package com.sports.service;

import com.sports.common.Grades;
import com.sports.collab.ScheduleCollaborationService;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.entity.Registration;
import com.sports.entity.Venue;
import com.sports.repository.EventRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.repository.RegistrationRepository;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.VenueRepository;
import com.sports.schedule.opt.Placement;
import com.sports.schedule.opt.ScheduleOptimizer;
import com.sports.schedule.analysis.LowerBoundEstimator;
import com.sports.schedule.opt.ga.GeneticAlgorithm;
import com.sports.schedule.opt.lns.LnsImprover;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import com.sports.schedule.rule.RuleBasedScheduler;
import com.sports.schedule.rule.RuleScheduleConfig;
import com.sports.schedule.rule.RuleUnit;
import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.opt.SchedulePlan;
import com.sports.schedule.opt.ScheduleUnit;
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
import com.sports.schedule.core.*;

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
                            ScheduleOptimizer scheduleOptimizer,
                            ScheduleVerifier scheduleVerifier,
                            LowerBoundEstimator lowerBoundEstimator,
                            LnsImprover lnsImprover,
                            GeneticAlgorithm geneticAlgorithm,
                            ScheduleCollaborationService collaborationService,
                            RuleBasedScheduler ruleBasedScheduler,
                            AuditService auditService) {
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.arrangementRepository = arrangementRepository;
        this.arrangementService = arrangementService;
        this.systemService = systemService;
        this.conflictService = conflictService;
        this.venueRepository = venueRepository;
        this.scheduleOptimizer = scheduleOptimizer;
        this.scheduleVerifier = scheduleVerifier;
        this.lowerBoundEstimator = lowerBoundEstimator;
        this.lnsImprover = lnsImprover;
        this.geneticAlgorithm = geneticAlgorithm;
        this.collaborationService = collaborationService;
        this.ruleBasedScheduler = ruleBasedScheduler;
        this.auditService = auditService;
        this.buildComponent = new ScheduleBuildComponent(eventRepository, registrationRepository, systemService);
        this.selfCheckComponent = new ScheduleSelfCheckComponent(lowerBoundEstimator, buildComponent);
        this.solveComponent = new ScheduleSolveComponent(scheduleOptimizer, ruleBasedScheduler, geneticAlgorithm, lnsImprover, buildComponent,
                lnsRounds, lnsRoundMillis, gaPopulation, gaGenerations, gaMutationRate, gaIndividualMillis);
        this.placementComponent = new SchedulePlacementComponent(arrangementService, arrangementRepository, scheduleRepository, buildComponent);
    }


    private final EventScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ArrangementService arrangementService;
    private final SystemService systemService;
    private final ConflictService conflictService;
    private final VenueRepository venueRepository;
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

    private final ScheduleBuildComponent buildComponent;

    private final ScheduleSelfCheckComponent selfCheckComponent;
    private final ScheduleSolveComponent solveComponent;
    private final SchedulePlacementComponent placementComponent;

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

    /** 单项目时长缩放下限比例：再挤也不该把一个大项压到不足真实用时的 35% */

    // ==================== 自动编排 ====================

    /**
     * 自动编排赛程。
     *
     * @param override 临时覆盖参数（不落库），可含 startDate / days / gradeOrder /
     *                 trackSlots / fieldSlots / eventOrder / fieldGroups /
     *                 defaultDurationMinutes / defaultIntervalMinutes / venues
     */
    public Map<String, Object> autoSchedule(Map<String, Object> override) {
        Map<String, Object> cfg = buildComponent.mergeConfig(override);

        List<String> gradeOrder = strList(cfg.get("gradeOrder"));
        // 并发位数：1 = 串行（同一时刻只进行 1 个项目）；n = 同时进行 n 个项目
        int trackSlots = Math.max(1, intVal(cfg.get("trackSlots"), 1));
        int fieldSlots = Math.max(1, intVal(cfg.get("fieldSlots"), 2));
        int defaultDuration = intVal(cfg.get("defaultDurationMinutes"), 30);
        int defaultInterval = intVal(cfg.get("defaultIntervalMinutes"), 5);
        int heatMinutes = intVal(cfg.get("heatMinutes"), 6);
        int fieldPerAthlete = intVal(cfg.get("fieldPerAthleteMinutes"), 3);
        // B05/U07：项目间隔下限（不小于此值，避免项目紧贴导致现场不可行）+ 压缩告警阈值。
        // 当某项目被压缩到「预计用时的 1/ratio 以下」时视为严重压缩，写入 warnings 告警。
        int minInterval = Math.max(1, intVal(cfg.get("minIntervalMinutes"), 5));
        double compressionWarnRatio = dblVal(cfg.get("compressionWarnRatio"), 1.5);
        // 场地来源：优先使用数据库「场地表」(含 parallelMax 并行上限)；为空则回退配置 JSON 的 venues
        List<Venue> dbVenues = (venueRepository != null)
                ? venueRepository.findByEnabledTrueOrderBySortOrderAsc() : List.of();
        boolean useDbVenues = !dbVenues.isEmpty();
        List<Map<String, Object>> venueList;
        if (useDbVenues) {
            venueList = new ArrayList<>();
            for (Venue v : dbVenues) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", v.getName());
                m.put("code", v.getCode());
                m.put("type", v.getType());
                m.put("parallelMax", v.getParallelMax());
                venueList.add(m);
            }
        } else {
            venueList = venueListOf(cfg.get("venues"));
        }
        List<String> venues = venueNames(venueList);
        Map<String, String> codeToName = new LinkedHashMap<>();
        Map<String, Integer> codeToParallelMax = new LinkedHashMap<>();
        for (Map<String, Object> v : venueList) {
            Object code = v.get("code");
            Object name = v.get("name");
            Object pm = v.get("parallelMax");
            if (code != null && !String.valueOf(code).isBlank()) {
                String c = String.valueOf(code).trim();
                codeToName.put(c, name != null ? String.valueOf(name).trim() : c);
                int max = (pm instanceof Number n) ? n.intValue() : 1;
                codeToParallelMax.put(c, Math.max(1, max));
            }
        }
        // DB 场地表存在时，主场地/首个田赛场地的 parallelMax 即其池并发上限
        // Bug1 修复：按场地类型定位——主场地=首个 type=track；田赛池=type=field；
        // pool/other 类型（如游泳馆）不进田赛轮询池，只能被项目 defaultVenueCode 显式绑定
        List<Map<String, Object>> trackTypeVenues = venuesOfType(venueList, "track");
        List<Map<String, Object>> fieldTypeVenues = venuesOfType(venueList, "field");
        boolean hasType = !trackTypeVenues.isEmpty() || !fieldTypeVenues.isEmpty();
        if (useDbVenues && hasType) {
            if (!trackTypeVenues.isEmpty()) {
                Integer mainPm = codeToParallelMax.get(codeOf(trackTypeVenues.get(0)));
                if (mainPm != null) trackSlots = Math.max(1, mainPm);
            }
            if (!fieldTypeVenues.isEmpty()) {
                Integer fPm = codeToParallelMax.get(codeOf(fieldTypeVenues.get(0)));
                if (fPm != null) fieldSlots = Math.max(1, fPm);
            }
        }
        List<Long> eventOrder = longList(cfg.get("eventOrder"));
        Map<Long, String> event2Group = parseFieldGroups(cfg.get("fieldGroups"));

        // 时间窗：按 (天, 时段) 顺序铺开
        List<Window> windows = buildComponent.buildWindows(cfg);
        if (windows.isEmpty()) {
            throw new RuntimeException("运动会日程未配置可用时段，请先在「系统设置 → 运动会日程」中配置日期与时段");
        }

        List<Unit> units = buildComponent.buildUnits(gradeOrder, eventOrder);
        // B05/U06 根因修复：venue.parallelMax 是「该场地同一时刻能并行几个**项目**」，
        // 与「一个项目内同时下场几个**运动员**」是两个正交的量。
        // 旧实现把 parallelMax 当成项目内并发人数传下去，主跑道 parallelMax=1 时
        // 会把 8 条道的 100 米压成「每组 1 人」→ ceil(24/1)×6 分 = 144 分钟虚假需求，
        // 再被 maxDurationMinutes 硬压到 20 分钟，现场时间估算彻底失真（B05 的 144→20）。
        // 正确的约束路径：parallelMax → 并发池槽位数（trackSlots/fieldSlots/专用池，已在上面处理），
        // 项目内并发只由项目自身配置（concurrency > groupSize > laneCount > defaultLanes）决定。
        buildComponent.estimateDurations(units, heatMinutes, fieldPerAthlete, defaultDuration);

        // B05/U06：时间窗容量可行性预检 + 压缩亏损量化。
        // 只告警不量化，现场无法判断「到底差多少分钟、差在哪个项目」。
        // 这里在放置之前先算清楚：本项目类别真正需要多少分钟、时段总共能提供多少分钟、
        // 缺口多少，并把每一个被压缩的项目列成表（需求/实给/压缩比/缺口）。
        int unitInterval = Math.max(defaultInterval, minInterval);
        Map<String, Object> feasibility = ScheduleAnalysisMath.assessFeasibility(units, windows, trackSlots, fieldSlots, unitInterval);
        // U24/B21：先按容量等比适配时长，再进入放置；compressionReport 放到放置之后统计，
        // 这样「就近缩短」的那部分也如实计入（报告反映的是真正落地的时长）。
        ScheduleAnalysisMath.fitDurationsToPools(units, feasibility, unitInterval);

        // 项目自带的「并行捆绑组」（表格2 的字母列）优先于配置页分组：
        // 同字母 → 同组 → 安排在同一时段并行；为空则不受限制，由算法自动安排
        for (Unit u : units) {
            String bg = u.event.getBundleGroup();
            if (bg != null && !bg.isBlank()) {
                event2Group.put(u.event.getId(), "捆绑组" + bg.trim().toUpperCase());
            }
        }
        // 小组合作项目：归入同一「合作组」，与同年级同组项目合并到同一时段并行进行
        // （径赛/田赛皆可，含趣味；真正并行的还是各自场地池，这里只负责「同时开赛」协调）
        for (Unit u : units) {
            if (Boolean.TRUE.equals(u.event.getCooperative())) {
                event2Group.put(u.event.getId(), "合作组");
            }
        }

        // 资源池：按类别分配并发位。径赛用主场地；田赛只用 type=field 的场地（不足则复用并告警）；
        // pool/other 类型（游泳馆等）不参与田赛轮询，只能被项目 defaultVenueCode 显式绑定。
        // 兼容旧配置：场地表/配置 JSON 均无类型信息时，回退「首个=主场地、其余=田赛」的旧规则。
        String mainVenue;
        String mainVenueCode;
        List<String> fieldVenues;
        if (hasType) {
            mainVenue = trackTypeVenues.isEmpty()
                    ? (venues.isEmpty() ? "田径场" : venues.get(0))
                    : nameOf(trackTypeVenues.get(0));
            mainVenueCode = trackTypeVenues.isEmpty()
                    ? (venueList.isEmpty() ? null : codeOf(venueList.get(0)))
                    : codeOf(trackTypeVenues.get(0));
            fieldVenues = new ArrayList<>();
            for (Map<String, Object> v : fieldTypeVenues) fieldVenues.add(nameOf(v));
        } else {
            mainVenue = venues.isEmpty() ? "田径场" : venues.get(0);
            mainVenueCode = venueList.isEmpty() ? null : codeOf(venueList.get(0));
            fieldVenues = venues.size() > 1
                    ? new ArrayList<>(venues.subList(1, venues.size()))
                    : new ArrayList<>(List.of(mainVenue));
        }
        Set<String> fieldVenueCodes = new HashSet<>();
        for (Map<String, Object> v : fieldTypeVenues) {
            String c = codeOf(v);
            if (c != null) fieldVenueCodes.add(c);
        }
        Pool trackPool = new Pool("径赛", trackSlots, new ArrayList<>(List.of(mainVenue)));
        Pool fieldPool = new Pool("田赛", fieldSlots, fieldVenues);
        // 项目级指定场地时按需创建的独立并发池（key=场地编码），与主/田赛池并行
        Map<String, Pool> dedicatedPools = new LinkedHashMap<>();

        List<EventSchedule> saved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (trackPool.venueShortage) {
            warnings.add(String.format("径赛并数 %d 超过可用场地数 %d，部分槽位将复用同一场地（并数取决于场地数量，请增加场地或调小并数）",
                    trackSlots, venues.size()));
        }
        if (fieldPool.venueShortage) {
            warnings.add(String.format("田赛并数 %d 超过可用田赛场地数 %d，部分槽位将复用同一场地（并数取决于场地数量，请增加场地或调小并数）",
                    fieldSlots, fieldVenues.size()));
        }

        scheduleRepository.deleteAllSchedules();

        // ===== 规则模式 / 优化模式分发（U39/B36：三级求解梯度的最低层——竞品规则引擎） =====
        // RULE（规则模式）：确定性 first-fit 规则编排（蛇形分组 + 固定分道 + 时间栅格 first-fit），
        //                   毫秒级、可复现、可解释；跳过求解器/GA/LNS，其余（自检/下界/协作/告警）全链路共用。
        // OPTIMIZE（优化模式，缺省）：既有行为完全不变——Timefold 组合求解 + GA + LNS，
        //                   规则引擎的解作为求解器的初始解（规则是起点，优化是提升）。
        RuleScheduleConfig ruleConfig = RuleScheduleConfig.from(override);

        // ===== U28/B25：约束求解（Timefold）=====
        // 在落库之前先把「谁排在哪个并发位的哪一刻、每个项目分到多少分钟」整体求出来。
        // 「求解」与「持久化」解耦的好处：求解失败或超时（solvedPlacement 为空）时零副作用回退贪心，
        // 接口在任何情况下都能给出方案。
        Map<Unit, Placement> solvedPlacement = new IdentityHashMap<>();
        int[] solverStat = {0, 0};   // {采用求解结果的项目数, 求解后的残余兼项冲突数}
        Map<String, Object> portfolioInfo = new LinkedHashMap<>();   // 算法选择的可观测信息
        portfolioInfo.put("mode", ruleConfig.ruleMode() ? "rule" : "optimize");
        if (ruleConfig.ruleMode()) {
            solveComponent.fillSolvedFromRules(units, trackPool, fieldPool, dedicatedPools, mainVenueCode,
                    fieldVenueCodes, codeToName, codeToParallelMax, trackSlots, fieldSlots,
                    windows, event2Group, unitInterval, ruleConfig, solvedPlacement, portfolioInfo);
        } else {
            solveComponent.fillSolvedFromSolver(units, trackPool, fieldPool, dedicatedPools, mainVenueCode,
                    fieldVenueCodes, codeToName, codeToParallelMax, trackSlots, fieldSlots,
                    windows, event2Group, unitInterval, solvedPlacement, solverStat, portfolioInfo);
        }
        if (solverStat[0] > 0) {
            log.info("约束求解: 采用 {} 个项目的位置与时长，求解后残余兼项冲突 {} 处",
                    solverStat[0], solverStat[1]);
        }

        int autoArrangeOk = 0;
        List<String> autoArrangeFails = new ArrayList<>();
        int[] orderCounter = {1};
        Set<Integer> done = new HashSet<>();
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
        // U23/B20：兼项冲突规避的运行状态。
        // busy = 运动员 → 已占用时间段（绝对分钟 = 天×1440 + 当日分钟），放置时据此避让；
        // conflictStat = {零冲突放置的单元数, 残余冲突条数}，随结果返回，便于核对「到底规避掉多少」。
        Map<Long, List<int[]>> busy = new HashMap<>();
        int[] conflictStat = {0, 0};

        for (int i = 0; i < units.size(); i++) {
            if (done.contains(i)) continue;
            Unit u = units.get(i);
            if (u.participants <= 0) {
                // U22/B19：只有项目「整体无报名」才告警；跨年级空单元（本就不该在该年级进行）静默跳过
                if (!eventsWithRegs.contains(u.event.getId())) {
                    warnings.add(String.format("项目「%s」（%s）暂无有效报名，已跳过",
                            u.event.getName(), u.grade == null ? "不分年级" : u.grade));
                }
                done.add(i);
                continue;
            }

            Pool pool = buildComponent.resolvePool(u, trackPool, fieldPool, mainVenueCode,
                    fieldVenueCodes, codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots);
            // 分组：田赛或「小组合作」项目按 event2Group 分组（合作项目哪怕是径赛也并入同组并行）
            String group = event2Group.get(u.event.getId());

            if (group == null) {
                // 普通单元：占用并发池中最空闲的一个槽位
                placementComponent.placeOne(u, pool, windows, defaultInterval, minInterval, compressionWarnRatio,
                        saved, warnings, orderCounter, autoArrangeFails, busy, conflictStat, solvedPlacement);
                autoArrangeOk += u.arranged;
                done.add(i);
            } else {
                // 田赛分组：同组 + 同年级 的单元安排在同一时段并行进行
                List<Integer> batch = new ArrayList<>();
                for (int j = i; j < units.size(); j++) {
                    if (done.contains(j)) continue;
                    Unit v = units.get(j);
                    // 同组并行：同年级 + 同组；合作项目允许径赛/田赛混合（场地池各自解析，仅时间协调）
                    if (!sameGrade(v.grade, u.grade)) continue;
                    if (!group.equals(event2Group.get(v.event.getId()))) continue;
                    if (v.participants <= 0) {
                        // U22/B19：同上——只有项目整体无报名才告警，跨年级空单元静默跳过
                        if (!eventsWithRegs.contains(v.event.getId())) {
                            warnings.add(String.format("项目「%s」（%s）暂无有效报名，已跳过",
                                    v.event.getName(), v.grade == null ? "不分年级" : v.grade));
                        }
                        done.add(j);
                        continue;
                    }
                    batch.add(j);
                }
                if (batch.isEmpty()) { done.add(i); continue; }
                // Bug1 修复：组内各单元先各自解析场地池（含 defaultVenueCode 绑定的专用池），
                // 场地输出取各自池的场地名；时间协调跨池进行（同一时刻同时开赛）。
                List<Pool> unitPools = new ArrayList<>();
                for (Integer idx : batch) {
                    unitPools.add(buildComponent.resolvePool(units.get(idx), trackPool, fieldPool, mainVenueCode,
                            fieldVenueCodes, codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
                }
                placementComponent.placeBatch(batch, units, unitPools, windows, defaultInterval, minInterval, compressionWarnRatio,
                        group, saved, warnings, orderCounter, autoArrangeFails, busy, conflictStat, solvedPlacement);
                for (Integer idx : batch) {
                    autoArrangeOk += units.get(idx).arranged;
                    done.add(idx);
                }
            }
        }

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

        Map<String, Object> result = buildResult();
        result.put("warnings", warnings);
        // U39/B36：本次编排使用的模式（rule=规则模式 / optimize=优化模式），供前端展示与核对
        result.put("mode", ruleConfig.ruleMode() ? RuleScheduleConfig.MODE_RULE : RuleScheduleConfig.MODE_OPTIMIZE);
        // B06/U05：完整兼项冲突清单（warnings 里只放汇总，避免上百条告警淹没现场）
        result.put("conflicts", conflicts);
        // B05/U06：新增「可行性预检」与「压缩亏损明细」两个结构化字段，让现场能算清缺口
        result.put("feasibility", feasibility);
        result.put("compressionReport", compressionReport);
        result.put("configUsed", cfg);
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
        auditAfterCommit(() -> auditService.record("SCHEDULE_AUTO", "SCHEDULE", null,
                "自动编排完成 businessOk=" + businessOk + ", 赛程条目=" + saved.size()));
        return result;
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






    // ==================== 时间窗 ====================

    // ==================== 查询 / 手动调整 / 清空 ====================

    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        return buildResult();
    }

    /**
     * 手动保存调整后的赛程（替换全部）。
     *
     * <p>B09/U09 修复：旧实现构建 {@link EventSchedule} 时<b>完全没写 round</b>，
     * 也不读取入参里的 round——手动保存一次（哪怕只是改个开始时间），
     * 整张赛程表的轮次就被清空为 null，导出/秩序册按 {@code RoundLabelUtil.label(null)}
     * 一律显示成「直接决赛」，预赛与决赛的区分彻底丢失。
     * {@link #buildResult()} 也没把 round 输出给前端，所以前端即使原样回传也无力回天。</p>
     *
     * <p>现在按三级取值保证轮次不丢：① 入参显式 round（前端已回传）；
     * ② 保存前既有行的 round（按 项目×年级×开始时刻×场地 匹配，兼容前端只传调整过的行）；
     * ③ 兜底按 {@code event.needHeats} 推断（需预赛→preliminary，否则 final）。</p>
     */
    public Map<String, Object> save(List<Map<String, Object>> items) {
        // ① 先给「旧行」建索引：手动保存会整表替换，替换前必须先把轮次捞出来
        Map<String, String> oldRoundByKey = new HashMap<>();
        Map<Long, String> oldRoundByEvent = new HashMap<>();
        for (EventSchedule old : scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc()) {
            if (old.getEvent() == null || old.getRound() == null || old.getRound().isBlank()) continue;
            oldRoundByKey.putIfAbsent(SchedulePlacementMath.roundKey(old.getEvent().getId(), old.getGrade(),
                    old.getStartTime(), old.getVenue()), old.getRound());
            oldRoundByEvent.putIfAbsent(old.getEvent().getId(), old.getRound());
        }

        scheduleRepository.deleteAllSchedules();
        int order = 1;
        for (Map<String, Object> item : items) {
            Long eventId = item.get("eventId") != null
                    ? ((Number) item.get("eventId")).longValue() : null;
            if (eventId == null) continue;
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) continue;

            String grade = str(item.get("grade"), null);
            String startTime = str(item.get("startTime"), null);
            String venue = str(item.get("venue"), "田径场");
            String round = resolveSavedRound(item, event, grade, startTime, venue,
                    oldRoundByKey, oldRoundByEvent);

            EventSchedule s = EventSchedule.builder()
                    .event(event)
                    .day(intVal(item.get("day"), 1))
                    .scheduleDate(str(item.get("scheduleDate"), null))
                    .grade(grade)
                    .timeSlot(str(item.get("timeSlot"), "上午"))
                    .startTime(startTime)
                    .endTime(str(item.get("endTime"), null))
                    .venue(venue)
                    .sortOrder(order++)
                    .durationMinutes(intVal(item.get("durationMinutes"), 30))
                    .round(round)
                    .remark(str(item.get("remark"), null))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            scheduleRepository.save(s);
        }
        log.info("手动保存赛程: 共{}条（轮次按入参/既有行/needHeats 三级保留）", order - 1);
        collaborationService.notify("schedule", "edited", "EventSchedule", null);
        // M5 修复（同 autoSchedule）：审计写入改到 afterCommit，规避 SQLite 单写者锁竞争；
        // 无活动事务时（单测直调）直接落审计。
        final int savedCount = order - 1;
        auditAfterCommit(() -> auditService.record("SCHEDULE_SAVE", "SCHEDULE", null, "手动保存赛程 " + savedCount + " 条"));
        return buildResult();
    }

    /** 轮次取值三级兜底：入参 → 既有行匹配 → event.needHeats 推断（B09/U09） */
    private String resolveSavedRound(Map<String, Object> item, Event event, String grade,
                                     String startTime, String venue,
                                     Map<String, String> oldRoundByKey,
                                     Map<Long, String> oldRoundByEvent) {
        Object raw = item.get("round");
        if (raw != null && !String.valueOf(raw).isBlank()) return String.valueOf(raw).trim();
        String byKey = oldRoundByKey.get(SchedulePlacementMath.roundKey(event.getId(), grade, startTime, venue));
        if (byKey != null) return byKey;
        String byEvent = oldRoundByEvent.get(event.getId());
        if (byEvent != null) return byEvent;
        return Boolean.TRUE.equals(event.getNeedHeats())
                ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL;
    }

    public void clear() {
        scheduleRepository.deleteAllSchedules();
        log.info("清空项目赛程");
        collaborationService.notify("schedule", "deleted", "EventSchedule", null);
        // M5 修复（同 autoSchedule）：审计写入改到 afterCommit，规避 SQLite 单写者锁竞争；
        // 无活动事务时（单测直调）直接落审计。
        auditAfterCommit(() -> auditService.record("SCHEDULE_CLEAR", "SCHEDULE", null, "清空全部项目赛程"));
    }

    /**
     * M5 配套：审计写入优先走 afterCommit（规避 SQLite 单写者锁竞争）；
     * 若当前无活动事务（如单测直接调用编排方法），则直接落审计——
     * {@code auditService.record} 自身是 REQUIRES_NEW，不依赖外层事务。
     */
    private void auditAfterCommit(Runnable recordTask) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recordTask.run();
                }
            });
        } else if (auditService != null) {
            // 无活动事务且审计服务已注入（生产常态）时直接落审计；
            // 单测若未装配 auditService 则跳过，不因此抛 NPE。
            recordTask.run();
        }
    }

    // ==================== 导出 ====================

    public void export(HttpServletResponse response) {
        List<EventSchedule> schedules = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        // B09/U09：赛程表补「轮次」列——此前仅有项目名称，预赛与决赛混在一起无法区分，
        // 现场拿到赛程表看不出哪个是决赛。统一走 RoundLabelUtil（预赛/决赛/直接决赛）。
        Set<Long> prelimEventIds = arrangementRepository.findAll().stream()
                .filter(a -> ArrangementService.ROUND_PRELIM.equals(a.getRound()))
                .map(a -> a.getEvent() != null ? a.getEvent().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // B13/U11：赛程表是最容易「拿错版本」的一份出口，文件名统一带「阶段 + 版本 + 生成时间」，
        // 与 arrange_result.json / 编排表 / 成绩表 / 秩序册 命名口径一致。
        // 阶段判定：任一「有预赛的项目」已排出决赛条目 = 二次编排后。
        boolean afterSecond = schedules.stream()
                .anyMatch(s -> ArrangementService.ROUND_FINAL.equals(s.getRound())
                        && s.getEvent() != null && prelimEventIds.contains(s.getEvent().getId()));
        String fileName = "项目赛程表_" + com.sports.common.ExportNaming.stage(afterSecond)
                + "_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".xlsx";
        String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);

        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("第几天", "日期", "时段", "开始", "结束", "场地", "年级", "项目名称", "项目编码",
                    "轮次", "类别", "是否田径", "道次", "项目内并发", "预计用时(分)"));
            for (EventSchedule s : schedules) {
                Event e = s.getEvent();
                boolean isTrack = e == null || !Boolean.FALSE.equals(e.getTrack());
                long seId = e != null && e.getId() != null ? e.getId() : -1L;
                String roundLabel = com.sports.common.RoundLabelUtil.label(
                        s.getRound(), prelimEventIds.contains(seId));
                data.add(List.of(
                        "第" + s.getDay() + "天",
                        n(s.getScheduleDate()), n(s.getTimeSlot()),
                        n(s.getStartTime()), n(s.getEndTime()), n(s.getVenue()),
                        n(s.getGrade()),
                        e != null ? n(e.getName()) : "",
                        e != null ? n(e.getCode()) : "",
                        roundLabel,
                        e != null ? n(e.getCategory()) : "",
                        isTrack ? "是" : "否",
                        e != null && e.getLaneCount() != null ? String.valueOf(e.getLaneCount()) : "0",
                        e != null ? String.valueOf(ScheduleAnalysisMath.concurrencyOf(e)) : "",
                        s.getDurationMinutes() != null ? String.valueOf(s.getDurationMinutes()) : ""));
            }
            List<List<String>> head = data.get(0).stream().map(List::of).collect(Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(head).sheet("项目赛程")
                    .doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出赛程失败: " + e.getMessage());
        }
        log.info("导出项目赛程: 共{}条", schedules.size());
    }

    // ==================== 结果组装 ====================

    private Map<String, Object> buildResult() {
        List<EventSchedule> schedules = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();

        // B09/U09：把「该项目的预赛是否已排出」一并算出来，供轮次标签判定
        // （round 为 null 的旧行：项目有预赛 → 决赛，无预赛 → 直接决赛）
        Set<Long> prelimEventIds = arrangementRepository.findAll().stream()
                .filter(a -> ArrangementService.ROUND_PRELIM.equals(a.getRound()))
                .map(a -> a.getEvent() != null ? a.getEvent().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Map<String, Object>> items = schedules.stream().map(s -> {
            Event e = s.getEvent();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("eventId", e != null ? e.getId() : null);
            m.put("eventName", e != null ? e.getName() : "");
            m.put("eventCode", e != null ? e.getCode() : "");
            m.put("category", e != null ? e.getCategory() : "");
            m.put("genderLimit", e != null ? e.getGenderLimit() : "");
            m.put("isTrack", e == null || !Boolean.FALSE.equals(e.getTrack()));
            m.put("laneCount", e != null ? e.getLaneCount() : 0);
            m.put("concurrency", e != null ? ScheduleAnalysisMath.concurrencyOf(e) : 0);
            m.put("isTeam", e != null && Boolean.TRUE.equals(e.getTeam()));
            m.put("teamSize", e != null ? e.getTeamMembers() : 0);
            m.put("day", s.getDay());
            m.put("scheduleDate", s.getScheduleDate());
            m.put("grade", s.getGrade());
            m.put("timeSlot", s.getTimeSlot());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            m.put("venue", s.getVenue());
            m.put("sortOrder", s.getSortOrder());
            m.put("durationMinutes", s.getDurationMinutes());
            // B09/U09：轮次随行返回，前端原样回传 → 手动保存不再丢轮次
            m.put("round", s.getRound());
            m.put("roundLabel", com.sports.common.RoundLabelUtil.label(
                    s.getRound(), e != null && prelimEventIds.contains(e.getId())));
            m.put("remark", s.getRemark());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("days", items.stream().mapToInt(i -> intVal(i.get("day"), 0)).max().orElse(0));

        // 按天分组，方便前端渲染甘特/时间表
        Map<Integer, List<Map<String, Object>>> byDay = items.stream()
                .collect(Collectors.groupingBy(i -> intVal(i.get("day"), 0), TreeMap::new, Collectors.toList()));
        result.put("byDay", byDay);
        return result;
    }

    // ==================== 配置合并（mergeConfig 已迁入 ScheduleBuildComponent） ====================

    // ==================== U29/B26：自检数据装配 ====================




}
