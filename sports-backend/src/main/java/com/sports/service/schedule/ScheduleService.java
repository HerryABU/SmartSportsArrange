package com.sports.service.schedule;

import com.sports.common.util.Grades;
import com.sports.collab.ScheduleCollaborationService;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.entity.registration.Registration;
import com.sports.entity.venue.Venue;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.arrange.ArrangementReservationRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.venue.VenueRepository;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.analysis.LowerBoundEstimator;
import com.sports.schedule.analysis.DaysEstimator;
import com.sports.schedule.opt.ga.GeneticAlgorithm;
import com.sports.schedule.opt.alns.AlnsImprover;
import com.sports.schedule.opt.fixopt.FixAndOptimizer;
import com.sports.schedule.opt.lns.LnsImprover;
import com.sports.schedule.opt.mnsa.MultiNeighborhoodAnnealer;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import com.sports.schedule.rule.RuleBasedScheduler;
import com.sports.schedule.rule.RuleScheduleConfig;
import com.sports.schedule.rule.RuleUnit;
import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.sports.schedule.support.ScheduleSupport.*;

import com.sports.controller.arrange.ArrangementController;
import com.sports.schedule.ai.AdversarialSchemeService;
import com.sports.schedule.core.math.ScheduleAnalysisMath;
import com.sports.schedule.core.math.SchedulePlacementMath;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.arrange.ConflictService;
import com.sports.service.arrange.HeatStaggerService;
import com.sports.service.audit.AuditService;
import com.sports.service.protection.AdminTimeProtectionService;
import com.sports.schedule.support.protection.ProtectionMath;
import com.sports.entity.protection.AdminTimeProtection;
import com.sports.service.system.SetupService;
import com.sports.service.system.SystemService;

/**
 * 项目赛程编排服务（项目编排）
 *
 * <p>严格依据 {@code meet_schedule} 配置生成赛程，全部参数可配置、无硬编码：</p>
 * <ul>
 *   <li><b>日期</b>：startDate + days 推出 xD-yD，每天的时段（AM/PM）起止可各不相同；</li>
 *   <li><b>年级顺序</b>：取自 gradeOrder（缺省按 grades 的 sortOrder 升序），
 *       默认高一→高二→高三仅是可修改的默认值，不写死在代码里；</li>
 *   <li><b>并发位数（取代串行/并行开关）</b>：trackSlots / fieldSlots —— 1 = 串行，
 *       n = 同一时刻可同时进行 n 个项目。槽位与场地一一对应（场地不足时复用并告警）；</li>
 *   <li><b>场地表（并行上限）</b>：当数据库存在启用的场地（venue 表）时，其 {@code parallelMax}
 *       即该场地并发上限，主场地/首个田赛场地的 parallelMax 分别作为径赛池/田赛池的槽位数；
 *       项目通过 defaultVenueCode 绑定场地后，其并发数受该场地 parallelMax 约束（1=串行，n=并行）。</li>
 *   <li><b>自定义项目顺序</b>：eventOrder（eventId 有序列表，田赛+径赛混排）优先，
 *       未列入的项目按 sortOrder 追加；</li>
 *   <li><b>田赛分组</b>：fieldGroups 中同一组的田赛项目安排在同一时段并行进行；</li>
 *   <li><b>项目内并发</b>：event.concurrency（径赛默认取道次数；田赛默认 1），
 *       时长 = ceil(人数 / 并发) × 单轮用时，并受 maxDurationMinutes 封顶；
 *       项目之间插入 intervalMinutes 间隔。</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
public class ScheduleService {
    public ScheduleService(EventScheduleRepository scheduleRepository,
                            EventRepository eventRepository,
                            RegistrationRepository registrationRepository,
                            ArrangementRepository arrangementRepository,
                            ArrangementService arrangementService,
                            SystemService systemService,
                            ConflictService conflictService,
                            VenueRepository venueRepository,
                            EventRefereeRepository eventRefereeRepository,
                            ArrangementReservationRepository arrangementReservationRepository,
                            ScheduleOptimizer scheduleOptimizer,
                            ScheduleVerifier scheduleVerifier,
                            LowerBoundEstimator lowerBoundEstimator,
                            LnsImprover lnsImprover,
                            GeneticAlgorithm geneticAlgorithm,
                            MultiNeighborhoodAnnealer mnsaAnnealer,
                            AlnsImprover alnsImprover,
                            FixAndOptimizer fixAndOptimizer,
                            ScheduleCollaborationService collaborationService,
                            RuleBasedScheduler ruleBasedScheduler,
                            AuditService auditService,
                            AdminTimeProtectionService protectionService,
                            HeatStaggerService heatStaggerService) {
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.arrangementRepository = arrangementRepository;
        this.arrangementService = arrangementService;
        this.systemService = systemService;
        this.conflictService = conflictService;
        this.venueRepository = venueRepository;
        this.eventRefereeRepository = eventRefereeRepository;
        this.arrangementReservationRepository = arrangementReservationRepository;
        this.scheduleOptimizer = scheduleOptimizer;
        this.scheduleVerifier = scheduleVerifier;
        this.lowerBoundEstimator = lowerBoundEstimator;
        this.lnsImprover = lnsImprover;
        this.geneticAlgorithm = geneticAlgorithm;
        this.mnsaAnnealer = mnsaAnnealer;
        this.alnsImprover = alnsImprover;
        this.fixAndOptimizer = fixAndOptimizer;
        this.collaborationService = collaborationService;
        this.ruleBasedScheduler = ruleBasedScheduler;
        this.auditService = auditService;
        this.protectionService = protectionService;
        this.heatStaggerService = heatStaggerService;
        this.buildComponent = new ScheduleBuildComponent(eventRepository, registrationRepository, systemService);
        // 准备阶段（配置合并 / 场地池 / 时间窗 / 行政保护 / 可行性）独立成组件，见 AutoSchedulePreparer
        this.preparer = new AutoSchedulePreparer(buildComponent, systemService, venueRepository, protectionService);
        this.selfCheckComponent = new ScheduleSelfCheckComponent(lowerBoundEstimator, buildComponent);
        // ⚠️ 精修链调参与自对抗服务都必须**延迟读取**（传 supplier，不传值）：
        //    · @Value 字段在构造器执行时还没注入（全是 0），而精修链以「>0 才启用」为开关
        //      ⇒ 直接传值会让 GA/LNS/MNSA/ALNS/Fix-opt 在生产环境静默全部关闭；
        //    · AdversarialSchemeService 在自身构造器里写静态 CURRENT，与本体谁先创建由 Spring 决定
        //      ⇒ 直接传值可能永久拿到 null，AI 模式的自对抗静默降级。
        //    这一处曾同时踩中两条（见 ScheduleTuning 类注释与 ScheduleServiceWiringTest）。
        this.solveComponent = new ScheduleSolveComponent(scheduleOptimizer, ruleBasedScheduler, geneticAlgorithm, lnsImprover,
                mnsaAnnealer, alnsImprover, fixAndOptimizer, buildComponent,
                this::tuning, AdversarialSchemeService::current);
        this.placementComponent = new SchedulePlacementComponent(arrangementService, arrangementRepository, scheduleRepository, buildComponent);
        // 多趟放置择优 + 真实冲突精修（原 autoSchedule 内 180 行内联逻辑，已独立成组件）
        this.placementRunner = new MultiStartPlacementRunner(scheduleRepository, arrangementService,
                conflictService, buildComponent, placementComponent);
        this.queryExportComponent = new ScheduleQueryExportComponent(scheduleRepository, eventRepository, arrangementRepository,
                eventRefereeRepository, arrangementReservationRepository, collaborationService, auditService);
    }

    /**
     * 精修链调参快照——<b>每次读取都现取</b>，绝不在构造期缓存成字段。
     *
     * <p>本方法存在的唯一理由是修一个真实的静默失效：{@code @Value} 是字段注入（构造之后），
     * 而 {@code ScheduleSolveComponent} 对每个算法都以「&gt;0 才启用」为开关。旧实现把 10 个
     * 字段值直接传进构造器 ⇒ 拿到的全是 0 ⇒ GA/LNS/MNSA/ALNS/Fix-opt 在生产环境从未跑过，
     * 而日志、前端、接口全部正常。详见 {@link ScheduleTuning}。</p>
     */
    private ScheduleTuning tuning() {
        return new ScheduleTuning(lnsRounds, lnsRoundMillis, gaPopulation, gaGenerations, gaMutationRate,
                gaIndividualMillis, mnsaIterations, alnsRounds, fixoptRounds, fixoptSliceMillis);
    }

    /** 装配自检用（与 facade 同包，见 {@code ScheduleServiceWiringTest}）。 */
    ScheduleSolveComponent solveComponent() {
        return solveComponent;
    }

    /**
     * 启动期把「精修链实际生效参数」打进日志，并在全部关闭时显式告警。
     *
     * <p>纪律：凡是「配了不生效」的静默失效，都要有一个启动期可观测的口子。这里既打印开/关，
     * 也打印实际取值——否则「没跑」与「跑了但没改进」在事后无法区分。</p>
     */
    @PostConstruct
    void logEffectiveTuning() {
        ScheduleTuning t = tuning();
        if (!t.refineChainEnabled()) {
            log.warn("[schedule] ⚠️ 精修链全部关闭（{}）。若配置里已写入非 0 值，说明参数未生效——"
                    + "检查是否在构造期就读走了 @Value 字段（见 ScheduleTuning 类注释）", t.describe());
        } else {
            log.info("[schedule] 精修链生效参数: {}", t.describe());
        }
    }


    private final EventScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ArrangementService arrangementService;
    private final SystemService systemService;
    private final ConflictService conflictService;
    private final VenueRepository venueRepository;
    /** 裁判分配（一键清空赛程时与道次编排一起清，避免重新编排后新旧分配错位） */
    private final EventRefereeRepository eventRefereeRepository;
    /** 编排预留空位（同上，一键清空的连带清理对象） */
    private final ArrangementReservationRepository arrangementReservationRepository;
    /** 约束求解器（Timefold）。求解失败/超时时自动降级为贪心编排 */
    private final ScheduleOptimizer scheduleOptimizer;
    /**
     * 独立校验器（自检的「裁判」）。
     *
     * <p>编排是 NP 难问题，没有外部标准答案——所以必须自己怀疑自己：本类在落库之后，
     * 用一套<b>与求解器无关的独立实现</b>再查一遍真实赛程表。</p>
     */
    private final ScheduleVerifier scheduleVerifier;
    /**
     * 理论下界评估器：没有最优解可比对时，用它回答「还剩多少改进空间」。
     * 与校验器（判对错）正交——一个判「能不能用」，一个判「还有多好」。
     */
    private final LowerBoundEstimator lowerBoundEstimator;
    /** 大邻域搜索精修：破坏一块（年级/窗口/最忙运动员）→ 只重建这一块 → 只接受更好的 */
    private final LnsImprover lnsImprover;
    /**
     * 遗传算法：种群 + 交叉 + 变异，在算法组合层出解后做一次全局种群探索。
     * 与「多起点波次」的区别是解之间会繁殖——好的落位模式被交叉重组，而不是各自为战。
     */
    private final GeneticAlgorithm geneticAlgorithm;
    /**
     * 多邻域模拟退火：六种邻域移动 + UCB1 自适应切换 + SA 接受准则，
     * 在 GA/LNS 之后以「单步移动 + 快速评分」的粒度持续扰动残余冲突。
     */
    private final MultiNeighborhoodAnnealer mnsaAnnealer;
    /** 自适应大邻域搜索：破坏-修复循环 + UCB1 双老虎机，四种破坏算子按实际收益自适应 */
    private final AlnsImprover alnsImprover;
    /** Fix-and-Optimize：冻结解的大部分，只对兼项冲突连通分量切片做小预算精确重排 */
    private final FixAndOptimizer fixAndOptimizer;
    /** 协作中心：编排/调整落库后广播版本号，让「开着同一页面的他人」尽早发现改动、冲突提前暴露 */
    private final ScheduleCollaborationService collaborationService;
    /**
     * 规则模式编排器（U39/B36）：三级求解梯度的最低层。
     *
     * <p>竞品（豪杰/索美）的「配置参数 → 生成结果」规则引擎的精确实现——确定性 first-fit、
     * 毫秒级、可复现。用户在前端可切换「规则模式 / 优化模式」：规则模式跳过求解器直接出方案；
     * 优化模式保持既有 Timefold+GA+LNS 全链路不变。</p>
     */
    private final RuleBasedScheduler ruleBasedScheduler;
    /** 操作审计（M5 修复：赛程编排高层操作此前无审计，破坏性操作 clear 无留痕） */
    private final AuditService auditService;
    /** 行政时间保护：GLOBAL 避让时段切分时间窗、TEACHER 个人时段按项目阻挡（对编排率先影响） */
    private final AdminTimeProtectionService protectionService;
    /**
     * 组次错开消解：精修链挪不动项目时间时，改换运动员在项目内的组次顺序来错开兼项冲突。
     *
     * <p>与上面几个依赖正交——它<b>只改编排表的 heat 字段，绝不动赛程表</b>，
     * 因此不会与「多趟放置择优」互相污染：赛程在它之前就已定型。</p>
     */
    private final HeatStaggerService heatStaggerService;

    private final ScheduleBuildComponent buildComponent;
    /** 准备阶段（原 autoSchedule 内 190 行内联逻辑）——只算「排布之前必须先定下来的东西」 */
    private final AutoSchedulePreparer preparer;

    private final ScheduleSelfCheckComponent selfCheckComponent;
    private final ScheduleSolveComponent solveComponent;
    private final SchedulePlacementComponent placementComponent;
    /** 多趟放置择优（含真实冲突精修）——autoSchedule 只负责编排相位，搜索细节由它负责 */
    private final MultiStartPlacementRunner placementRunner;
    private final ScheduleQueryExportComponent queryExportComponent;

    /** LNS 轮数（0 = 关闭）。每轮都是「破坏-重建」，轮数越多越可能跳出现有局部最优 */
    @Value("${sports.schedule.lns-rounds:2}")
    private int lnsRounds;
    /** LNS 每轮时间预算（毫秒） */
    @Value("${sports.schedule.lns-round-millis:700}")
    private long lnsRoundMillis;

    /** 遗传算法种群大小（&lt;2 = 关闭 GA） */
    @Value("${sports.schedule.ga-population:6}")
    private int gaPopulation;
    /** 遗传算法代数 */
    @Value("${sports.schedule.ga-generations:2}")
    private int gaGenerations;
    /** 遗传算法变异概率（0..1） */
    @Value("${sports.schedule.ga-mutation-rate:0.15}")
    private double gaMutationRate;
    /** 遗传算法初始种群里每个「多样化个体」的求解时间预算（毫秒） */
    @Value("${sports.schedule.ga-individual-millis:300}")
    private long gaIndividualMillis;

    /** 多邻域模拟退火步数（0 = 关闭）。步数越多探索越充分，代价只是快速评分（不跑求解器）。
     *  2026-09-27：60 → 120——用户反馈「残留硬冲突/分配质量不佳」，退火步数是最便宜的探索增量。 */
    @Value("${sports.schedule.mnsa-iterations:120}")
    private int mnsaIterations;

    /** ALNS 破坏-修复轮数（0 = 关闭）。破坏/修复算子由 UCB1 老虎机自适应选择。
     *  2026-09-27：4 → 8——冲突簇破坏算子需要更多轮次才能吃透兼项密集的实例。 */
    @Value("${sports.schedule.alns-rounds:8}")
    private int alnsRounds;

    /** Fix-and-Optimize 最大重排轮数（0 = 关闭）。每轮放开至多 10 个冲突切片、
     *  预算逐轮翻倍（封顶 ×4），切片全部消除或整轮无接受即停。 */
    @Value("${sports.schedule.fixopt-rounds:2}")
    private int fixoptRounds;
    /** Fix-and-Optimize 每个切片的精确重排时间预算（毫秒） */
    @Value("${sports.schedule.fixopt-slice-millis:600}")
    private long fixoptSliceMillis;

    /**
     * 单项目时长缩放下限比例：再挤也不该把一个大项压到不足真实用时的 35%
     *
     * <p>无限轮模式的安全上限与收敛阈值已随多趟放置一并迁入
     * {@link MultiStartPlacementRunner}（{@code UNLIMITED_PASS_CAP} / {@code UNLIMITED_CONVERGE_STALE}）——
     * 它们是「搜索」这一职责的内部口径，放在使用方同处才不会被误改。</p>
     */

    // ==================== 自动编排 ====================

    /**
     * 自动编排赛程。
     *
     * @param override 临时覆盖参数（不落库），可含 startDate / days / gradeOrder /
     *                 trackSlots / fieldSlots / eventOrder / fieldGroups /
     *                 defaultDurationMinutes / defaultIntervalMinutes / venues
     */
    public Map<String, Object> autoSchedule(Map<String, Object> override) {
        // 准备阶段（配置合并 / 场地与并发位 / 单元与时长 / 时间窗 / 行政保护 / 可行性预检 / 并行分组）
        // 已独立为 AutoSchedulePreparer——autoSchedule 只保留「相位编排」，不再内联 190 行准备逻辑。
        AutoScheduleContext ctx = preparer.prepare(override);
        ScheduleProgressTracker.mark("准备", 5, "合并编排配置与系统规则");
        log.info("编排准备完成: {}", AutoSchedulePreparer.describe(ctx));

        // 相位契约：下列本地量是「准备阶段产出、后续相位只读」。新增准备项请加在 AutoScheduleContext。
        Map<String, Object> cfg = ctx.cfg;
        List<Unit> units = ctx.units;
        List<Window> windows = ctx.windows;
        int trackSlots = ctx.trackSlots;
        int fieldSlots = ctx.fieldSlots;
        int defaultInterval = ctx.defaultInterval;
        int minInterval = ctx.minInterval;
        double compressionWarnRatio = ctx.compressionWarnRatio;
        int unitInterval = ctx.unitInterval;
        Map<String, String> codeToName = ctx.codeToName;
        Map<String, Integer> codeToParallelMax = ctx.codeToParallelMax;
        List<Long> eventOrder = ctx.eventOrder;
        Map<Long, String> event2Group = ctx.event2Group;
        boolean minimizeDays = ctx.minimizeDays;
        int estimatedDays = ctx.estimatedDays;
        Map<Long, List<int[]>> eventBlocked = ctx.eventBlocked;
        Map<String, Object> feasibility = ctx.feasibility;
        Pool trackPool = ctx.trackPool;
        Pool fieldPool = ctx.fieldPool;
        Map<String, Pool> dedicatedPools = ctx.dedicatedPools;
        String mainVenue = ctx.mainVenue;
        String mainVenueCode = ctx.mainVenueCode;
        List<String> fieldVenues = ctx.fieldVenues;
        Set<String> fieldVenueCodes = ctx.fieldVenueCodes;
        List<String> venueWarnings = ctx.venueWarnings;

        // ===== 规则模式 / 优化模式分发（U39/B36：三级求解梯度的最低层——竞品规则引擎） =====
        // RULE（规则模式）：确定性 first-fit 规则编排（蛇形分组 + 固定分道 + 时间栅格 first-fit），
        //                   毫秒级、可复现、可解释；跳过求解器/GA/LNS，其余（自检/下界/协作/告警）全链路共用。
        // OPTIMIZE（优化模式，缺省）：既有行为完全不变——Timefold 组合求解 + GA + LNS，
        //                   规则引擎的解作为求解器的初始解（规则是起点，优化是提升）。
        RuleScheduleConfig ruleConfig = RuleScheduleConfig.from(override);
        // AI 模式专属：道次款型（默认「ai」由模型输出派遣优先级）自本次编排起贯穿整条放置链，
        // 一路传到 ArrangementService.arrange → LaneAdvisorService；非 AI 模式传 null = 既有口径。
        String laneStyleRule = ruleConfig.aiMode() ? ruleConfig.aiLaneStyle() : null;

        // ===== U28/B25：约束求解（Timefold）=====
        // 在落库之前先把「谁排在哪个并发位的哪一刻、每个项目分到多少分钟」整体求出来。
        // 「求解」与「持久化」解耦的好处：求解失败或超时（solvedPlacement 为空）时零副作用回退贪心，
        // 接口在任何情况下都能给出方案。
        // 进度打点：求解是整条链路最耗时的一段（构造启发式 + 算法组合波次），
        // 前端据此能区分「在算」还是「卡住了」。
        ScheduleProgressTracker.mark("求解", 35, "约束求解：构造启发式 + 算法组合波次（含 AI 建议）");
        Map<Unit, Placement> solvedPlacement = new IdentityHashMap<>();
        int[] solverStat = {0, 0};   // {采用求解结果的项目数, 求解后的残余兼项冲突数}
        Map<String, Object> portfolioInfo = new LinkedHashMap<>();   // 算法选择的可观测信息
        // 三态：rule=规则模式；ai=AI 模式（求解链 + AI 派遣 + 推理时自对抗）；optimize=优化模式（既有行为）
        portfolioInfo.put("mode", ruleConfig.aiMode() ? RuleScheduleConfig.MODE_AI
                : (ruleConfig.ruleMode() ? RuleScheduleConfig.MODE_RULE : RuleScheduleConfig.MODE_OPTIMIZE));
        // days==-1（尽可能减少）时放大跨天惩罚。求解器由 Timefold 反射实例化、
        // 不在 Spring 容器里，拿不到请求级配置 → 走静态桥（同 RuleInjectionHolder 模式）。
        // 必须 finally 复位：静态状态会跨请求存活，忘了复位会污染后续所有编排。
        ScheduleConstraintProvider.setMinimizeDaysMode(minimizeDays);
        try {
            if (ruleConfig.ruleMode()) {
                solveComponent.fillSolvedFromRules(units, trackPool, fieldPool, dedicatedPools, mainVenueCode,
                        fieldVenueCodes, codeToName, codeToParallelMax, trackSlots, fieldSlots,
                        windows, event2Group, unitInterval, ruleConfig, solvedPlacement, portfolioInfo);
            } else {
                solveComponent.fillSolvedFromSolver(units, trackPool, fieldPool, dedicatedPools, mainVenueCode,
                        fieldVenueCodes, codeToName, codeToParallelMax, trackSlots, fieldSlots,
                        windows, event2Group, unitInterval, ruleConfig, solvedPlacement, solverStat, portfolioInfo);
            }
        } finally {
            ScheduleConstraintProvider.setMinimizeDaysMode(false);
        }
        if (solverStat[0] > 0) {
            log.info("约束求解: 采用 {} 个项目的位置与时长，求解后残余兼项冲突 {} 处",
                    solverStat[0], solverStat[1]);
        }
        ScheduleProgressTracker.mark("精修", 65, "精修链：GA / LNS / MNSA / ALNS / Fix-opt");

        // U22/B19：先算好「确实有已审核报名的项目」，供下面区分两种「0 参与」：
        //   ① 项目整体无报名（数据的真问题，值得告警）；
        //   ② event.gradeGroup 缺失时按「年级 × 项目」笛卡尔积展开出的跨年级空单元
        //      （项目本就不该在该年级进行，属正常，静默跳过，否则几十条噪声淹没真正的业务告警）。
        // U22/B19：eventsWithRegs 直接由已构建的 units 推导——estimateDurations 阶段已算出每个单元
        // 的真实 participants，无需再遍历全部项目并逐项目查一次 countParticipants（N+1 冗余查询）。
        Set<Long> eventsWithRegs = units.stream()
                .filter(u -> u.participants > 0)
                .map(u -> u.event.getId())
                .collect(Collectors.toSet());

        // ===== U33/B30：兼项冲突规避——多策略自适应重试（0 轮=无限轮直至收敛）=====
        // 放置顺序会显著影响兼项冲突总数：先排「参与人数多 / 兼项度高」的项目，能让它们先占住
        // 无冲突时段，后续项目据此避让（busy 记账按放置顺序累积），从而把残余兼项冲突压到更低。
        // 这里跑多种排序策略，每趟独立 deleteAllSchedules + 重新放置（saveSchedule 即时落库），
        // 保留「残余冲突最少」的那一趟作为最终结果；若某趟已压到 0 则提前结束——
        // 不要傻傻地跑满固定次数，能归零就归零，归不了零才在最后如实告警。
        // conflictAvoidancePasses：>0 跑这么多趟（上限 64）；=0 则无限轮——持续用不同随机顺序
        // 重试，直到「连续 UNLIMITED_CONVERGE_STALE 趟残余冲突都不再下降」判定收敛，或安全上限封顶，
        // 目标是把兼项冲突压到该排序启发式下的最低。
        // 放置趟数（兼项冲突规避的多策略重试）：以「最大尝试次数」(max_attempts) 为准，
        // 0 = 无限轮（持续用不同随机顺序收敛到最优，内置 UNLIMITED_PASS_CAP 安全上限）。
        // 未配置 max_attempts（老数据 / 旧客户端）时回退 conflictAvoidancePasses（默认 4）。
        Object rawMa = cfg.get("max_attempts");
        int requestedPasses;
        if (rawMa == null) {
            requestedPasses = intVal(cfg.get("conflictAvoidancePasses"), 4);
        } else {
            requestedPasses = intVal(rawMa, 0);   // 0 = 无限轮
        }
        boolean unlimited = requestedPasses <= 0;
        int hardCap = unlimited ? MultiStartPlacementRunner.UNLIMITED_PASS_CAP
                : Math.min(64, Math.max(1, requestedPasses));
        int convergeStale = MultiStartPlacementRunner.UNLIMITED_CONVERGE_STALE;

        // 多趟择优 + 真实冲突精修整段交给 MultiStartPlacementRunner（含「评估口径 ≡ 交付口径」的口径修复）
        MultiStartPlacementRunner.SearchOutcome outcome = placementRunner.search(
                new MultiStartPlacementRunner.PassEnv(units, windows, trackSlots, fieldSlots, mainVenue, fieldVenues,
                        mainVenueCode, fieldVenueCodes, codeToName, codeToParallelMax, event2Group,
                        defaultInterval, minInterval, compressionWarnRatio, solvedPlacement, eventsWithRegs,
                        eventBlocked, laneStyleRule),
                hardCap, unlimited, convergeStale);

        PlacementPassResult best = outcome.best();
        String winningStrategy = outcome.winningStrategy();
        int passesRun = outcome.passesRun();
        portfolioInfo.put("realConflictRefine", outcome.realRefineInfo());
        List<EventSchedule> saved = best.saved;
        List<String> warnings = new ArrayList<>(venueWarnings);
        warnings.addAll(best.warnings);
        int[] conflictStat = best.conflictStat;
        int autoArrangeOk = best.autoArrangeOk;
        List<String> autoArrangeFails = best.autoArrangeFails;
        portfolioInfo.put("conflictAvoidancePasses", passesRun);
        portfolioInfo.put("winningStrategy", winningStrategy);
        portfolioInfo.put("unlimitedMode", unlimited);
        // 收敛判据（连续 N 趟残余冲突不再下降）是搜索内部的量，由 runner 随结论一并给出——
        // 这里不再自己持有 stale 计数，避免同一口径存在两个副本。
        portfolioInfo.put("converged", outcome.converged());

        log.info("赛程自动编排完成: {}个单元, {}天, 径赛{}位并发, 田赛{}位并发, 田赛分组{}组",
                saved.size(), windows.stream().mapToInt(w -> w.day).max().orElse(0),
                trackSlots, fieldSlots, event2Group.values().stream().distinct().count());
        log.info("兼项冲突规避: 零冲突放置 {} 个单元, 残余冲突 {} 条（缓冲 {} 分钟）",
                conflictStat[0], conflictStat[1], ConflictService.CONFLICT_BUFFER_MIN);

        // B01/U01/B17：自动编排重建了整张赛程表，需把「已二次编排」的决赛条目补回来。
        // 否则在二次编排之后重跑自动编排，径赛决赛条目会被整体抹掉——
        // 编排表/道次表/秩序册里仍有决赛、赛程表却没有，多出口数据不一致。
        int restoredFinals = 0;
        try {
            restoredFinals = arrangementService.restoreFinalScheduleRows();
        } catch (Exception ex) {
            warnings.add("决赛赛程条目补回失败：" + ex.getMessage() + "（请检查赛程表与道次表是否一致）");
            log.warn("补回决赛赛程条目异常", ex);
        }

        // ===== 组次维度错开（微调算法的最后一招）=====
        // 精修链（GA/LNS/MNSA/ALNS/Fix-opt）都在「挪项目时间」这一维度上找改进，
        // 受「同并发位不重叠 + 组次必须连续」约束，总有一些顽固冲突挪不动。
        // 这一招换维度：不碰任何项目的时间窗，只改换运动员在项目内的组次顺序，
        // 让两场错开 ≥ 缓冲——这是唯一不动时段容量的自由度。
        // 放在精修之后：先让重排手段用尽，再用组次错开收尾（反过来做会浪费重排机会）。
        Map<String, Object> heatStagger = null;
        try {
            boolean allowHeatStagger = !Boolean.FALSE.equals(cfg.get("heatStagger"));
            if (allowHeatStagger) {
                heatStagger = heatStaggerService.resolve(ConflictService.CONFLICT_BUFFER_MIN);
                Object resolved = heatStagger == null ? null : heatStagger.get("resolved");
                if (resolved instanceof Number n && n.intValue() > 0) {
                    log.info("组次错开: 换组 {} 人次以消解兼项冲突（项目时间窗未改动）", n.intValue());
                }
            } else {
                heatStagger = Map.of("resolved", 0, "disabled", true,
                        "note", "本次编排显式关闭了组次错开（heatStagger=false）");
            }
        } catch (Exception ex) {
            // 组次错开是「锦上添花」而非交付前提：失败不能拖垮整次编排，如实告警即可
            log.warn("组次错开消解失败（不影响已生成的赛程）: {}", ex.getMessage());
            heatStagger = Map.of("resolved", 0, "error", String.valueOf(ex.getMessage()));
        }

        // B06/U05：赛程生成后做兼项冲突检测。
        // 冲突清单本身可能上百条，逐条塞进 warnings 会把 warnings 变成噪声、现场反而看不见
        // （旧实现一次编排产生 552 条告警）。这里改为「一条汇总告警 + 完整清单走 conflicts 字段」。
        List<Map<String, Object>> conflicts = conflictService.detectConflicts();
        int severeConflicts = 0;
        for (Map<String, Object> c : conflicts) {
            if (ConflictService.SEVERITY_BLOCKER.equals(c.get("severity"))) severeConflicts++;
        }
        if (!conflicts.isEmpty()) {
            Map<String, Object> worst = conflicts.get(0);
            warnings.add(String.format("兼项冲突告警：共 %d 处（严重 %d 处），例如运动员「%s」在 %s 与 %s 时间冲突；"
                            + "完整清单见 conflicts 字段或 GET /api/arrange/conflicts/export",
                    conflicts.size(), severeConflicts, worst.get("athleteName"),
                    worst.get("windowA"), worst.get("windowB")));
            // 教师已设定出场顺序（eventOrder）时，残余兼项冲突很可能是「顺序锁死」导致无法避让，
            // 明确提示：建议删掉冲突学生项目或调整其编排顺序。
            if (eventOrder != null && !eventOrder.isEmpty() && severeConflicts > 0) {
                warnings.add(String.format("已按教师设定顺序编排，仍余 %d 处严重兼项冲突：可在班主任报名处删除冲突学生的某项目，"
                        + "或放宽教师顺序设定后重排。", severeConflicts));
            }
        }

        // 占道冲突告警：真实径赛 与「占道但用田赛法」的项目都实体占用跑道，彼此时间不得重叠，否则跑道被同时占用需错开。
        placementComponent.warnTrackOccupancy(warnings, saved);

        // U24/B21：放置完成后统计「真正落地的时长 vs 真实估算」——此时 u.duration 已含
        // 容量等比缩放与就剩余空间缩短两部分，报告因而与赛程表逐行对得上。
        List<Map<String, Object>> compressionReport = ScheduleAnalysisMath.compressionReport(units);

        // U27/B24：统计「有报名却没排进去」的单元（按池分）。容量告警必须把这件事一并说清——
        // 只说「已压缩到 55.9%」而不同时说明「仍 N 个排不下」会让人误以为压缩后都排下了。
        Set<String> placedKeys = new HashSet<>();
        for (EventSchedule s : saved) {
            if (s.getEvent() != null) {
                placedKeys.add(s.getEvent().getId() + "|" + (s.getGrade() == null ? "" : s.getGrade()));
            }
        }
        int unplacedTrack = 0;
        int unplacedField = 0;
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            if (placedKeys.contains(u.event.getId() + "|" + (u.grade == null ? "" : u.grade))) continue;
            if (u.track) unplacedTrack++;
            else unplacedField++;
        }

        // B05/U06 + U24/B21：容量告警给出**可执行结论**——缺口多少、至少需要几位并发、已等比压缩到多少。
        // 只写「缺口 699 分钟」现场无法落地；写「田赛并发位需 ≥3（当前 2）」才能立刻照做。
        if (!Boolean.TRUE.equals(feasibility.get("feasible"))) {
            for (String label : List.of("track", "field")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) feasibility.get(label);
                if (p == null || Boolean.TRUE.equals(p.get("feasible"))) continue;
                int curSlots = "track".equals(label) ? trackSlots : fieldSlots;
                Object scale = p.get("scalePercent");
                int unplaced = "track".equals(label) ? unplacedTrack : unplacedField;
                warnings.add(String.format("时间窗容量不足（%s）：需求 %d 分钟，时段×%d 位并发仅能提供 %d 分钟，缺口 %d 分钟；"
                                + "%s，仍有 %d 个项目未能排入；建议把%s并发位增至 ≥%d 位（或增加比赛天数/时段、削减项目规模）。"
                                + "压缩明细见 compressionReport",
                        p.get("label"), p.get("requiredMinutes"), curSlots, p.get("supplyMinutes"),
                        p.get("deficitMinutes"),
                        scale == null ? "已按真实用时等比压缩" : "已按真实用时等比压缩至约 " + scale + "%",
                        unplaced, p.get("label"), p.get("requiredSlots")));
            }
        }
        if (!compressionReport.isEmpty()) {
            // U24/B21：压缩幅度超过 compressionWarnRatio 的算「严重压缩」，单独点出来
            int severe = 0;
            for (Map<String, Object> c : compressionReport) {
                if (dblVal(c.get("ratioPercent"), 100) * compressionWarnRatio <= 100) severe++;
            }
            Map<String, Object> worst = compressionReport.get(0);
            warnings.add(String.format("时长压缩告警：共 %d 个项目的时长被压缩（其中 %d 项压缩幅度超过 %.1f 倍阈值），"
                            + "压缩最重的是「%s」（%s）：预计需 %d 分钟，实给 %d 分钟（%.0f%%），缺口 %d 分钟；明细见 compressionReport",
                    compressionReport.size(), severe, compressionWarnRatio,
                    worst.get("eventName"), worst.get("grade"),
                    worst.get("requiredMinutes"), worst.get("givenMinutes"),
                    dblVal(worst.get("ratioPercent"), 0), worst.get("deficitMinutes")));
        }

        Map<String, Object> result = queryExportComponent.buildResult();
        result.put("warnings", warnings);
        // U39/B36：本次编排使用的模式（rule=规则模式 / ai=AI 模式 / optimize=优化模式），供前端展示与核对
        result.put("mode", ruleConfig.aiMode() ? RuleScheduleConfig.MODE_AI
                : (ruleConfig.ruleMode() ? RuleScheduleConfig.MODE_RULE : RuleScheduleConfig.MODE_OPTIMIZE));
        // AI 模式专属：推理时自对抗自检报告（模型来源 / 轮数 / 判别器评分 / 候选残余冲突 / 是否建议采纳）
        if (ruleConfig.aiMode()) {
            result.put("aiReport", aiSelfCheckReport(units, ruleConfig, conflictStat[1]));
        }
        // B06/U05：完整兼项冲突清单（warnings 里只放汇总，避免上百条告警淹没现场）
        result.put("conflicts", conflicts);
        // 组次错开报告：换组了几个人次、每人从第几组换到第几组、间隔改善多少
        result.put("heatStagger", heatStagger);
        // B05/U06：新增「可行性预检」与「压缩亏损明细」两个结构化字段，让现场能算清缺口
        result.put("feasibility", feasibility);
        result.put("compressionReport", compressionReport);
        result.put("configUsed", cfg);
        // 空时间限制：本次自动推算的比赛天数（0 = 显式指定天数，未启用自动推算）
        result.put("estimatedDays", estimatedDays);
        result.put("autoArrange", Map.of("ok", autoArrangeOk, "failed", autoArrangeFails.size(), "fails", autoArrangeFails));
        // B01/U01/B17：本次自动编排补回的决赛赛程条目数（0 = 无需补，赛程表已与编排一致）
        result.put("restoredFinalScheduleRows", restoredFinals);
        // U23/B20：兼项冲突规避成效——排程阶段主动避让的结果，与事后 detectConflicts 的清单互为印证
        Map<String, Object> avoidance = new LinkedHashMap<>();
        avoidance.put("conflictFreeUnits", conflictStat[0]);
        avoidance.put("residualConflicts", conflictStat[1]);
        avoidance.put("unitsPlaced", conflictStat[0] + (conflictStat[1] > 0 ? 1 : 0));
        avoidance.put("bufferMinutes", ConflictService.CONFLICT_BUFFER_MIN);
        result.put("conflictAvoidance", avoidance);
        // ===== U29/B26：独立自检（内置裁判）=====
        // 求解器与贪心都是「生产者」，它们的输出不能自己证明自己。这里用一个**独立实现**的校验器
        // 对「落库之后的真实赛程表」再查一遍：
        //   · 按**真实场地**查重叠——求解器是按「并发位（槽位）」判的，两套映射不一致时只有这里查得出；
        //   · 按**运动员**查赶场（含 15 分钟缓冲）；
        //   · 查被**静默丢弃**的项目（排错看得见，漏排要到比赛当天才发现）；
        //   · 查被压过头的时长、时间自洽性。
        // 自检失败不影响编排结果，但**绝不静默**：有阻塞级问题必须写进 warnings 让人看见。
        Map<String, Object> verification;
        try {
            verification = scheduleVerifier.verify(selfCheckComponent.collectVerifyRows(saved, event2Group),
                    selfCheckComponent.collectVerifyExpected(units), SchedulePlacementMath.dailyCapacityOf(windows)).toMap();
            int blockers = intVal(verification.get("blockerCount"), 0);
            int warnCount = intVal(verification.get("warningCount"), 0);
            if (blockers > 0) {
                warnings.add(String.format("⚠️ 自检未通过：按真实场地/运动员复核后仍有 %d 条阻塞级问题（%s）；"
                                + "完整清单见 verification.violations",
                        blockers, firstViolationBrief(verification)));
            } else if (warnCount > 0) {
                warnings.add(String.format("自检通过（无阻塞级问题），但有 %d 条告警级提示；"
                        + "完整清单见 verification.violations", warnCount));
            }
        } catch (Exception ex) {
            log.warn("赛程自检执行失败（编排结果仍可用，但请人工复核）", ex);
            verification = new LinkedHashMap<>();
            verification.put("hardOk", false);
            verification.put("error", "自检执行失败：" + ex.getMessage());
        }
        ScheduleProgressTracker.mark("自检", 88, "对抗式自检、理论下界评估与可解性诊断");
        result.put("verification", verification);
        // 算法组合调度过程（可观测）：实例特征 → 候选算法 → 胜出者
        result.put("algorithmPortfolio", portfolioInfo);

        // ===== U31/B28：理论下界评估（竞赛算法思维：从「感觉优化了」到「知道离最优多远」）=====
        // 自检回答「方案能不能用」，下界回答「还有多少改进空间」——两者正交，缺一不可。
        try {
            result.put("lowerBound",
                    selfCheckComponent.assessLowerBound(units, windows, saved, trackSlots, fieldSlots).toMap());
        } catch (Exception ex) {
            log.warn("下界评估失败（不影响编排结果）: {}", ex.getMessage());
        }

        // B16/U20：业务级成功判定——不止看 failed:0，还要看业务告警
        // （兼项冲突、严重压缩、时间窗溢出、自检未通过等 warnings 任一非空即视为未完全成功）
        boolean businessOk = warnings.isEmpty() && autoArrangeFails.isEmpty();
        result.put("businessOk", businessOk);
        ScheduleProgressTracker.mark("收尾", 97, "汇总统计与结果");
        result.put("success", businessOk);
        result.put("message", businessOk ? "编排完成，业务校验通过" : "编排已完成，但存在业务告警（见 warnings），请复核");
        // 实时协作：落库完成即广播版本号，让开着同一页面的他人尽早刷新、冲突提前暴露
        collaborationService.notify("schedule", "auto-arranged", "EventSchedule", null);
        // M5 修复：与 ArrangementController 对齐，高层赛程编排也留审计。
        // 关键：auditService.record 使用 REQUIRES_NEW，在外层 @Transactional 事务内直接调用，
        // 在 SQLite 单写者场景下会与外层事务锁冲突（SQLITE_BUSY → 外层被标 rollback-only →
        // UnexpectedRollbackException，端点返回非 success）。改为事务提交后（afterCommit）再写审计，
        // 复用 M3 SetupService 的同步模式，彻底规避锁竞争；且只有外层事务成功提交才记录审计。
        // 若当前无活动事务（如单测直接调用编排方法），则直接落审计——record 自身 REQUIRES_NEW，不依赖外层事务。
        queryExportComponent.auditAfterCommit(() -> auditService.record("SCHEDULE_AUTO", "SCHEDULE", null,
                "自动编排完成 businessOk=" + businessOk + ", 赛程条目=" + saved.size()));
        return result;
    }

    // ==================== 兼项冲突规避：多策略自适应放置 ====================


    /** AI 模式专属自检报告（整次编排的汇总口径，随编排结果一并返回给前端）。
     *
     * <p>回答三件事：① 本次是否真的按 AI 派遣款型生成道次（{@code laneStyle}）；
     * ② 推理时自对抗有没有跑起来（模型缺失/加载失败会如实报 {@code unavailable} = 已降级，
     * 绝不静默）；③ 落库后还剩多少兼项冲突。</p>
     *
     * <p>求解阶段的自对抗明细（D 分 / 候选冲突 / 择优前后对比）在
     * {@code algorithmPortfolio.aiReport} 里，此处只做汇总，不做第二次模型推理。</p>
     */
    private Map<String, Object> aiSelfCheckReport(List<Unit> units, RuleScheduleConfig cfg, int residualConflicts) {
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("laneStyle", cfg.aiLaneStyle());
        rep.put("rounds", Math.max(0, cfg.aiAdversarialRounds()));
        rep.put("units", units == null ? 0 : units.size());
        rep.put("residualConflicts", residualConflicts);
        AdversarialSchemeService adv = AdversarialSchemeService.current();
        if (adv == null) {
            rep.put("adversarial", "unavailable");
            rep.put("note", "推理时自对抗服务未接入（AI 编排组件未在容器内创建），"
                    + "本次 AI 模式仅启用 AI 派遣款型与 AI 可解性诊断");
        } else if (!adv.isAvailable()) {
            rep.put("adversarial", "unavailable");
            rep.put("note", "ONNX 模型不可用（缺失或加载失败），AI 模式已自动降级为优化模式");
        } else {
            rep.put("adversarial", "enabled");
            rep.put("modelInfo", adv.modelInfo());
            rep.put("note", "推理时自对抗已在求解阶段执行（明细见 algorithmPortfolio.aiReport），本表为整次编排的汇总口径");
        }
        return rep;
    }

    /** 两趟放置结果比较：残余冲突更少优先；其次零冲突单元更多；最后放置失败更少 */

    /**
     * 自动消解兼项冲突（一键编排的「冲突最小化」专精入口）。
     *
     * <p>在 {@link #autoSchedule} 的基础上强制「无限轮」(max_attempts=0)，把真实事后冲突数
     * （与 GET /api/arrange/conflicts 同口径）作为目标函数，持续用不同随机顺序重排并保留最优，
     * 直到真实冲突归零或收敛到该扰动下的最低——即「检测后继续尝试，直到兼项冲突没有」。</p>
     *
     * <p><b>单调保底（2026-09-27）</b>：消解的全部重排趟都以「无决赛条目」的中间态过账，
     * 决赛条目在补回后才计入冲突；若补回后的最终交付口径仍劣于入口（极端实例下重排全面劣化、
     * 或精修扰动跳不出劣化区间），则回滚到入口快照——保证「消解永不变差」：本入口返回后的
     * 兼项冲突数（total/severe）不高于调用前。同时把基线/终值/是否回滚写入
     * algorithmPortfolio.resolveConflictGuard，供前端与日志核对。</p>
     */
    public Map<String, Object> resolveConflicts(Map<String, Object> override) {
        Map<String, Object> cfg = override == null ? new LinkedHashMap<>() : new LinkedHashMap<>(override);
        cfg.put("max_attempts", 0);   // 无限轮：跑到底把真实冲突压到最低 / 归零
        // 入口基线：交付口径冲突数 + 赛程/编排快照（两表均为无外键引用的派生表，可安全整表重建）
        int[] entryConflict = conflictService.countConflicts();
        List<EventSchedule> entrySchedules = scheduleRepository.findAll();
        List<Arrangement> entryArrangements = arrangementRepository.findAll();
        Map<String, Object> result = autoSchedule(cfg);
        int[] finalConflict = conflictService.countConflicts();
        boolean worse = finalConflict[0] > entryConflict[0]
                || (finalConflict[0] == entryConflict[0] && finalConflict[1] > entryConflict[1]);
        if (worse) {
            restoreScheduleSnapshot(entrySchedules, entryArrangements);
            log.warn("兼项冲突消解结果劣于入口（total/severe {} -> {}），已回滚到消解前的赛程表与编排表",
                    Arrays.toString(entryConflict), Arrays.toString(finalConflict));
            // 回滚后 result 里的 conflicts/warnings 反映的是被放弃的重排方案，按恢复后的库重算
            result.put("conflicts", conflictService.detectConflicts());
            if (result.get("warnings") instanceof List<?> ws) {
                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) ws;
                warnings.add(String.format(
                        "兼项冲突消解未找到优于当前方案的解（冲突 %d 处，尝试后为 %d 处），已保留消解前的赛程表",
                        entryConflict[0], finalConflict[0]));
            }
        }
        if (result.get("algorithmPortfolio") instanceof Map<?, ?> pfRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pf = (Map<String, Object>) pfRaw;
            pf.put("resolveConflictGuard", Map.of(
                    "entryTotal", entryConflict[0], "entrySevere", entryConflict[1],
                    "finalTotal", finalConflict[0], "finalSevere", finalConflict[1],
                    "rolledBack", worse));
        }
        return result;
    }

    /**
     * 消解单调保底的快照恢复：整表重建赛程行与编排行（字段逐项一致，行 id 重新分配——
     * 经核查两表均无任何外键引用者，id 变化不影响业务）。
     */
    private void restoreScheduleSnapshot(List<EventSchedule> scheduleRows, List<Arrangement> arrangementRows) {
        scheduleRepository.deleteAllSchedules();
        for (EventSchedule s : scheduleRows) {
            scheduleRepository.save(EventSchedule.builder()
                    .event(s.getEvent())
                    .day(s.getDay()).scheduleDate(s.getScheduleDate()).grade(s.getGrade())
                    .timeSlot(s.getTimeSlot()).startTime(s.getStartTime()).endTime(s.getEndTime())
                    .venue(s.getVenue()).sortOrder(s.getSortOrder())
                    .durationMinutes(s.getDurationMinutes()).round(s.getRound()).remark(s.getRemark())
                    .createdAt(s.getCreatedAt() != null ? s.getCreatedAt() : LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        arrangementRepository.deleteAll();
        for (Arrangement a : arrangementRows) {
            arrangementRepository.save(Arrangement.builder()
                    .event(a.getEvent()).athlete(a.getAthlete())
                    .grade(a.getGrade()).gender(a.getGender())
                    .heat(a.getHeat()).lane(a.getLane()).position(a.getPosition()).round(a.getRound())
                    .qualified(a.getQualified()).prelimRank(a.getPrelimRank())
                    .prelimTime(a.getPrelimTime()).prelimTimeSeconds(a.getPrelimTimeSeconds())
                    .teamNo(a.getTeamNo()).isManual(a.getIsManual()).remark(a.getRemark())
                    .createdAt(a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
    }

    /**
     * 对「当前库里的赛程表」做自检——供前端在人工调整之后随时复查。
     *
     * <p>这是<b>人机对抗循环</b>的接口：编排人员拖一个项目，系统立刻告诉他这个调整破坏了什么。
     * 与 {@link #autoSchedule} 内部的自检共用同一套判定，但入口独立、不依赖任何编排参数，
     * 因此可以在任何时刻（包括纯手工编辑之后）调用。</p>
     *
     * <p>与编排期自检的差别：本入口读不到「编排意图」，因此不做「编排表里有、赛程表里没有」这类
     * 覆盖性检查——它的结论是「这份赛程表<b>自身</b>是否自洽」，而不是「是否覆盖了全部项目」。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> verifyCurrentSchedule() {
        Map<String, Object> cfg = buildComponent.mergeConfig(null);
        Map<Long, String> groupCfg = parseFieldGroups(cfg.get("fieldGroups"));
        List<EventSchedule> rows = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();
        List<ScheduleVerifier.Row> verifyRows = new ArrayList<>();
        List<ScheduleVerifier.Expected> expected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EventSchedule s : rows) {
            Event e = s.getEvent();
            if (e == null || e.getId() == null) continue;
            Set<Long> athletes = new LinkedHashSet<>();
            Map<Long, String> names = new LinkedHashMap<>();
            for (Registration reg : buildComponent.approvedRegs(e.getId(), s.getGrade())) {
                if (reg.getAthlete() == null || reg.getAthlete().getId() == null) continue;
                Long aid = reg.getAthlete().getId();
                athletes.add(aid);
                names.put(aid, reg.getAthlete().getName());
            }
            int start = parseMinute(s.getStartTime());
            int end = parseMinute(s.getEndTime());
            String group = groupCfg.get(e.getId());
            verifyRows.add(new ScheduleVerifier.Row(e.getId(), e.getName(), s.getGrade(),
                    s.getDay() == null ? 0 : s.getDay(), s.getScheduleDate(), s.getTimeSlot(),
                    start, end, s.getVenue(),
                    group == null ? null : group + "@" + (s.getGrade() == null ? "" : s.getGrade()),
                    athletes, names));
            String key = ScheduleVerifier.keyOf(e.getId(), s.getGrade());
            if (seen.add(key)) {
                // rawDuration 传 0 = 「真实用时未知」：本入口读不到编排意图，
                // 因此跳过「压缩比 / 时长下限」两项检查，只做自洽性校验。
                expected.add(new ScheduleVerifier.Expected(key, e.getName(), s.getGrade(), 0));
            }
        }
        int dailyCapacity = 0;
        try {
            dailyCapacity = SchedulePlacementMath.dailyCapacityOf(buildComponent.buildWindows(cfg));
        } catch (Exception ex) {
            log.warn("自检读取时段配置失败，将跳过容量利用率计算: {}", ex.getMessage());
        }
        Map<String, Object> out = scheduleVerifier.verify(verifyRows, expected, dailyCapacity).toMap();
        out.put("basis", "current-table-only");
        out.put("note", "本入口只校验「当前赛程表自身是否自洽」（不检查项目覆盖性与压缩比）；"
                + "完整校验请调用 POST /api/schedule/auto");
        return out;
    }

    // ==================== 放置（并发池） ====================


    /** 生成某并发池的全部候选位置：槽位 × 时段窗口 × 起点档位（按 unitInterval 步进） */

    /** 项目时长下限：再挤也不该把一个大项压到不足真实用时的 35%（那已不是「压缩」而是「不可执行」） */

    /** 候选时长档位（降序）：从真实用时按 5% 递减到下限，供求解器逐项目权衡「保真」与「排得下」 */

    /** 参赛运动员 id 升序数组（升序是为了让兼项判定能用双指针求交） */

    /** 求解结果的残余兼项冲突数（与检测端同口径）；与 solverStat 一起进日志，便于核对「到底规避掉多少」 */

    /** 两个单元是否有共同运动员（小集合驱动，避免全量遍历） */



    /** 单元在所属池内的游标：多单元共用同池时依次占用不同槽位 */





    /** 性别原值 → 中文标签（用于告警文案） */

    // ==================== 赛程单元构建 ====================


    /** 按自定义顺序（eventOrder）重排项目；未列入者按原 sortOrder 保持相对顺序（稳定排序） */







    // 时间窗构建与自动天数模板已随准备阶段迁入 AutoSchedulePreparer。

    // ==================== 查询 / 手动调整 / 清空 ====================

    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        return queryExportComponent.list();
    }

    public Map<String, Object> save(List<Map<String, Object>> items) {
        return queryExportComponent.save(items);
    }

    public void clear() {
        queryExportComponent.clear();
    }

    public void export(HttpServletResponse response) {
        queryExportComponent.export(response);
    }

    // ==================== 配置合并（mergeConfig 已迁入 ScheduleBuildComponent） ====================

    // ==================== U29/B26：自检数据装配 ====================




}
