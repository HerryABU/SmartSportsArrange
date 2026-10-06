package com.sports.service.schedule;

import com.sports.schedule.ai.AdversarialSchemeService;
import com.sports.schedule.ai.PrimaryMoeBridge;
import com.sports.schedule.ai.SuperMoeService;
import com.sports.schedule.ai.SuperScheduleEncoder;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import com.sports.schedule.opt.alns.AlnsImprover;
import com.sports.schedule.opt.ga.GeneticAlgorithm;
import com.sports.schedule.opt.fixopt.FixAndOptimizer;
import com.sports.schedule.opt.lns.LnsImprover;
import com.sports.schedule.opt.mnsa.MultiNeighborhoodAnnealer;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import com.sports.schedule.rule.RuleBasedScheduler;
import com.sports.schedule.rule.RuleScheduleConfig;
import com.sports.schedule.rule.RuleUnit;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sports.schedule.support.ScheduleSupport.*;

import com.sports.entity.event.Event;
import com.sports.schedule.analysis.ScheduleFeasibilityService;
import com.sports.schedule.core.math.SchedulePlacementMath;
import com.sports.service.arrange.ArrangementService;

/**
 * 赛程求解组件（从 {@code ScheduleService} 抽出）：负责把「每个项目的落位 + 时长」
 * 整体求出来——规则模式（确定性 first-fit）或优化模式（Timefold 组合求解 + GA + LNS）。
 *
 * <p>求解结果只写回传入的 {@code solvedPlacement}（并把求解器选定的时长写回 {@code unit.duration}），
 * 真正的落库仍由主循环统一完成——「求解」与「持久化」解耦，求解失败时零副作用回退贪心。</p>
 *
 * <p>持有两个算法服务与规则编排器，并复用 {@link ScheduleBuildComponent} 的场地池解析，
 * 全部为 Spring 无关的纯装配逻辑（仅持有注入的依赖）。</p>
 */
@Slf4j
public class ScheduleSolveComponent {

    private final ScheduleOptimizer scheduleOptimizer;
    private final RuleBasedScheduler ruleBasedScheduler;
    private final GeneticAlgorithm geneticAlgorithm;
    private final LnsImprover lnsImprover;
    private final MultiNeighborhoodAnnealer mnsaAnnealer;
    private final AlnsImprover alnsImprover;
    private final FixAndOptimizer fixAndOptimizer;
    private final ScheduleBuildComponent buildComponent;

    /**
     * 可解性诊断器（无状态纯分析，字段直持有即可，无需进 Spring 装配）。
     *
     * <p>刻意<b>不改构造器签名</b>：本组件是手工装配的（{@code ScheduleService} 里 new），
     * 加构造参数会连带改 4 个 {@code @InjectMocks} 测试类；而诊断器本身无外部依赖，
     * 直接持有最省事且不增加测试负担。</p>
     */
    private final ScheduleFeasibilityService feasibilityService = new ScheduleFeasibilityService();

    /**
     * 精修链调参的<b>延迟读取</b>入口——见 {@link ScheduleTuning} 的类注释。
     *
     * <p>⚠️ 绝不能在构造期把值快照成字段：facade 的 {@code @Value} 字段此时尚未注入，全是 0，
     * 而「&gt;0 才启用」的判据会让整条精修链静默关闭。这里持有一个 supplier，每次求解现取。</p>
     */
    private final java.util.function.Supplier<ScheduleTuning> tuningSupplier;

    public ScheduleSolveComponent(ScheduleOptimizer scheduleOptimizer,
                                  RuleBasedScheduler ruleBasedScheduler,
                                  GeneticAlgorithm geneticAlgorithm,
                                  LnsImprover lnsImprover,
                                  MultiNeighborhoodAnnealer mnsaAnnealer,
                                  AlnsImprover alnsImprover,
                                  FixAndOptimizer fixAndOptimizer,
                                  ScheduleBuildComponent buildComponent,
                                  java.util.function.Supplier<ScheduleTuning> tuningSupplier,
                                  java.util.function.Supplier<AdversarialSchemeService> adversarialSupplier) {
        this.scheduleOptimizer = scheduleOptimizer;
        this.ruleBasedScheduler = ruleBasedScheduler;
        this.geneticAlgorithm = geneticAlgorithm;
        this.lnsImprover = lnsImprover;
        this.mnsaAnnealer = mnsaAnnealer;
        this.alnsImprover = alnsImprover;
        this.fixAndOptimizer = fixAndOptimizer;
        this.buildComponent = buildComponent;
        this.tuningSupplier = tuningSupplier;
        this.adversarialSupplier = adversarialSupplier;
    }

    /**
     * AI 模式专属的推理时自对抗服务（G↔D 在推理时继续博弈），可为 null = 未接入，自动跳过。
     *
     * <p>用 supplier 而非构造参数，是为了让求解链仍然可独立构造（单测传 {@code () -> null}）；
     * 生产侧由 facade 传 {@link com.sports.schedule.ai.AdversarialSchemeService#current()}。</p>
     *
     * <p>⚠️ 同样不能缓存静态入口的<b>当时</b>返回值：{@code AdversarialSchemeService} 在自身构造器里
     * 写 {@code CURRENT}，与 facade 的创建先后由 Spring 决定——谁先谁后都可能。缓存一次就可能永久拿到
     * null，于是「AI 模式的自对抗」静默变成 unavailable。改成用时取值后与顺序无关。</p>
     */
    private final java.util.function.Supplier<AdversarialSchemeService> adversarialSupplier;

    /** 当前生效的精修链调参（每次读取都取最新值；求解链与启动期日志共用同一口径）。 */
    public ScheduleTuning tuning() {
        return tuningSupplier.get();
    }

    /**
     * 容器里的推理时自对抗服务；未创建（单测 / 未启用 AI）返回 null，调用方必须判空。
     * package-private 而非 private：装配自检测试要验证它确实是「用时取值」（见 ScheduleServiceWiringTest）。
     */
    AdversarialSchemeService adversarial() {
        return adversarialSupplier.get();
    }

    /** 精修链是否至少有一环被启用——装配自检用，见 {@link ScheduleTuning#refineChainEnabled()}。 */
    public boolean refineChainEnabled() {
        return tuning().refineChainEnabled();
    }
    /** 自对抗择优的冲突惩罚权重：越大越偏向「真零冲突」而非「像真解」 */
    private static final double ADVERSARIAL_LAMBDA = 0.5;

    // ==================== 以下方法由 scripts/refactor_extract_solve_component.py 从 ScheduleService 迁入 ====================

    /**
     * AI 模式专属：跑一遍**推理时自对抗**（G 采样 → 精修器精修 → D 评判 → 择优），
     * 把「模型来源 / 博弈轮数 / 判别器评分 / 候选方案的残余冲突」结构化输出。
     *
     * <p>只做观测与上报，不覆盖主方案（主方案由求解器决定并落库）：自对抗候选的解空间
     * 与求解器输出不同粒度（槽着色 vs 并发位时段），直接替换会破坏既有落库语义。
     * 报告里 {@code improved=true} 表示「多轮博弈确实把候选方案的冲突压得比单次生成更低」。</p>
     */
    private Map<String, Object> adversarialSelfCheck(RuleScheduleConfig cfg, List<ScheduleUnit> optUnits) {
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("laneStyle", cfg.aiLaneStyle());
        rep.put("rounds", Math.max(0, cfg.aiAdversarialRounds()));
        AdversarialSchemeService adv = adversarial();
        if (adv == null) {
            rep.put("adversarial", "unavailable");
            rep.put("note", "推理时自对抗服务未接入（AI 增强组件未创建），本次 AI 模式仅启用 AI 派遣款型与 AI 可解性诊断");
            return rep;
        }
        if (!adv.isAvailable()) {
            rep.put("adversarial", "unavailable");
            rep.put("note", "ONNX 模型不可用（缺失或加载失败），AI 模式已自动降级为优化模式");
            return rep;
        }
        try {
            Optional<AdversarialSchemeService.SchemeResult> r =
                    adv.generateAdversarially(optUnits, cfg.aiAdversarialRounds(), ADVERSARIAL_LAMBDA);
            if (r.isEmpty()) {
                rep.put("adversarial", "skipped");
                rep.put("note", "无可用单元参与对抗（单元列表为空）");
                return rep;
            }
            AdversarialSchemeService.SchemeResult s = r.get();
            rep.put("adversarial", "enabled");
            rep.put("dScore", r3(s.dScore()));
            rep.put("conflict", r4(s.conflict()));
            rep.put("conflictBefore", r4(s.conflictBefore()));
            rep.put("score", r3(s.score(ADVERSARIAL_LAMBDA)));
            rep.put("refined", s.refined());
            rep.put("roundsUsed", s.rounds());
            // 与 LnsImprover.Report 同口径：执行轮数 / 采纳轮数分开报，
            // 否则「没跑」与「跑了但没改进」无法区分。
            rep.put("roundsRun", s.roundsRun());
            rep.put("improved", s.conflict() < s.conflictBefore() - 1e-9);
            rep.put("note", "候选方案与求解器主方案独立评估、互不覆盖；主方案仍为落库结果");
            log.info("AI 自对抗: 执行 {} 轮/采纳自第 {} 轮, D分 {}, 候选冲突 {}（单次生成基线 {}），择优改进={}",
                    s.roundsRun(), s.rounds(), r3(s.dScore()), r4(s.conflict()), r4(s.conflictBefore()),
                    s.conflict() < s.conflictBefore() - 1e-9);
        } catch (Exception ex) {
            rep.put("adversarial", "error");
            rep.put("note", "自对抗执行失败（不影响编排）：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            log.warn("推理时自对抗失败（不影响编排）: {}", ex.toString());
        }
        return rep;
    }

    private static double r3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static double r4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /**
     * 规则模式编排（U39/B36）：把单元适配成 {@link RuleUnit}，交给确定性规则编排器出方案。
     *
     * <p>与 {@link #fillSolvedFromSolver} 完全对称：同样只写回 {@code solvedPlacement}
     * （及 {@code unit.duration}），真正的落库仍由主循环统一完成——「规则」与「持久化」解耦，
     * 规则排不下的单元由主循环贪心兜底，接口在任何情况下都能给出方案。</p>
     *
     * <p>组次/道次级规则（蛇形分组、固定分道）在 {@code saveSchedule → autoArrangeFor} 的既有管线
     * 中生效，本方法只负责「时间线」级别（哪个项目、哪天、哪个时段、哪刻开始、多长时间）。</p>
     */
    public void fillSolvedFromRules(List<Unit> units, Pool trackPool, Pool fieldPool,
                                     Map<String, Pool> dedicatedPools, String mainVenueCode,
                                     Set<String> fieldVenueCodes, Map<String, String> codeToName,
                                     Map<String, Integer> codeToParallelMax,
                                     int trackSlots, int fieldSlots, List<Window> windows,
                                     Map<Long, String> event2Group, int unitInterval,
                                     RuleScheduleConfig ruleConfig,
                                     Map<Unit, Placement> solvedPlacement,
                                     Map<String, Object> portfolioInfo) {
        // ① 与求解器完全相同的池解析/候选栅格口径（保证规则解与求解解可互替）
        Map<Unit, Pool> unitPool = new IdentityHashMap<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            unitPool.put(u, buildComponent.resolvePool(u, trackPool, fieldPool, mainVenueCode, fieldVenueCodes,
                    codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
        }
        if (unitPool.isEmpty()) return;

        Map<String, List<Placement>> rangeByPool = new LinkedHashMap<>();
        for (Pool p : unitPool.values()) {
            rangeByPool.computeIfAbsent(p.label, k -> SchedulePlacementMath.placementsOf(p, windows, unitInterval));
        }

        // ② 适配为规则层公共 DTO（不让 ScheduleService 的私有内部类泄漏出服务层）
        List<RuleUnit> ruleUnits = new ArrayList<>();
        Map<String, Unit> unitByKey = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Pool pool = unitPool.get(u);
            if (pool == null) continue;
            List<Placement> range = rangeByPool.get(pool.label);
            List<Placement> cands = new ArrayList<>();
            int floor = SchedulePlacementMath.minDurationOf(u);
            for (Placement p : range) {
                if (p.getMaxDuration() >= floor) cands.add(p);
            }
            if (cands.isEmpty()) continue;   // 该池整块放不下 → 交给贪心如实报「排不下」
            String key = "u" + i;
            unitByKey.put(key, u);
            ruleUnits.add(new RuleUnit(key, u.event.getId(), u.event.getName(), u.grade, u.track,
                    pool.label, u.track ? null : event2Group.get(u.event.getId()),
                    Math.max(SchedulePlacementMath.intervalOf(u, unitInterval), 1), u.rawDuration, floor,
                    SchedulePlacementMath.sortedAthletes(u), cands));
        }
        if (ruleUnits.isEmpty()) return;

        // ③ 规则编排（确定性、毫秒级）
        RuleBasedScheduler.RulePlan plan = ruleBasedScheduler.plan(ruleUnits, ruleConfig);
        for (Map.Entry<String, RuleBasedScheduler.Assignment> e : plan.assignments().entrySet()) {
            Unit u = unitByKey.get(e.getKey());
            if (u == null) continue;
            solvedPlacement.put(u, e.getValue().placement());
            u.duration = e.getValue().duration();
        }
        if (portfolioInfo != null) {
            portfolioInfo.put("rule", Map.of(
                    "placed", plan.placedCount(),
                    "unplaced", plan.unplacedCount(),
                    "residualConflicts", plan.residualConflicts(),
                    "elapsedMillis", plan.elapsedMillis(),
                    "lanePolicy", ruleConfig.lanePolicy().name(),
                    "advanceCount", ruleConfig.advanceCount(),
                    "conflictBufferMinutes", ruleConfig.conflictBufferMinutes()));
            portfolioInfo.put("basis", "规则模式：确定性 first-fit（蛇形分组 + 固定分道 + 时间栅格），"
                    + "毫秒级可复现；自检与下界评估照常执行");
        }
        log.info("规则编排: 排入 {}/{} 个单元, 残余兼项冲突 {} 处, 耗时 {}ms",
                plan.placedCount(), ruleUnits.size(), plan.residualConflicts(), plan.elapsedMillis());
    }


    /**
     * 用约束求解器求出全局编排方案（每个项目的落位 + 时长）。
     *
     * <p>求解结果只写回 {@code solvedPlacement}（并把求解器选定的时长写回 {@code unit.duration}），
     * 真正的落库仍由主循环统一完成——「求解」与「持久化」解耦，求解失败时零副作用回退贪心。</p>
     *
     * @param solverStat 出参：[0] = 采用解的项目数，[1] = 求解后的残余兼项冲突数
     */
    public void fillSolvedFromSolver(List<Unit> units, Pool trackPool, Pool fieldPool,
                                      Map<String, Pool> dedicatedPools, String mainVenueCode,
                                      Set<String> fieldVenueCodes, Map<String, String> codeToName,
                                      Map<String, Integer> codeToParallelMax,
                                      int trackSlots, int fieldSlots, List<Window> windows,
                                      Map<Long, String> event2Group, int unitInterval,
                                      RuleScheduleConfig ruleConfig,
                                      Map<Unit, Placement> solvedPlacement, int[] solverStat,
                                      Map<String, Object> portfolioInfo) {
        if (scheduleOptimizer == null) return;

        // ① 预解析每个单元所属的并发池。与主循环调用同一个方法、同一顺序，
        //    因此主循环再次解析必然得到同一个池，不会出现「解落在别的池」。
        Map<Unit, Pool> unitPool = new IdentityHashMap<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            unitPool.put(u, buildComponent.resolvePool(u, trackPool, fieldPool, mainVenueCode, fieldVenueCodes,
                    codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
        }
        if (unitPool.isEmpty()) return;

        // ② 位置值域按池生成一次并复用（同池单元共享同一批候选位置）
        Map<String, List<Placement>> rangeByPool = new LinkedHashMap<>();
        for (Pool p : unitPool.values()) {
            rangeByPool.computeIfAbsent(p.label, k -> SchedulePlacementMath.placementsOf(p, windows, unitInterval));
        }

        // ③ 组装计划实体：每个单元只暴露「自己池里、且放得下其时长下限」的位置
        List<ScheduleUnit> optUnits = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Pool pool = unitPool.get(u);
            if (pool == null) continue;
            int floor = SchedulePlacementMath.minDurationOf(u);
            List<Placement> cands = new ArrayList<>();
            for (Placement p : rangeByPool.get(pool.label)) {
                if (p.getMaxDuration() >= floor) cands.add(p);
            }
            if (cands.isEmpty()) continue;   // 该池整块放不下 → 交给贪心如实报「排不下」
            optUnits.add(new ScheduleUnit("u" + i, u.event.getId(), u.event.getName(), u.grade, u.track,
                    pool.label, u.track ? null : event2Group.get(u.event.getId()),
                    unitInterval, u.rawDuration, floor, SchedulePlacementMath.sortedAthletes(u), SchedulePlacementMath.durationChoicesOf(u), cands,
                    ruleEventAttrs(u.event)));
        }
        if (optUnits.isEmpty()) return;

        List<Placement> allPlacements = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Placement> list : rangeByPool.values()) {
            for (Placement p : list) {
                if (seen.add(p.getId())) allPlacements.add(p);
            }
        }

        // ④ 求解：先提取实例特征，再由算法组合层选出候选算法，波次跑完取最优。
        //    容量紧张 → 模拟退火/迟接受（允许暂时变差才跳得出「用压缩换时间」的深坑）；
        //    容量宽裕 → 禁忌搜索（记住走过的路，避免循环）；兼项密集 → 多样化迟接受（多邻域）。
        SchedulePlan problem = new SchedulePlan(allPlacements, optUnits);

        // ④-0 **可解性诊断**：把「排得下吗 / 排不下的是什么 / 怎么办」结构化输出进编排响应。
        //     编排接口过去只回「编排完成 + 统计」，操作者看不到「哪些其实放不下、为什么」；
        //     诊断与下游求解读**同一份** (optUnits, allPlacements)，因此结论可复现、可追溯。
        //     三类不可解分开报告：容量缺口（加天/加场地）、超大单元（必须拆批）、
        //     团下界（结构性不可解，加场地无效）；外加未排清单与建议动作。
        if (portfolioInfo != null) {
            try {
                Map<String, Object> feasibility = feasibilityService.diagnose(optUnits, allPlacements);
                portfolioInfo.put("feasibility", feasibility);
                Map<?, ?> summary = (Map<?, ?>) feasibility.get("summary");
                log.info("可解性诊断: 可排 {}/{} 组次（{}），未排 {}；可行={}；最少 {} 天",
                        summary.get("placed"), summary.get("tasks"),
                        ScheduleFeasibilityService.percent(
                                ((Number) summary.get("placed")).intValue(),
                                ((Number) summary.get("tasks")).intValue()),
                        summary.get("unplaced"), feasibility.get("feasible"),
                        ((Map<?, ?>) feasibility.get("bounds")).get("minDaysByCapacity"));
            } catch (Exception ex) {
                log.warn("可解性诊断失败（不影响编排）: {}", ex.toString());
            }
        }

        // ④-0b **AI 模式专属：推理时自对抗自检**（G 采样 → 精修器 → D 评判 → 择优）。
        //     只在 AI 模式跑：把「模型从哪来、博弈了几轮、相比单次生成是否更优」结构化输出，
        //     让「AI 模式」不只是换个按钮——它真的跑了一遍生成对抗，且把过程写进编排响应。
        //     主方案仍以上面求解器的结果为准（自对抗不覆盖已落库的赛程），只作观测与建议。
        if (ruleConfig != null && ruleConfig.aiMode() && portfolioInfo != null) {
            portfolioInfo.put("aiReport", adversarialSelfCheck(ruleConfig, optUnits));
        }

        // ④-0c **第一次编排用主 MoE**（2026-10-05）。
        //
        //     为什么放在求解**之前**：Timefold 的构造启发式从「实体集合顺序」出发，
        //     初始顺序直接决定它先给谁找位置 —— 冲突簇中心的单元越早被安排，
        //     后续单元越容易在其剩余空间里落位。主 MoE 的 priority 就是学出来的
        //     「谁该先排」，把它作为初始顺序，是「第一次编排用主模型」的落地。
        //
        //     与 ④-0b 的自对抗自检的区别：那个只输出观测报告、不改变求解；
        //     这里的重排**真的改变求解起点**。所以必须显式写进 portfolioInfo，
        //     否则「AI 到底有没有影响结果」在编排响应里看不出来。
        //
        //     ⚠️ 三条边界：
        //     ① 只**重排**，不增删单元（`optUnits` 集合不变）—— 否则会改变可解性结论；
        //     ② 顺序按 priority 降序、等值时保持原相对顺序（稳定排序），
        //        避免「同样好的两个单元每次跑出来顺序都不同」这种不可复现；
        //     ③ 模型不可用/推理失败 → 原样返回，求解照常进行。
        if (portfolioInfo != null) {
            try {
                List<SuperScheduleEncoder.Unit> encUnits = toEncoderUnits(units);
                SuperScheduleEncoder.Encoded enc = PRIMARY_ENCODER.encode(
                        encUnits, toEncoderWindows(windows, units), 0, units.size());
                java.util.Optional<SuperMoeService.Advice> adv =
                        PrimaryMoeBridge.advise(enc);
                if (adv.isPresent()) {
                    double[] priority = adv.get().priority();
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("model", "super_moe");
                    info.put("mode", "主 MoE 优先级作为求解初始顺序");
                    info.put("n", priority == null ? 0 : priority.length);
                    info.put("style", adv.get().nextStepName());
                    if (priority != null && priority.length > 0) {
                        int before = optUnits.size();
                        List<ScheduleUnit> reordered = new ArrayList<>(optUnits);
                        // id 形如 "u<i>"，i 就是 units 的下标 —— 用它取对应优先级。
                        // 稳定排序：Java 的 List.sort 是归并排序（稳定），等值保持原序。
                        reordered.sort(java.util.Comparator.comparingDouble(
                                (ScheduleUnit u) -> -priorityOf(u, priority)));
                        optUnits = reordered;
                        info.put("applied", true);
                        info.put("units", before);
                        log.info("第一次编排: 应用主 MoE 优先级重排 {} 个单元的求解初始顺序"
                                + "（模型建议下一步: {}）", before, adv.get().nextStepName());
                    } else {
                        info.put("applied", false);
                        info.put("reason", "主 MoE 未给出优先级");
                    }
                    portfolioInfo.put("primaryMoe", info);
                } else {
                    portfolioInfo.put("primaryMoe", Map.of(
                            "applied", false,
                            "reason", PrimaryMoeBridge.bound()
                                    ? "主 MoE 不可用或推理失败（已回退原顺序）"
                                    : "主 MoE 未装配（静态桥未绑定）"));
                }
            } catch (Exception ex) {
                // 主 MoE 是增强项：失败必须只降级、不影响求解
                log.warn("主 MoE 初始顺序建议失败（不影响求解）: {}", ex.toString());
                portfolioInfo.put("primaryMoe", Map.of("applied", false,
                        "reason", ex.getClass().getSimpleName()));
            }
        }

        AlgorithmPortfolio.Features features = AlgorithmPortfolio.extract(optUnits, allPlacements);
        if (portfolioInfo != null) {
            portfolioInfo.put("features", features.toMap());
            List<String> names = new ArrayList<>();
            for (AlgorithmPortfolio.Plan p : AlgorithmPortfolio.planFor(features, 1)) {
                names.add(p.name());
            }
            portfolioInfo.put("candidates", names);
            portfolioInfo.put("basis", "按实例特征（紧张度 / 兼项密度）动态选择；"
                    + "多算法并行探索后取评分最优者");
        }
        SchedulePlan solvedPlan = scheduleOptimizer.solveWithPortfolio(problem, features).orElse(null);
        if (solvedPlan == null) {
            // 求解失败也要给出 AI 自对抗的报告（否则 AI 模式下除错报告缺失，运维无从判断）
            if (ruleConfig != null && ruleConfig.aiMode() && portfolioInfo != null) {
                portfolioInfo.put("aiReport", adversarialSelfCheck(ruleConfig, optUnits));
            }
            return;
        }

        // 精修链调参：**在这里取一次**（而不是构造期快照）。facade 的 @Value 字段到这一步早已注入，
        // 因此拿到的就是配置文件里的真实值；构造期取值只会拿到 0 并把整条链关掉。
        final ScheduleTuning tuning = tuning();

        // ④b **遗传算法（GA）**：种群 + 交叉 + 变异，全局并行探索。
        //     与多起点波次的区别：波次是「各跑各的、跑完比大小」，GA 让好解之间繁殖——
        //     好的落位模式被交叉重组、坏的被变异扰动，能组合出任何单个起点都到不了的结构。
        try {
            if (tuning.gaEnabled()) {
                SchedulePlan before = solvedPlan;
                Optional<SchedulePlan> evolved = geneticAlgorithm.evolve(
                        before, tuning.gaPopulation(), tuning.gaGenerations(),
                        java.time.Duration.ofMillis(Math.max(200, tuning.gaIndividualMillis())),
                        tuning.gaMutationRate());
                if (evolved.isPresent()) {
                    solvedPlan = evolved.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("ga", "已启用：种群 " + tuning.gaPopulation()
                                + "、" + tuning.gaGenerations() + " 代");
                        portfolioInfo.put("gaScore", String.format("%s → %s",
                                before.getScore(), solvedPlan.getScore()));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("ga", "种群进化后无改进（初始解已足够好）");
                }
            }
        } catch (Exception ex) {
            log.warn("遗传算法失败（保留算法组合层的结果）: {}", ex.toString());
        }

        // ④c **大邻域搜索精修（LNS）**：破坏一块 → 只重建这一块 → 只接受更好的。
        //     与上游的区别在粒度：局部搜索一次动一个项目，LNS 一次拔出「一个年级 / 一个时段窗口 /
        //     某个最忙运动员的全部项目」，在子空间里重新优化，其余部分用 @PlanningPin 锁定不动。
        //     因此每轮代价很小，能在同样预算里做很多次真正触到"根"的调整。
        try {
            if (tuning.lnsEnabled()) {
                int rounds = Math.min(tuning.lnsRounds(), 20);
                long perRound = Math.max(200, tuning.lnsRoundMillis());
                SchedulePlan before = solvedPlan;
                Optional<SchedulePlan> improved =
                        lnsImprover.improve(before, rounds, java.time.Duration.ofMillis(perRound));
                if (improved.isPresent()) {
                    solvedPlan = improved.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("lns", "已启用：破坏-重建 " + rounds + " 轮，每轮 " + perRound + "ms");
                        portfolioInfo.put("lnsScore", String.format("%s → %s",
                                before.getScore(), solvedPlan.getScore()));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("lns", rounds + " 轮未改进（当前解已局部稳定）");
                }
            }
        } catch (Exception ex) {
            log.warn("LNS 精修失败（保留算法组合层的结果）: {}", ex.toString());
        }

        // ④d **多邻域模拟退火精修（MNSA）**：六种邻域移动（换位/迁移/时长/压缩/拔除冲突/补排空缺）
        //     + UCB1 自适应切换 + SA 接受准则。与 LNS 的区别在粒度与代价：LNS 每轮要跑一次求解器、
        //     改得深但步数少；MNSA 一步只是「改一两个单元的落位/时长 + 快速评分」，能在同样预算里
        //     做几十上百步。中途允许暂时变差（退火爬坡），但只把严格更优的解交给下游。
        try {
            if (tuning.mnsaEnabled()) {
                int iters = Math.min(tuning.mnsaIterations(), 200);
                SchedulePlan before = solvedPlan;
                Map<String, Object> mnsaInfo = new LinkedHashMap<>();
                Optional<SchedulePlan> annealed =
                        mnsaAnnealer.anneal(before, iters, ScheduleOptimizer.RANDOM_SEED, mnsaInfo);
                if (annealed.isPresent()) {
                    solvedPlan = annealed.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("mnsa", "已启用：" + iters + " 步多邻域退火（六邻域 UCB1 自适应）");
                        portfolioInfo.put("mnsaScore", String.valueOf(mnsaInfo.get("score")));
                        portfolioInfo.put("mnsaStats", mnsaInfo.get("moveStats"));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("mnsa", iters + " 步退火后无改进（当前解已局部稳定）");
                }
            }
        } catch (Exception ex) {
            log.warn("MNSA 精修失败（保留上游算法的结果）: {}", ex.toString());
        }

        // ⑤ **自适应大邻域搜索精修（ALNS）**：破坏-修复循环 + UCB1 双老虎机。
        //     与 ④c 固定轮换邻域的 LNS 相比：破坏端四种算子（随机/冲突簇/最忙运动员/窗口）
        //     由老虎机按实际收益选择——冲突簇破坏直接利用兼项冲突的图结构；修复端
        //     贪心/随机插入代替完整求解，单轮成本降低一个数量级，同样预算能做更多轮。
        try {
            if (tuning.alnsEnabled()) {
                int rounds = Math.min(tuning.alnsRounds(), 30);
                SchedulePlan before = solvedPlan;
                Map<String, Object> alnsInfo = new LinkedHashMap<>();
                Optional<SchedulePlan> improved =
                        alnsImprover.improve(before, rounds, ScheduleOptimizer.RANDOM_SEED, alnsInfo);
                if (improved.isPresent()) {
                    solvedPlan = improved.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("alns", "已启用：" + rounds + " 轮破坏-修复（UCB1 自适应算子选择）");
                        portfolioInfo.put("alnsScore", String.valueOf(alnsInfo.get("score")));
                        portfolioInfo.put("alnsStats", Map.of(
                                "destroy", alnsInfo.get("destroyStats"),
                                "repair", alnsInfo.get("repairStats")));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("alns", rounds + " 轮未改进（当前解已局部稳定）");
                }
            }
        } catch (Exception ex) {
            log.warn("ALNS 精修失败（保留上游算法的结果）: {}", ex.toString());
        }

        // ⑥ **Fix-and-Optimize 局部精确修复**：把残余兼项冲突切成连通分量切片，
        //     逐片「冻结其余（@PlanningPin）+ 小预算精确重排」。轻手法（MNSA/ALNS）之后
        //     仍有顽固冲突链时，只有放开整条链才有自由度真正错开——解多个小规模子问题
        //     优于解一个大问题，与 Benders「告诉求解器病在哪」的思想同源。
        //     2026-09-27 升级为多轮重排：每轮治至多 10 个切片、预算逐轮翻倍（封顶 ×4），
        //     切片全部消除或整轮无接受即停——旧实现只治一轮、剩余切片原样残留。
        try {
            if (tuning.fixoptEnabled()) {
                int passes = Math.min(tuning.fixoptRounds(), 5);
                long sliceMillis = Math.max(200, tuning.fixoptSliceMillis());
                SchedulePlan before = solvedPlan;
                Map<String, Object> fixoptInfo = new LinkedHashMap<>();
                Optional<SchedulePlan> repaired = fixAndOptimizer.optimizeMultiPass(
                        before, passes, 10, java.time.Duration.ofMillis(sliceMillis),
                        ScheduleOptimizer.RANDOM_SEED, fixoptInfo);
                if (repaired.isPresent()) {
                    solvedPlan = repaired.get();
                    if (portfolioInfo != null) {
                        portfolioInfo.put("fixopt", "已启用：" + passes + " 轮升级重排（预算逐轮翻倍），治疗 "
                                + fixoptInfo.get("slicesTreated") + "/" + fixoptInfo.get("slicesTotal")
                                + " 个冲突切片，接受 " + fixoptInfo.get("accepted") + " 个");
                        portfolioInfo.put("fixoptScore", String.valueOf(fixoptInfo.get("score")));
                    }
                } else if (portfolioInfo != null) {
                    portfolioInfo.put("fixopt", "无冲突切片或重排后无改进（当前解已局部稳定）");
                }
            }
        } catch (Exception ex) {
            log.warn("Fix-and-Optimize 精修失败（保留上游算法的结果）: {}", ex.toString());
        }

        // ⑤ 回填：位置与时长一起生效。时长写回 u.duration 之后，compressionReport 反映的就是
        //    真正落地的时长，而不是「预计要压多少」。
        int applied = 0;
        for (ScheduleUnit su : solvedPlan.getUnits()) {
            if (!su.isPlaced()) continue;
            int idx = Integer.parseInt(su.getKey().substring(1));
            Unit u = units.get(idx);
            solvedPlacement.put(u, su.getPlacement());
            u.duration = su.getDuration();
            applied++;
        }
        solverStat[0] = applied;
        solverStat[1] = SchedulePlacementMath.countResidualClashes(solvedPlacement);
    }

    /**
     * 规则注入上下文里的 {@code event.*} 字段——键与
     * {@code ArrangementService#ruleContextOf} <b>逐一对齐</b>。
     *
     * <p>两条路径（编排阶段逐落位注入、求解阶段动态约束）若字段名不一致，同一份规则片段
     * 就会出现「编排时命中、求解时静默不命中」的诡异现象。这里显式对齐，杜绝该缺陷。</p>
     */
    private static Map<String, Object> ruleEventAttrs(com.sports.entity.event.Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) {
            return m;
        }
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("category", e.getCategory());
        m.put("track", e.getTrack());
        m.put("team", e.getTeam());
        m.put("teamMembers", e.getTeamMembers());
        m.put("concurrency", e.getConcurrency());
        m.put("venueCode", e.getDefaultVenueCode());
        m.put("gradeGroup", e.getGradeGroup());
        return m;
    }

    // ==================== 主 MoE（第一次编排）====================

    /**
     * 主 MoE 编码器。
     *
     * <p>无状态、无外部依赖（不读库、不注容器），字段直持有即可 ——
     * 刻意<b>不进构造签名</b>：本组件是手工 assembly 的，
     * 加构造参数会连带改 4 个 {@code @InjectMocks} 测试类，收益不抵风险。
     * 与 {@code ScheduleFeasibilityService} 同一处理方式。</p>
     */
    private static final SuperScheduleEncoder PRIMARY_ENCODER = new SuperScheduleEncoder();

    /**
     * 取某单元对应的主 MoE 优先级。
     *
     * <p>{@code ScheduleUnit.id} 形如 {@code "u<i>"}（见 ③ 处的组装），
     * {@code i} 就是 {@code units} 的下标，而主 MoE 的 {@code priority[]}
     * 与 {@code units} <b>同序</b> —— 所以按下标直接取，不需要额外映射表。</p>
     *
     * <p>越界（单元数超过 {@code MAX_NODES} 被编码器截断）时返回
     * {@code -INFINITY}，让这些单元**稳定地排在末尾**，
     * 而不是抛异常或随机插入（后者会让同一份输入每次跑出不同顺序，不可复现）。</p>
     */
    private static double priorityOf(ScheduleUnit u, double[] priority) {
        String id = u.getKey();
        int i = -1;
        if (id != null && id.length() > 1 && id.charAt(0) == 'u') {
            try {
                i = Integer.parseInt(id.substring(1));
            } catch (NumberFormatException ignored) {
                i = -1;
            }
        }
        return (i >= 0 && i < priority.length) ? priority[i] : Double.NEGATIVE_INFINITY;
    }

    /**
     * 把编排原语 {@code core.primitive.Unit} 转成主 MoE 编码器要的
     * {@link SuperScheduleEncoder.Unit}。
     *
     * <p>⚠️ 两个类**同名不同类**，直接传会报
     * 「List&lt;core.primitive.Unit&gt; 无法转换为 List&lt;SuperScheduleEncoder.Unit&gt;」——
     * 这类编译错误还算友好，真正危险的是「名字像就以为能混用」。</p>
     *
     * <p>字段映射原则：<b>只搬编码器真正读的那些</b>，不臆造。
     * {@code task} 取「有无并道/组次语义」推得的三档（与数据侧 TASK_* 的
     * 前几位语义一致）；{@code athletes} 用 {@code athleteIds}（兼项边的依据）。</p>
     */
    private static List<SuperScheduleEncoder.Unit> toEncoderUnits(List<Unit> units) {
        List<SuperScheduleEncoder.Unit> out = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            out.add(new SuperScheduleEncoder.Unit(
                    "u" + i,
                    u.event == null ? "" : u.event.getName(),
                    taskOf(u),
                    u.track,
                    u.event != null && Boolean.TRUE.equals(u.event.getTeam()),
                    null,
                    null,
                    u.event == null ? null : u.event.getDefaultVenueCode(),
                    null,
                    u.grade,
                    Math.max(1, u.duration),
                    0,
                    u.heats,
                    new ArrayList<>(u.athleteIds),
                    null,
                    null,
                    "main"));
        }
        return out;
    }

    /** 由单元语义推出任务位（与 {@code super_scenarios.TASK_*} 的前几位对齐）。 */
    private static int taskOf(Unit u) {
        if (u.event != null && Boolean.TRUE.equals(u.event.getTeam())) {
            return 2;      // TASK_BALL：球类赛制
        }
        return u.track ? 1 : 0;   // 径赛 → 道次编排；其余 → 项目编排
    }

    /**
     * 把编排窗口转成编码器窗口。
     *
     * <p>⚠️ 两个 {@code Window} **同名不同类**，且字段集不同：
     * 编排原语的 {@code core.primitive.Window} 是
     * {@code (day, date, slotName, startMinute, capacity)}——<b>没有场地</b>
     * （场地信息在 {@code Pool.venueOf} 里，按并发位给）；
     * 而编码器的 {@code Window} 是 {@code (day, windowIdx, capacity, venue, pool)}。</p>
     *
     * <p>场地不能瞎填：编码器拿 {@code w.venue()} 与 {@code u.venue()} 配对算
     * 「该场地在该时段的容量」，并对不上就退回默认容量 1 —— 那会让
     * {@code E_TIME} 边全按容量 1 计算，AI 看到的装箱压力完全失真。
     * 所以这里按「**核心窗口 × 单元场地**」展开：同一时段每个场地各开一条窗口、
     * 容量取核心窗口的值。语义上也对 —— 某时段的容量本就对该时段的每个场地成立。</p>
     */
    private static List<SuperScheduleEncoder.Window> toEncoderWindows(
            List<Window> windows, List<Unit> units) {
        List<SuperScheduleEncoder.Window> out = new ArrayList<>();
        if (windows == null || windows.isEmpty()) {
            return out;
        }
        // 场地集合取自单元（编码器就是这样与 u.venue() 配对的）
        java.util.LinkedHashSet<String> venues = new java.util.LinkedHashSet<>();
        for (Unit u : units) {
            if (u.event != null && u.event.getDefaultVenueCode() != null) {
                venues.add(u.event.getDefaultVenueCode());
            }
        }
        if (venues.isEmpty()) {
            venues.add(null);          // 无场地信息时保留单条，交由编码器走默认容量
        }
        int idx = 0;
        for (Window w : windows) {
            for (String v : venues) {
                out.add(new SuperScheduleEncoder.Window(w.day, idx++, w.capacity, v, null));
            }
        }
        return out;
    }
}
