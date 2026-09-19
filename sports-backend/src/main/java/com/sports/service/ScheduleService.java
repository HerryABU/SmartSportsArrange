package com.sports.service;

import com.sports.common.Grades;
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
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.verify.ScheduleViolation;
import com.sports.schedule.opt.SchedulePlan;
import com.sports.schedule.opt.ScheduleUnit;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

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

    /** 单个项目最短占用时间（分钟），避免 0 人报名时挤成一团 */
    private static final int MIN_DURATION = 10;
    /** 单项目时长缩放下限比例：再挤也不该把一个大项压到不足真实用时的 35% */
    private static final double MIN_DURATION_RATIO = 0.35;

    // ==================== 自动编排 ====================

    /**
     * 自动编排赛程。
     *
     * @param override 临时覆盖参数（不落库），可含 startDate / days / gradeOrder /
     *                 trackSlots / fieldSlots / eventOrder / fieldGroups /
     *                 defaultDurationMinutes / defaultIntervalMinutes / venues
     */
    public Map<String, Object> autoSchedule(Map<String, Object> override) {
        Map<String, Object> cfg = mergeConfig(override);

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
        List<Window> windows = buildWindows(cfg);
        if (windows.isEmpty()) {
            throw new RuntimeException("运动会日程未配置可用时段，请先在「系统设置 → 运动会日程」中配置日期与时段");
        }

        List<Unit> units = buildUnits(gradeOrder, eventOrder);
        // B05/U06 根因修复：venue.parallelMax 是「该场地同一时刻能并行几个**项目**」，
        // 与「一个项目内同时下场几个**运动员**」是两个正交的量。
        // 旧实现把 parallelMax 当成项目内并发人数传下去，主跑道 parallelMax=1 时
        // 会把 8 条道的 100 米压成「每组 1 人」→ ceil(24/1)×6 分 = 144 分钟虚假需求，
        // 再被 maxDurationMinutes 硬压到 20 分钟，现场时间估算彻底失真（B05 的 144→20）。
        // 正确的约束路径：parallelMax → 并发池槽位数（trackSlots/fieldSlots/专用池，已在上面处理），
        // 项目内并发只由项目自身配置（concurrency > groupSize > laneCount > defaultLanes）决定。
        estimateDurations(units, heatMinutes, fieldPerAthlete, defaultDuration);

        // B05/U06：时间窗容量可行性预检 + 压缩亏损量化。
        // 只告警不量化，现场无法判断「到底差多少分钟、差在哪个项目」。
        // 这里在放置之前先算清楚：本项目类别真正需要多少分钟、时段总共能提供多少分钟、
        // 缺口多少，并把每一个被压缩的项目列成表（需求/实给/压缩比/缺口）。
        int unitInterval = Math.max(defaultInterval, minInterval);
        Map<String, Object> feasibility = assessFeasibility(units, windows, trackSlots, fieldSlots, unitInterval);
        // U24/B21：先按容量等比适配时长，再进入放置；compressionReport 放到放置之后统计，
        // 这样「就近缩短」的那部分也如实计入（报告反映的是真正落地的时长）。
        fitDurationsToPools(units, feasibility, unitInterval);

        // 项目自带的「并行捆绑组」（表格2 的字母列）优先于配置页分组：
        // 同字母 → 同组 → 安排在同一时段并行；为空则不受限制，由算法自动安排
        for (Unit u : units) {
            String bg = u.event.getBundleGroup();
            if (bg != null && !bg.isBlank()) {
                event2Group.put(u.event.getId(), "捆绑组" + bg.trim().toUpperCase());
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

        // ===== U28/B25：约束求解（Timefold）=====
        // 在落库之前先把「谁排在哪个并发位的哪一刻、每个项目分到多少分钟」整体求出来。
        // 「求解」与「持久化」解耦的好处：求解失败或超时（solvedPlacement 为空）时零副作用回退贪心，
        // 接口在任何情况下都能给出方案。
        Map<Unit, Placement> solvedPlacement = new IdentityHashMap<>();
        int[] solverStat = {0, 0};   // {采用求解结果的项目数, 求解后的残余兼项冲突数}
        Map<String, Object> portfolioInfo = new LinkedHashMap<>();   // 算法选择的可观测信息
        fillSolvedFromSolver(units, trackPool, fieldPool, dedicatedPools, mainVenueCode,
                fieldVenueCodes, codeToName, codeToParallelMax, trackSlots, fieldSlots,
                windows, event2Group, unitInterval, solvedPlacement, solverStat, portfolioInfo);
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
        Set<Long> eventsWithRegs = new HashSet<>();
        for (Event ev : eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()) {
            if (countParticipants(ev.getId(), null) > 0) eventsWithRegs.add(ev.getId());
        }
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

            Pool pool = resolvePool(u, trackPool, fieldPool, mainVenueCode,
                    fieldVenueCodes, codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots);
            String group = u.track ? null : event2Group.get(u.event.getId());

            if (group == null) {
                // 普通单元：占用并发池中最空闲的一个槽位
                placeOne(u, pool, windows, defaultInterval, minInterval, compressionWarnRatio,
                        saved, warnings, orderCounter, autoArrangeFails, busy, conflictStat, solvedPlacement);
                autoArrangeOk += u.arranged;
                done.add(i);
            } else {
                // 田赛分组：同组 + 同年级 的单元安排在同一时段并行进行
                List<Integer> batch = new ArrayList<>();
                for (int j = i; j < units.size(); j++) {
                    if (done.contains(j)) continue;
                    Unit v = units.get(j);
                    if (v.track || !sameGrade(v.grade, u.grade)) continue;
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
                    unitPools.add(resolvePool(units.get(idx), trackPool, fieldPool, mainVenueCode,
                            fieldVenueCodes, codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
                }
                placeBatch(batch, units, unitPools, windows, defaultInterval, minInterval, compressionWarnRatio,
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
        }

        // U24/B21：放置完成后统计「真正落地的时长 vs 真实估算」——此时 u.duration 已含
        // 容量等比缩放与就剩余空间缩短两部分，报告因而与赛程表逐行对得上。
        List<Map<String, Object>> compressionReport = compressionReport(units);

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
            verification = scheduleVerifier.verify(collectVerifyRows(saved, event2Group),
                    collectVerifyExpected(units), dailyCapacityOf(windows)).toMap();
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
                    assessLowerBound(units, windows, saved, trackSlots, fieldSlots).toMap());
        } catch (Exception ex) {
            log.warn("下界评估失败（不影响编排结果）: {}", ex.getMessage());
        }

        // B16/U20：业务级成功判定——不止看 failed:0，还要看业务告警
        // （兼项冲突、严重压缩、时间窗溢出、自检未通过等 warnings 任一非空即视为未完全成功）
        boolean businessOk = warnings.isEmpty() && autoArrangeFails.isEmpty();
        result.put("businessOk", businessOk);
        result.put("success", businessOk);
        result.put("message", businessOk ? "编排完成，业务校验通过" : "编排已完成，但存在业务告警（见 warnings），请复核");
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
        Map<String, Object> cfg = mergeConfig(null);
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
            for (Registration reg : approvedRegs(e.getId(), s.getGrade())) {
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
            dailyCapacity = dailyCapacityOf(buildWindows(cfg));
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

    /**
     * 普通单元：在池中挑选**兼项冲突最少、其次最靠前**的位置，再登记赛程行（径赛顺带自动道次编排）。
     *
     * <p>U23/B20：旧实现只做「最早可用」放置（`pickSlot` + `Cursor.place`），完全不看运动员兼项，
     * 排完再让 {@link ConflictService#detectConflicts()} 报出上百处冲突——那是**事后报错**，
     * 不是**事前规避**。现在改为冲突感知放置：若把本项目放在某时间点会让参赛运动员与已排项目
     * 撞车（时间重叠，或间隔不足 {@link ConflictService#CONFLICT_BUFFER_MIN} 分钟），
     * 就改用同一并发池内其它更合适的时段/槽位；当所有候选都躲不开时，才落到**冲突最少**的
     * 位置，并把残余冲突如实计数上报（不粉饰）。</p>
     *
     * <p>注意：候选里的第一个（最早的）与旧实现完全相同，因此「零冲突时行为不变」，
     * 只有真的会撞车时才发生位移——改动是可解释、可回归的。</p>
     */
    /**
     * 用约束求解器求出全局编排方案（每个项目的落位 + 时长）。
     *
     * <p>求解结果只写回 {@code solvedPlacement}（并把求解器选定的时长写回 {@code unit.duration}），
     * 真正的落库仍由主循环统一完成——「求解」与「持久化」解耦，求解失败时零副作用回退贪心。</p>
     *
     * @param solverStat 出参：[0] = 采用解的项目数，[1] = 求解后的残余兼项冲突数
     */
    private void fillSolvedFromSolver(List<Unit> units, Pool trackPool, Pool fieldPool,
                                      Map<String, Pool> dedicatedPools, String mainVenueCode,
                                      Set<String> fieldVenueCodes, Map<String, String> codeToName,
                                      Map<String, Integer> codeToParallelMax,
                                      int trackSlots, int fieldSlots, List<Window> windows,
                                      Map<Long, String> event2Group, int unitInterval,
                                      Map<Unit, Placement> solvedPlacement, int[] solverStat,
                                      Map<String, Object> portfolioInfo) {
        if (scheduleOptimizer == null) return;

        // ① 预解析每个单元所属的并发池。与主循环调用同一个方法、同一顺序，
        //    因此主循环再次解析必然得到同一个池，不会出现「解落在别的池」。
        Map<Unit, Pool> unitPool = new IdentityHashMap<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            unitPool.put(u, resolvePool(u, trackPool, fieldPool, mainVenueCode, fieldVenueCodes,
                    codeToName, codeToParallelMax, dedicatedPools, trackSlots, fieldSlots));
        }
        if (unitPool.isEmpty()) return;

        // ② 位置值域按池生成一次并复用（同池单元共享同一批候选位置）
        Map<String, List<Placement>> rangeByPool = new LinkedHashMap<>();
        for (Pool p : unitPool.values()) {
            rangeByPool.computeIfAbsent(p.label, k -> placementsOf(p, windows, unitInterval));
        }

        // ③ 组装计划实体：每个单元只暴露「自己池里、且放得下其时长下限」的位置
        List<ScheduleUnit> optUnits = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            Pool pool = unitPool.get(u);
            if (pool == null) continue;
            int floor = minDurationOf(u);
            List<Placement> cands = new ArrayList<>();
            for (Placement p : rangeByPool.get(pool.label)) {
                if (p.getMaxDuration() >= floor) cands.add(p);
            }
            if (cands.isEmpty()) continue;   // 该池整块放不下 → 交给贪心如实报「排不下」
            optUnits.add(new ScheduleUnit("u" + i, u.event.getId(), u.event.getName(), u.grade, u.track,
                    pool.label, u.track ? null : event2Group.get(u.event.getId()),
                    unitInterval, u.rawDuration, floor, sortedAthletes(u), durationChoicesOf(u), cands));
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
        if (solvedPlan == null) return;

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
        solverStat[1] = countResidualClashes(solvedPlacement);
    }

    /**
     * 把一个求解结果落到赛程表：预定区间（允许回填空隙）+ 登记赛程行（径赛顺带道次编排）。
     *
     * @return 落位是否成功；越界或与已有赛程冲突时返回 false（调用方回退贪心放置）
     */
    private boolean applySolved(Unit u, Pool pool, Placement p, List<Window> windows, int interval,
                                List<EventSchedule> saved, List<String> warnings, int[] orderCounter,
                                List<String> autoArrangeFails, double compressionWarnRatio,
                                Map<Long, List<int[]>> busy, int[] conflictStat) {
        if (p.getWindowIdx() < 0 || p.getWindowIdx() >= windows.size()) return false;
        if (p.getSlotIdx() < 0 || p.getSlotIdx() >= pool.cursors.size()) return false;
        Window w = windows.get(p.getWindowIdx());
        int rel = p.getStartMinute() - w.startMinute;
        if (rel < 0 || rel + u.duration > w.capacity) return false;
        if (!pool.cursors.get(p.getSlotIdx()).reserve(p.getWindowIdx(), rel, u.duration, interval)) {
            return false;
        }
        int n = countConflicts(u.athleteIds, w.day, p.getStartMinute(), u.duration, busy);
        if (n == 0) conflictStat[0]++; else conflictStat[1] += n;
        saveSchedule(u, new Slot(w, p.getStartMinute()), pool.venueOf.get(p.getSlotIdx()),
                saved, orderCounter, autoArrangeFails, warnings, compressionWarnRatio, busy);
        return true;
    }

    /** 生成某并发池的全部候选位置：槽位 × 时段窗口 × 起点档位（按 unitInterval 步进） */
    private static List<Placement> placementsOf(Pool pool, List<Window> windows, int gridStep) {
        int step = Math.max(1, gridStep);
        List<Placement> out = new ArrayList<>();
        for (int si = 0; si < pool.slots; si++) {
            String venue = pool.venueOf.get(si);
            for (int wi = 0; wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                for (int off = 0; off + MIN_DURATION <= w.capacity; off += step) {
                    out.add(new Placement(pool.label, si, wi, w.day, w.date, w.slotName, venue,
                            w.startMinute + off, w.startMinute, w.capacity));
                }
            }
        }
        return out;
    }

    /** 项目时长下限：再挤也不该把一个大项压到不足真实用时的 35%（那已不是「压缩」而是「不可执行」） */
    private static int minDurationOf(Unit u) {
        return Math.max(MIN_DURATION, (int) Math.round(u.rawDuration * MIN_DURATION_RATIO));
    }

    /** 候选时长档位（降序）：从真实用时按 5% 递减到下限，供求解器逐项目权衡「保真」与「排得下」 */
    private static List<Integer> durationChoicesOf(Unit u) {
        int raw = u.rawDuration;
        int floor = minDurationOf(u);
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (int pct = 100; pct >= 35; pct -= 5) {
            int v = (int) Math.round(raw * pct / 100.0);
            if (v >= floor) set.add(v);
        }
        set.add(floor);
        List<Integer> out = new ArrayList<>(set);
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /** 参赛运动员 id 升序数组（升序是为了让兼项判定能用双指针求交） */
    private static long[] sortedAthletes(Unit u) {
        long[] arr = new long[u.athleteIds.size()];
        int i = 0;
        for (Long id : u.athleteIds) arr[i++] = id;
        Arrays.sort(arr);
        return arr;
    }

    /** 求解结果的残余兼项冲突数（与检测端同口径）；与 solverStat 一起进日志，便于核对「到底规避掉多少」 */
    private int countResidualClashes(Map<Unit, Placement> solved) {
        List<Unit> placed = new ArrayList<>();
        for (Map.Entry<Unit, Placement> e : solved.entrySet()) {
            if (e.getKey().participants > 0) placed.add(e.getKey());
        }
        int n = 0;
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                Unit a = placed.get(i);
                Unit b = placed.get(j);
                if (!sharesAthlete(a, b)) continue;
                int aS = solved.get(a).getAbsoluteStartMinute();
                int bS = solved.get(b).getAbsoluteStartMinute();
                if (aS < bS + b.duration + ConflictService.CONFLICT_BUFFER_MIN
                        && bS < aS + a.duration + ConflictService.CONFLICT_BUFFER_MIN) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 两个单元是否有共同运动员（小集合驱动，避免全量遍历） */
    private static boolean sharesAthlete(Unit a, Unit b) {
        if (a.athleteIds.isEmpty() || b.athleteIds.isEmpty()) return false;
        Set<Long> small = a.athleteIds.size() <= b.athleteIds.size() ? a.athleteIds : b.athleteIds;
        Set<Long> big = small == a.athleteIds ? b.athleteIds : a.athleteIds;
        for (Long id : small) {
            if (big.contains(id)) return true;
        }
        return false;
    }

    private void placeOne(Unit u, Pool pool, List<Window> windows, int defaultInterval, int minInterval,
                          double compressionWarnRatio, List<EventSchedule> saved, List<String> warnings,
                          int[] orderCounter, List<String> autoArrangeFails,
                          Map<Long, List<int[]>> busy, int[] conflictStat,
                          Map<Unit, Placement> solved) {
        int interval = Math.max(intervalOf(u, defaultInterval), minInterval);
        // U28/B25：优先采用约束求解结果（求解器已联合决定「位置 + 时长」）。
        // 落位失败（该位置已被占用）或该单元没有解时才回退贪心——两条路径都经过同一个 Cursor
        // 记账，因此后续单元的可用空间判断始终是准确的。
        Placement fixed = solved == null ? null : solved.get(u);
        if (fixed != null && fixed.getPoolLabel().equals(pool.label)
                && applySolved(u, pool, fixed, windows, interval, saved, warnings, orderCounter,
                        autoArrangeFails, compressionWarnRatio, busy, conflictStat)) {
            return;
        }
        Cand best = findBestSlot(u, pool, windows, interval, busy);
        if (best == null) {
            warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                    u.grade == null ? "不分年级" : u.grade));
            return;
        }
        // U26/B23：整块放置——u.duration 就是该项目的编排时间单位，不做任何「就地缩短」。
        // 若找不到能整块放下的位置，就如实报「排不下」，由用户按建议调整并发位/天数，
        // 而不是把一个 200 分钟的项目偷偷塞进 60 分钟的缝隙里。
        Cursor cursor = pool.cursors.get(best.slotIdx);
        Probe probe = cursor.placeAt(windows, best.windowIdx, best.startMinute, u.duration);
        if (probe == null) {   // 理论上不会发生（findBestSlot 已校验容量）
            warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                    u.grade == null ? "不分年级" : u.grade));
            return;
        }
        if (best.conflicts == 0) conflictStat[0]++;
        else conflictStat[1] += best.conflicts;
        saveSchedule(u, probe.slot, pool.venueOf.get(best.slotIdx), saved, orderCounter, autoArrangeFails,
                warnings, compressionWarnRatio, busy);
    }

    /**
     * 候选位置：某槽位 × 某窗口 × 某起点，及把该单元放在此处的兼项冲突条数。
     *
     * <p>U26/B23：候选**只包含「整块放得下」的位置**——项目时间是编排的原子单位，
     * 一个项目必须完整占住它自己的时长，不能按旁边剩多少空间临时改小。</p>
     */
    private static class Cand {
        final int slotIdx;
        final int windowIdx;
        final int startMinute;
        final int conflicts;

        Cand(int slotIdx, int windowIdx, int startMinute, int conflicts) {
            this.slotIdx = slotIdx;
            this.windowIdx = windowIdx;
            this.startMinute = startMinute;
            this.conflicts = conflicts;
        }
    }

    /**
     * 在池的各槽位内扫描候选起点，返回排序最优者：
     * <b>冲突数最少 → 日期/时段最早 → 起点最早 → 槽位号最小</b>。
     *
     * <p>同槽位内的候选按 {@code interval} 步进枚举（而非只取「最早可用」这一个点），
     * 这样才能为了避让兼项冲突而主动后移若干分钟；容量边界与 {@link Cursor#place} 保持一致
     * （首项不留前置间隔，后续项留 interval）。</p>
     *
     * @return 最优候选；该池在剩余时段内完全放不下时返回 null
     */
    private Cand findBestSlot(Unit u, Pool pool, List<Window> windows, int interval,
                              Map<Long, List<int[]>> busy) {
        Cand best = null;
        for (int si = 0; si < pool.cursors.size(); si++) {
            Cursor c = pool.cursors.get(si);
            // U25/B22：扫描**所有**窗口（含已部分占用的早先窗口），而不是只从游标当前位置往后，
            // 这样早先窗口剩下的 60~90 分钟碎片也能派上用场，不必整块闲置。
            for (int wi = 0; wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                int base = c.usedAt(wi);
                int gap = base == 0 ? 0 : interval;             // 与 Cursor.place 的段前间隔口径一致
                // U26/B23：项目时间 = 编排的原子单位——只考虑「整块放得下」的位置，
                // 不按剩余空间临时改小时长（那会让项目时长取决于旁边恰好剩多少，现场无法据此布置）。
                if (base + gap + u.duration > w.capacity) continue;
                // 默认贴着该窗口的已用前沿开始（最早、不留空档）；只有当「后移」能真正减少
                // 兼项冲突时才后移——不为填尾巴而人为制造空档。
                int start = w.startMinute + base + gap;
                int n = countConflicts(u.athleteIds, w.day, start, u.duration, busy);
                for (int off = base + gap + interval; n > 0 && off + u.duration <= w.capacity; off += interval) {
                    int s2 = w.startMinute + off;
                    int n2 = countConflicts(u.athleteIds, w.day, s2, u.duration, busy);
                    if (n2 < n) {
                        n = n2;
                        start = s2;
                    }
                }
                Cand cand = new Cand(si, wi, start, n);
                if (beats(cand, best)) best = cand;
            }
        }
        return best;
    }

    /**
     * 候选排序：**冲突少者优先**（尽量避开兼项）→ 窗口靠前 → 起点靠前 → 槽位号小。
     *
     * <p>U26/B23：不再有「是否缩短」这一维——项目时长是固定的编排单位，候选里全是能整块放下的位置。</p>
     *
     * <p>U27/B24：这里**刻意不做 best-fit**。槽位是**并行**资源（不同场地同时开赛），
     * 若为了「贴合」而把后面的项目塞进同一槽位的尾巴，就会出现「两个田赛没能同时开赛」，
     * 白白浪费并行位、还把赛程拉长（回归用例 `fieldSlotsAllowParallelProjects` 正是守这一点）。
     * 「填满已有块」的收益改由**预判口径与放置口径一致**来保证：见 {@link #greedyPacks}。</p>
     */
    private static boolean beats(Cand a, Cand b) {
        if (b == null) return true;
        if (a.conflicts != b.conflicts) return a.conflicts < b.conflicts;
        if (a.windowIdx != b.windowIdx) return a.windowIdx < b.windowIdx;
        if (a.startMinute != b.startMinute) return a.startMinute < b.startMinute;
        return a.slotIdx < b.slotIdx;
    }

    /**
     * 把单元放到「第 day 天 startMinute 起、持续 duration 分钟」会撞上多少条已排项目。
     *
     * <p>与 {@link ConflictService#detectConflicts()} <b>同口径</b>：必须是同一天，且两段
     * 区间重叠、或间隔小于 {@link ConflictService#CONFLICT_BUFFER_MIN} 分钟才计一次。
     * 口径一致是硬要求——否则「排时以为不冲突、检出来又冲突」。</p>
     *
     * @param busy 运动员 → 已占用时间段（绝对分钟 = 天 × 1440 + 当日分钟）
     */
    private static int countConflicts(Set<Long> athleteIds, int day, int startMinute, int duration,
                                      Map<Long, List<int[]>> busy) {
        if (athleteIds == null || athleteIds.isEmpty() || busy == null || busy.isEmpty()) return 0;
        int absStart = day * 1440 + startMinute;
        int absEnd = absStart + duration;
        int n = 0;
        for (Long aid : athleteIds) {
            List<int[]> spans = busy.get(aid);
            if (spans == null) continue;
            for (int[] p : spans) {
                int gap = Math.max(absStart - p[1], p[0] - absEnd);   // 对称间隔
                if (gap < ConflictService.CONFLICT_BUFFER_MIN) n++;
            }
        }
        return n;
    }

    /**
     * 田赛分组 / 并行捆绑组批量放置：同组单元安排在同一时段、**同一时刻同时开始**。
     *
     * <p>实现：先探测各单元（各自场地池）的最早可用位置（不改状态），取其中「最晚的窗口 + 该窗口内最晚的起点」
     * 作为本批共同起点，再把各项目都放到该起点（各占所属池的一个并发位）；若个别槽位在该起点放不下
     * （时段容量不足），退化为各自最早位置并记 warning。</p>
     */
    private void placeBatch(List<Integer> batch, List<Unit> units, List<Pool> unitPools, List<Window> windows,
                            int defaultInterval, int minInterval, double compressionWarnRatio, String group,
                            List<EventSchedule> saved, List<String> warnings,
                            int[] orderCounter, List<String> autoArrangeFails,
                            Map<Long, List<int[]>> busy, int[] conflictStat, Map<Unit, Placement> solved) {
        // U28/B25：组内单元全部拿到求解结果时直接按解落库——「同组同时开赛」由求解器的硬约束
        // 保证（同 groupKey 必须落在同一天同一分钟），无需再做波次编排。个别落位失败只回退该单元，
        // 不牵连整组。
        if (solved != null && !batch.isEmpty()) {
            boolean allSolved = true;
            for (Integer idx : batch) {
                if (!solved.containsKey(units.get(idx))) { allSolved = false; break; }
            }
            if (allSolved) {
                for (int k = 0; k < batch.size(); k++) {
                    Unit su = units.get(batch.get(k));
                    Pool sp = unitPools.get(k);
                    int iv = Math.max(intervalOf(su, defaultInterval), minInterval);
                    if (!applySolved(su, sp, solved.get(su), windows, iv, saved, warnings, orderCounter,
                            autoArrangeFails, compressionWarnRatio, busy, conflictStat)) {
                        placeOne(su, sp, windows, defaultInterval, minInterval, compressionWarnRatio,
                                saved, warnings, orderCounter, autoArrangeFails, busy, conflictStat, null);
                    }
                }
                return;
            }
        }
        int maxSlots = 1;
        for (Pool p : unitPools) maxSlots = Math.max(maxSlots, p.slots);
        for (int from = 0; from < batch.size(); from += maxSlots) {
            List<Integer> wave = batch.subList(from, Math.min(batch.size(), from + maxSlots));

            // 起始窗口下界：各参与槽位当前窗口的最大值（不倒退到已用尽的时段之前）
            int minWindow = 0;
            for (int k = 0; k < wave.size(); k++) {
                Cursor c = cursorOf(unitPools.get(k), k);
                minWindow = Math.max(minWindow, c.windowIdx);
            }

            // ① 各单元最早可用位置（只探测，不推进游标）
            List<Probe> earliest = new ArrayList<>();
            for (int k = 0; k < wave.size(); k++) {
                Unit u = units.get(wave.get(k));
                Pool p = unitPools.get(k);
                earliest.add(cursorOf(p, k).probe(windows, u.duration,
                        Math.max(intervalOf(u, defaultInterval), minInterval), minWindow));
            }

            // ② 共同起点 = 最晚的可用窗口 + 该窗口内最晚的可用起点
            int targetWindow = minWindow;
            for (Probe p : earliest) {
                if (p != null) targetWindow = Math.max(targetWindow, p.windowIdx);
            }
            boolean sameStart = true;
            if (targetWindow >= windows.size()) {
                warnings.add(String.format("田赛分组「%s」因时段已排满未能安排", group));
                continue;
            }
            int commonStart = windows.get(targetWindow).startMinute;
            for (Probe p : earliest) {
                if (p != null && p.windowIdx == targetWindow) {
                    commonStart = Math.max(commonStart, p.slot.startMinute);
                }
            }

            // ③ 各项目放到共同起点；放不下则该项退化为各自最早位置（仍推进游标，避免重叠）
            for (int k = 0; k < wave.size(); k++) {
                Unit u = units.get(wave.get(k));
                Pool p = unitPools.get(k);
                Cursor cursor = cursorOf(p, k);
                Probe probe = cursor.placeAt(windows, targetWindow, commonStart, u.duration);
                if (probe == null) {
                    sameStart = false;
                    Probe fb = earliest.get(k);   // 退化为该槽位各自最早可用位置
                    if (fb != null) {
                        probe = cursor.placeAt(windows, fb.windowIdx, fb.slot.startMinute, u.duration);
                    }
                }
                if (probe == null) {
                    warnings.add(String.format("田赛分组「%s」中项目「%s」因时段已排满未能安排", group, u.event.getName()));
                    continue;
                }
                saveSchedule(u, probe.slot, p.venueOf.get(Math.min(k, p.venueOf.size() - 1)),
                        saved, orderCounter, autoArrangeFails, warnings, compressionWarnRatio, busy);
            }
            if (!sameStart) {
                warnings.add(String.format("田赛分组「%s」部分项目未能同时开始（并发位或时段容量不足），已按各自最早时段顺延", group));
            }
        }
    }

    /** 单元在所属池内的游标：多单元共用同池时依次占用不同槽位 */
    private static Cursor cursorOf(Pool pool, int unitIdx) {
        return pool.cursors.get(unitIdx % pool.cursors.size());
    }

    private static int intervalOf(Unit u, int defaultInterval) {
        return u.event.getIntervalMinutes() != null ? u.event.getIntervalMinutes() : defaultInterval;
    }

    /** 登记一条赛程：径赛排入后立即复用编排引擎生成道次（needHeats 项目=预赛，其余=决赛） */
    private void saveSchedule(Unit u, Slot placed, String venue, List<EventSchedule> saved,
                              int[] orderCounter, List<String> autoArrangeFails,
                              List<String> warnings, double compressionWarnRatio,
                              Map<Long, List<int[]>> busy) {
        boolean needPrelim = u.track && Boolean.TRUE.equals(u.event.getNeedHeats());
        EventSchedule s = EventSchedule.builder()
                .event(u.event)
                .day(placed.window.day)
                .scheduleDate(placed.window.date)
                .grade(u.grade)
                .timeSlot(placed.window.slotName)
                .startTime(fmt(placed.startMinute))
                .endTime(fmt(placed.startMinute + u.duration))
                .venue(venue)
                .sortOrder(orderCounter[0]++)
                .durationMinutes(u.duration)
                .round(needPrelim ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL)
                .remark(u.duration >= u.rawDuration ? null
                        : String.format("预计%d分钟，实给%d分钟（按可用时段容量适配）", u.rawDuration, u.duration))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        // B05/U07 + U24/B21：逐条压缩告警只保留「项目显式配了 maxDurationMinutes 而被压」的情形——
        // 那是用户自己设的上限，值得逐项确认；容量不足造成的等比压缩由 autoSchedule 汇总成
        // 「一池一条」的可执行告警，避免十几条重复文案把真正的业务问题淹没。
        if (u.explicitMaxDuration > 0 && u.rawDuration > u.explicitMaxDuration) {
            warnings.add(String.format("⚠️ 项目时长上限告警：「%s」（%s）预计需 %d 分钟，受该项目显式上限 "
                            + "maxDurationMinutes=%d 约束压缩为 %d 分钟（约 %.0f%%）；如现场时间充足可上调该上限",
                    u.event.getName(), u.grade == null ? "不分年级" : u.grade,
                    u.rawDuration, u.explicitMaxDuration, u.duration,
                    u.explicitMaxDuration * 100.0 / u.rawDuration));
        }
        saved.add(scheduleRepository.save(s));

        // U23/B20：登记本单元占用的时间段，供后续单元做兼项冲突规避（绝对分钟便于跨天比较）
        if (!u.athleteIds.isEmpty()) {
            int absStart = placed.window.day * 1440 + placed.startMinute;
            int[] span = {absStart, absStart + u.duration};
            for (Long aid : u.athleteIds) {
                busy.computeIfAbsent(aid, k -> new ArrayList<>()).add(span);
            }
        }

        if (u.track && u.participants > 0) {
            u.arranged = autoArrangeFor(u, autoArrangeFails);
        }
    }

    /**
     * 为单个径赛单元自动生成道次（先清理该 事件×年级×性别 的旧记录再重排）：
     * <ul>
     *   <li>needHeats（需预赛）项目 → 生成<b>预赛</b>道次（round=preliminary），
     *       录入预赛成绩并计算晋级后由 {@code computeQualifiers} 追加决赛道次与独立决赛赛程条目；</li>
     *   <li>其余项目 → 直接生成<b>决赛</b>道次（round=final）。</li>
     * </ul>
     *
     * @return 成功生成的 性别组 数量（男/女各计 1）
     */
    private int autoArrangeFor(Unit u, List<String> arrFails) {
        Event e = u.event;
        int lanes = concurrencyOf(e);
        String round = Boolean.TRUE.equals(e.getNeedHeats())
                ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL;
        // U22/B19 根因修复：性别组必须从「真实已审核报名」推导，不能只看 event.genderLimit。
        // 现实中 genderLimit 常为空（项目名写着「男子组/女子组」，但该字段没落库/导入时缺失），
        // 旧实现此时会退回「男女各排一版」：男子项目去排 F 组时，
        // registrationRepository 查无报名 → arrange 抛「没有符合条件的已审核报名记录」。
        // 该异常虽被下面的 try/catch 吞掉，却已把外层 autoSchedule 的 @Transactional 事务
        // 标记为 rollback-only，提交时抛 UnexpectedRollbackException → 整个自动编排接口 500，
        // 且整张赛程表被回滚（event_schedule 一行不剩）。
        // 正确口径与 batchArrange 一致：按报名中实际出现的性别逐组编排；没有报名的性别直接跳过，
        // 既不是失败也不产生告警。
        List<String> genders = approvedGenders(e.getId(), u.grade);
        int ok = 0;
        for (String g : genders) {
            try {
                arrangementRepository.deleteByEventRoundGradeGender(e.getId(), round, u.grade, g);
                arrangementService.arrange(e.getId(), u.grade, g, lanes, null, round);
                ok++;
            } catch (Exception ex) {
                arrFails.add(String.format("%s（%s %s）：%s", e.getName(), u.grade,
                        genderLabel(g),
                        ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        if (ok > 0) {
            log.info("自动道次编排: event={}({}), grade={}, genders={}, round={}", e.getName(), e.getId(), u.grade, genders, round);
        }
        return ok;
    }

    /**
     * 该项目在指定年级下「已审核报名」里实际出现的性别（原值，通常为 M/F），去重后排序。
     *
     * <p>U22/B19：这是自动道次编排的正确驱动源——{@code event.genderLimit} 只是「限制」，
     * 缺省（null）代表「不限制」，并不代表「一定男女都有人报名」。只有报名里真的出现过的性别
     * 才应该去调 {@code arrange}，否则会触发「没有符合条件的已审核报名记录」并毒化外层事务。</p>
     *
     * @param grade null/空 = 不限年级（取该项目全部报名）
     * @return 实际存在的性别列表；无报名时返回空列表
     */
    private List<String> approvedGenders(Long eventId, String grade) {
        List<String> out = new ArrayList<>();
        for (Registration r : approvedRegs(eventId, grade)) {
            var a = r.getAthlete();
            if (a == null || a.getGender() == null) continue;
            String g = a.getGender().trim();
            if (!g.isEmpty() && !out.contains(g)) out.add(g);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** 性别原值 → 中文标签（用于告警文案） */
    private static String genderLabel(String g) {
        if (g == null) return "未知";
        if ("M".equalsIgnoreCase(g) || "男".equals(g)) return "男子";
        if ("F".equalsIgnoreCase(g) || "女".equals(g)) return "女子";
        return g;
    }

    // ==================== 赛程单元构建 ====================

    /**
     * 按「年级顺序 → 项目顺序」展开赛程单元。
     *
     * <p>项目顺序：自定义 eventOrder（eventId 有序列表，田赛+径赛混排）优先；
     * 未列入的项目保持项目排序号（sortOrder）相对顺序追加在后（稳定排序）。</p>
     * 年级顺序来自配置；event.gradeGroup 非空时该项目只属于对应年级。
     */
    private List<Unit> buildUnits(List<String> gradeOrder, List<Long> eventOrder) {
        List<Event> events = orderedEvents(eventOrder);

        List<Unit> units = new ArrayList<>();
        if (gradeOrder.isEmpty()) {
            for (Event e : events) units.add(new Unit(e, null));
            return units;
        }

        for (String grade : gradeOrder) {
            for (Event e : events) {
                String gg = e.getGradeGroup();
                if (gg != null && !gg.isBlank() && !Grades.same(gg, grade)) continue;
                units.add(new Unit(e, grade));
            }
        }
        return units;
    }

    /** 按自定义顺序（eventOrder）重排项目；未列入者按原 sortOrder 保持相对顺序（稳定排序） */
    private List<Event> orderedEvents(List<Long> eventOrder) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        if (eventOrder == null || eventOrder.isEmpty()) return events;
        Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < eventOrder.size(); i++) pos.put(eventOrder.get(i), i);
        List<Event> list = new ArrayList<>(events);
        list.sort(Comparator.comparingInt(e -> pos.getOrDefault(e.getId(), Integer.MAX_VALUE)));
        return list;
    }

    /**
     * 项目内并发人数（不含任何场地并行上限）：
     * 显式 concurrency 优先；田赛回退 groupSize（每组工位）；径赛回退 groupSize（泳道）> laneCount > defaultLanes。
     *
     * <p>注意：此处<b>刻意不接受</b> venue.parallelMax。场地并行上限约束的是「并发池槽位数」
     * （见 {@link #autoSchedule} 的 trackSlots/fieldSlots 与 {@link #resolvePool}），
     * 属于项目之间的并行；把它混进项目内并发会把 8 条跑道压成 1 人/组（B05/U06）。</p>
     */
    private int concurrencyOf(Event e) {
        Integer c = e.getConcurrency();
        if (c != null && c > 0) return c;
        if (Boolean.FALSE.equals(e.getTrack())) {
            // 田赛回退链：显式 groupSize（每组次几人，如田赛工位数）> 1
            Integer gs = e.getGroupSize();
            if (gs != null && gs > 0) return gs;
            return 1;
        }
        // 径赛回退链：groupSize（每组次几人/泳道数，如游泳）> 道次数 > 默认道次
        Integer gs = e.getGroupSize();
        if (gs != null && gs > 0) return gs;
        Integer lc = e.getLaneCount();
        if (lc != null && lc > 0) return lc;
        return e.getDefaultLanes() != null && e.getDefaultLanes() > 0 ? e.getDefaultLanes() : 8;
    }

    /**
     * 估算每个单元用时：径赛看组数、田赛看轮次（均按项目内并发折算）。
     *
     * <p>U24/B21：时长以<b>真实估算</b>为准，只受项目<b>显式配置</b>的 maxDurationMinutes 约束；
     * 不再拿 defaultDurationMinutes 当上限一刀切。能否排下由 {@link #fitDurationsToPools}
     * 按各并发池的真实可用容量等比缩放决定（并如实上报缺口与所需并发位）。</p>
     */
    private void estimateDurations(List<Unit> units,
                                   int heatMinutes, int fieldPerAthlete, int defaultDuration) {
        for (Unit u : units) {
            Event e = u.event;
            boolean isTrack = !Boolean.FALSE.equals(e.getTrack());
            List<Registration> regs = approvedRegs(e.getId(), u.grade);
            int count = regs.size();
            int concurrency = concurrencyOf(e);

            int entrants = count;
            if (Boolean.TRUE.equals(e.getTeam()) && e.getTeamMembers() != null && e.getTeamMembers() > 0) {
                entrants = (int) Math.ceil((double) count / e.getTeamMembers());
            }
            // 轮次 = ceil(参赛单元 / 项目内并发人数)
            int rounds = Math.max(1, (int) Math.ceil((double) entrants / Math.max(1, concurrency)));

            u.track = isTrack;
            u.concurrency = concurrency;
            u.participants = count;
            u.heats = isTrack ? rounds : 0;
            u.rounds = rounds;
            // U23/B20：记录参赛者，供兼项冲突规避（与 participants 同一次查询，口径必然一致）
            for (Registration r : regs) {
                if (r.getAthlete() != null && r.getAthlete().getId() != null) {
                    u.athleteIds.add(r.getAthlete().getId());
                }
            }
            // 单轮用时未配置（0）时退回「项目默认时长」，保证任何配置下都能得到一个正数估算
            int perUnit = isTrack ? heatMinutes : fieldPerAthlete;
            int estimated = rounds * perUnit;
            u.rawDuration = Math.max(MIN_DURATION, estimated > 0 ? estimated : defaultDuration);

            // U24/B21：首选时长 = min(真实估算, 项目显式上限)；未配上限就用真实估算。
            // 旧写法把 defaultDurationMinutes(30) 当上限，与「当天有多少时间」无关——
            // 44 人跳远需 198 分钟也被砍成 30 分钟，18 个项目无一例外「全压缩」，
            // 排出来的表看着整齐、现场根本跑不完。真正排不下的部分改由容量等比缩放处理。
            u.explicitMaxDuration = e.getMaxDurationMinutes() != null && e.getMaxDurationMinutes() > 0
                    ? e.getMaxDurationMinutes() : 0;
            u.duration = u.explicitMaxDuration > 0
                    ? Math.min(u.rawDuration, u.explicitMaxDuration)
                    : u.rawDuration;
        }
    }

    /**
     * B05/U06：时间窗容量可行性预检。
     *
     * <p>把「需求」与「供给」分别量化后对比：</p>
     * <ul>
     *   <li>需求：按类别（径赛/田赛）汇总各单元<b>未压缩</b>的预计用时 + 项目间间隔；</li>
     *   <li>供给：该类别的可并发位数（径赛 trackSlots / 田赛 fieldSlots）× 全部时段容量。</li>
     * </ul>
     * <p>两者口径统一后给出 {@code deficitMinutes}（缺口）与 {@code affectedEvents}
     * （被 maxDurationMinutes 压缩的项目）。缺口 &gt; 0 说明即便把所有压缩都用上仍排不完，
     * 必须增加天数/时段/并发位或削减项目规模——这一点以前完全不可见（B05 的现场跑不完）。</p>
     */
    private Map<String, Object> assessFeasibility(List<Unit> units, List<Window> windows,
                                                  int trackSlots, int fieldSlots, int interval) {
        int totalCapacity = windows.stream().mapToInt(w -> w.capacity).sum();
        int trackNeed = 0, trackCount = 0;
        int fieldNeed = 0, fieldCount = 0;
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            int need = u.rawDuration + interval;   // 含项目间间隔
            if (u.track) { trackNeed += need; trackCount++; } else { fieldNeed += need; fieldCount++; }
        }

        Map<String, Object> track = poolFeasibility("径赛", trackNeed, trackCount, totalCapacity, trackSlots, windows.size());
        Map<String, Object> field = poolFeasibility("田赛", fieldNeed, fieldCount, totalCapacity, fieldSlots, windows.size());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowCount", windows.size());
        m.put("windowCapacityMinutes", totalCapacity);
        m.put("track", track);
        m.put("field", field);
        m.put("feasible", Boolean.TRUE.equals(track.get("feasible")) && Boolean.TRUE.equals(field.get("feasible")));
        return m;
    }

    private Map<String, Object> poolFeasibility(String label, int need, int unitCount,
                                                int totalCapacity, int slots, int windowCount) {
        int supply = totalCapacity * Math.max(1, slots);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("unitCount", unitCount);
        m.put("slots", Math.max(1, slots));
        m.put("windowCount", windowCount);
        m.put("requiredMinutes", need);
        m.put("supplyMinutes", supply);
        m.put("deficitMinutes", Math.max(0, need - supply));
        m.put("feasible", need <= supply);
        // U24/B21：给出**可执行**的结论——该池至少要几位并发才排得下（ceil(需求/单个时段容量)）。
        // 只说「缺口 699 分钟」现场无法落地；说「田赛并发位需 ≥3（当前 2）」才能立刻照做。
        m.put("requiredSlots", (int) Math.ceil((double) need / Math.max(1, totalCapacity)));
        return m;
    }

    /**
     * U24/B21：把「真实估算时长」按各并发池的可用容量<b>等比公平缩放</b>。
     *
     * <p>某池需求 ≤ 供给 → 不压缩，全部保留真实用时；需求 &gt; 供给 → 对该池<b>所有</b>单元
     * 等比缩放，用二分搜索取「<b>仍然整块装得下</b>的最大 k」：</p>
     * <ul>
     *   <li><b>上界</b> = 总量口径 {@code (供给 − 间隔总和) / 原始总需求}；</li>
     *   <li><b>可行性判定</b> = {@link #greedyPacks}，它<b>模拟真实放置策略</b>（项目顺序 +
     *       窗口靠前 + 槽位并行 + 块尾碎片）。预判与实际放置同源，才不会出现
     *       「预判装得下、实际却丢项目」——这正是早先用理想 FFD 预判踩过的坑。</li>
     * </ul>
     * <p>「统一等比」而不是「一部分砍到地板、一部分原样排」，结果可解释、可复现，
     * 也让 compressionReport 里的百分比有统一的物理含义（该池被压缩到的比例）。</p>
     *
     * <p>显式配置了 maxDurationMinutes 的项目仍受其上限约束（用户意图优先）；缩放下限固定为
     * {@link #MIN_DURATION}——**不可**拿 defaultDurationMinutes 当下限，那是「默认时长」而非
     * 「最小时长」，设大时会顶住缩放、使其完全失效。</p>
     */
    private void fitDurationsToPools(List<Unit> units, Map<String, Object> feasibility, int interval) {
        for (String key : List.of("track", "field")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pool = (Map<String, Object>) feasibility.get(key);
            if (pool == null) continue;
            int need = intVal(pool.get("requiredMinutes"), 0);
            int supply = intVal(pool.get("supplyMinutes"), 0);
            if (need <= 0 || need <= supply) continue;   // 容量够 → 保留真实用时
            boolean isTrack = "track".equals(key);
            long totalRaw = 0;
            int unitsInPool = 0;
            for (Unit u : units) {
                if (u.participants <= 0 || u.track != isTrack) continue;
                totalRaw += u.rawDuration;
                unitsInPool++;
            }
            if (totalRaw <= 0) continue;
            int slotsInPool = Math.max(1, intVal(pool.get("slots"), 1));
            int windowCount = Math.max(1, intVal(pool.get("windowCount"), 1));
            int capacityPerWindow = Math.max(1, supply / slotsInPool / windowCount);
            // 二分搜索的上界取「纯总量口径」：供给扣掉项目间隔后还能承载多少时长。
            // 真正的可行性判定交给 greedyPacks——它已经模拟了块尾碎片与槽位并行，
            // 不必再额外预留一份「打包余量」（两者同时用会重复扣减、导致过度压缩）。
            double kSum = (supply - (long) unitsInPool * interval) / (double) totalRaw;
            // 项目时间是编排的原子单位（U26/B23）：每个项目必须**整块**落地，于是这是装箱问题。
            // 这里二分搜索「最大的、仍然装得下」的 k：既保住整块语义，又不过度压缩
            // （「任意两块都塞得下」那种充分条件实测压到 43.8% 却还闲置 40% 容量，自相矛盾）。
            double hi = Math.min(1.0, kSum);
            double lo = 0.05;
            double k = lo;
            long[] probeDur = new long[unitsInPool];
            for (int it = 0; it < 24; it++) {              // 24 次足够收敛到 1e-7
                double mid = (lo + hi) / 2;
                int idx = 0;
                for (Unit u : units) {
                    if (u.participants <= 0 || u.track != isTrack) continue;
                    probeDur[idx++] = Math.max(MIN_DURATION, (int) Math.floor(u.rawDuration * mid));
                }
                if (greedyPacks(probeDur, slotsInPool, windowCount, capacityPerWindow, interval)) {
                    k = mid;
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            for (Unit u : units) {
                if (u.participants <= 0 || u.track != isTrack) continue;
                // 缩放下限 = MIN_DURATION：再挤也不该把项目压到毫无意义的几分钟。
                // 注意**不能用** defaultDurationMinutes 当下限——它的语义是「默认时长」，
                // 夹具/用户把它设大（如 600 = 不封顶）时会把下限顶到原始时长，
                // 使等比缩放完全失效（实测：k 被迫掉到 0.05、项目反而排不下）。
                int scaled = Math.max(MIN_DURATION, (int) Math.floor(u.rawDuration * k));
                int target = u.explicitMaxDuration > 0 ? Math.min(scaled, u.explicitMaxDuration) : scaled;
                u.duration = Math.min(u.duration, Math.max(MIN_DURATION, target));
            }
            pool.put("scalePercent", Math.round(k * 1000.0) / 10.0);
        }
    }

    /**
     * 模拟**真实放置策略**的整块装箱可行性（U27/B24）。
     *
     * <p><b>预判口径必须与放置口径一致</b>——这是本方法存在的唯一理由。实际放置是按项目顺序，
     * 对每个单元取「窗口靠前 → 起点靠前 → 槽位号小」的**首次适应(first-fit)**，且槽位之间并行。
     * 早先版本用理想 FFD 预判，结果是「预判装得下、实际却丢了一个项目」——口径不一致的典型。
     * 这里按同一套贪心规则逐块推算，二者同源，预判才对实际有约束力。</p>
     *
     * @param durations      各单元时长（严格按放置顺序）
     * @param slots          该池并发槽位数
     * @param windowCount    时段数
     * @param windowCapacity 单个时段可用分钟（同池内各时段同长）
     * @param interval       项目间最小间隔
     * @return 全部单元都能整块放下则为 true
     */
    private static boolean greedyPacks(long[] durations, int slots, int windowCount,
                                       int windowCapacity, int interval) {
        int slotCount = Math.max(1, slots);
        int[] used = new int[slotCount * Math.max(1, windowCount)];
        for (long d : durations) {
            int dur = (int) d;
            int bestIdx = -1;
            int bestStart = Integer.MAX_VALUE;
            for (int wi = 0; wi < windowCount; wi++) {
                for (int si = 0; si < slotCount; si++) {
                    int idx = wi * slotCount + si;
                    int u = used[idx];
                    int gap = u == 0 ? 0 : interval;
                    if (u + gap + dur > windowCapacity) continue;          // 整块放不下
                    int start = wi * 1440 + u + gap;                       // 绝对分钟，跨天可比
                    if (start < bestStart || (start == bestStart && idx < bestIdx)) {
                        bestStart = start;
                        bestIdx = idx;
                    }
                }
            }
            if (bestIdx < 0) return false;                                 // 没有任何块装得下
            used[bestIdx] += (used[bestIdx] == 0 ? 0 : interval) + dur;
        }
        return true;
    }

    /**
     * B05/U06：压缩亏损量化——把每个「实际用时 &lt; 未压缩预计用时」的项目列成表，
     * 输出需求分钟、实给分钟、压缩比与缺口分钟，供现场判断哪一项最危险。
     */
    private List<Map<String, Object>> compressionReport(List<Unit> units) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Unit u : units) {
            if (u.participants <= 0 || u.duration >= u.rawDuration) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("eventId", u.event.getId());
            c.put("eventName", u.event.getName());
            c.put("grade", u.grade);
            c.put("track", u.track);
            c.put("participants", u.participants);
            c.put("concurrency", u.concurrency);
            c.put("rounds", u.rounds);
            c.put("requiredMinutes", u.rawDuration);
            c.put("givenMinutes", u.duration);
            c.put("deficitMinutes", u.rawDuration - u.duration);
            c.put("ratioPercent", Math.round(u.duration * 1000.0 / u.rawDuration) / 10.0);
            out.add(c);
        }
        out.sort(Comparator.comparingInt(c -> -intVal(c.get("deficitMinutes"), 0)));
        return out;
    }

    /**
     * 该 (项目, 年级) 的「已审核报名」——本类查询报名的<b>唯一入口</b>。
     *
     * <p>U23/B20：以前 countParticipants / approvedGenders / 空单元判定各自查一遍报名，
     * 口径容易漂移（一处滤年级、一处不滤）。统一到这里之后，「人数、参赛者集合、性别集合」
     * 三者都由同一次查询派生，必然互相自洽。</p>
     *
     * @param grade null/空 = 不限年级（取该项目全部已审核报名）
     */
    private List<Registration> approvedRegs(Long eventId, String grade) {
        try {
            List<Registration> regs = registrationRepository.findApprovedByEventId(eventId);
            if (grade == null || grade.isBlank()) return regs;
            return regs.stream()
                    .filter(r -> r.getAthlete() != null && Grades.same(grade, r.getAthlete().getGrade()))
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            return List.of();
        }
    }

    private int countParticipants(Long eventId, String grade) {
        return approvedRegs(eventId, grade).size();
    }

    // ==================== 时间窗 ====================

    /** 把 dayConfigs 铺开成有序的时间窗列表 */
    private List<Window> buildWindows(Map<String, Object> cfg) {
        String startDate = str(cfg.get("startDate"), null);
        List<Map<String, Object>> dayConfigs = castList(cfg.get("dayConfigs"));
        List<Window> windows = new ArrayList<>();

        for (Map<String, Object> dc : dayConfigs) {
            int day = intVal(dc.get("day"), windows.size() + 1);
            String date = str(dc.get("date"), null);
            if ((date == null || date.isBlank()) && startDate != null && !startDate.isBlank()) {
                date = shiftDate(startDate, day - 1);
            }
            List<Map<String, Object>> slots = castList(dc.get("slots"));
            if (slots.isEmpty()) continue;

            for (Map<String, Object> sl : slots) {
                String start = str(sl.get("start"), "08:00");
                String end = str(sl.get("end"), "11:30");
                int s = parseHhMm(start);
                int e = parseHhMm(end);
                if (e <= s) continue;
                windows.add(new Window(day, date, str(sl.get("name"), str(sl.get("key"), "上午")), s, e - s));
            }
        }
        windows.sort(Comparator.comparingInt(w -> w.day));
        return windows;
    }

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
            oldRoundByKey.putIfAbsent(roundKey(old.getEvent().getId(), old.getGrade(),
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
        return buildResult();
    }

    /** 轮次取值三级兜底：入参 → 既有行匹配 → event.needHeats 推断（B09/U09） */
    private String resolveSavedRound(Map<String, Object> item, Event event, String grade,
                                     String startTime, String venue,
                                     Map<String, String> oldRoundByKey,
                                     Map<Long, String> oldRoundByEvent) {
        Object raw = item.get("round");
        if (raw != null && !String.valueOf(raw).isBlank()) return String.valueOf(raw).trim();
        String byKey = oldRoundByKey.get(roundKey(event.getId(), grade, startTime, venue));
        if (byKey != null) return byKey;
        String byEvent = oldRoundByEvent.get(event.getId());
        if (byEvent != null) return byEvent;
        return Boolean.TRUE.equals(event.getNeedHeats())
                ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL;
    }

    private static String roundKey(Long eventId, String grade, String startTime, String venue) {
        return (eventId == null ? "" : eventId)
                + "|" + (grade == null ? "" : grade.trim())
                + "|" + (startTime == null ? "" : startTime.trim())
                + "|" + (venue == null ? "" : venue.trim());
    }

    public void clear() {
        scheduleRepository.deleteAllSchedules();
        log.info("清空项目赛程");
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
                        e != null ? String.valueOf(concurrencyOf(e)) : "",
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
            m.put("concurrency", e != null ? concurrencyOf(e) : 0);
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

    // ==================== 配置合并 ====================

    /** 以已保存的 meet_schedule 为底，用请求参数覆盖（不落库） */
    private Map<String, Object> mergeConfig(Map<String, Object> override) {
        Map<String, Object> cfg = new LinkedHashMap<>(systemService.getMeetSchedule());
        if (override != null) {
            for (Map.Entry<String, Object> e : override.entrySet()) {
                if (e.getValue() != null) cfg.put(e.getKey(), e.getValue());
            }
        }
        if (cfg.get("gradeOrder") == null || strList(cfg.get("gradeOrder")).isEmpty()) {
            cfg.put("gradeOrder", systemService.getGradeOrder());
        }
        // 日期跟随 startDate 重算，避免手工改动后日期错位
        String startDate = str(cfg.get("startDate"), null);
        if (startDate != null && !startDate.isBlank()) {
            List<Map<String, Object>> dayConfigs = castList(cfg.get("dayConfigs"));
            for (Map<String, Object> dc : dayConfigs) {
                dc.put("date", shiftDate(startDate, intVal(dc.get("day"), 1) - 1));
            }
            cfg.put("dayConfigs", dayConfigs);
        }
        return cfg;
    }

    // ==================== 内部类 ====================

    /** 一个赛程单元 = 项目 × 年级 */
    private static class Unit {
        Event event;
        String grade;
        boolean track;        // 是否径赛
        int concurrency = 1;  // 项目内并发人数（径赛=道次/每组人数；田赛=工位数）
        int duration;
        int rawDuration;
        int explicitMaxDuration;   // 项目显式配置的时长上限（0 = 未配置；仅此时才允许按时长封顶）
        int participants;
        int heats;            // 径赛组数
        int rounds;           // 总轮次（径赛=组数、田赛=批次数）
        int arranged;         // 径赛自动道次编排成功的性别组数
        /**
         * 本单元的参赛运动员（已审核报名）。
         *
         * <p>U23/B20：兼项冲突规避的关键输入——放置时据此判断「把该项目排在这个时间点，
         * 会不会和该运动员已排的其它项目撞车」。</p>
         */
        final Set<Long> athleteIds = new HashSet<>();

        Unit(Event event, String grade) {
            this.event = event;
            this.grade = grade;
        }
    }

    /** 并发位池：slots 个并发槽位，槽位与场地一一对应（场地不足则复用） */
    private static class Pool {
        final String label;
        final int slots;
        final List<Cursor> cursors = new ArrayList<>();
        final List<String> venueOf = new ArrayList<>();
        final boolean venueShortage;

        Pool(String label, int slots, List<String> venueNames) {
            this.label = label;
            this.slots = Math.max(1, slots);
            this.venueShortage = venueNames.size() < this.slots;
            for (int i = 0; i < this.slots; i++) {
                cursors.add(new Cursor());
                venueOf.add(venueNames.isEmpty()
                        ? "田径场"
                        : venueNames.get(i % venueNames.size()));
            }
        }

        /** 选当前推进最靠前（最空闲）的槽位 */
        int pickSlot() {
            int best = 0;
            for (int i = 1; i < cursors.size(); i++) {
                if (cursors.get(i).aheadOf(cursors.get(best))) best = i;
            }
            return best;
        }
    }

    /** 一个时段窗口（第几天 + 该天的某个时段） */
    private static class Window {
        int day;
        String date;
        String slotName;
        int startMinute;   // 该时段起点（分钟，自 00:00 起算）
        int capacity;      // 该时段可用分钟数

        Window(int day, String date, String slotName, int startMinute, int capacity) {
            this.day = day;
            this.date = date;
            this.slotName = slotName;
            this.startMinute = startMinute;
            this.capacity = capacity;
        }
    }

    /** 放置结果 */
    private static class Slot {
        Window window;
        int startMinute;

        Slot(Window window, int startMinute) {
            this.window = window;
            this.startMinute = startMinute;
        }
    }

    /** 探测结果（只读，不修改游标状态）：给出某槽位最早可放位置 */
    private static class Probe {
        final int windowIdx;
        final Slot slot;

        Probe(int windowIdx, Slot slot) {
            this.windowIdx = windowIdx;
            this.slot = slot;
        }
    }

    /**
     * 场地时间游标：**逐窗口记账**（每个窗口各自的已用分钟），而不是只记「当前推进到哪」。
     *
     * <p>U25/B22：旧实现是「单向传送带」——只有 {@code windowIdx} + {@code used} 两个标量，
     * 一旦推进到后面的窗口，前面窗口的剩余空间就<b>再也回不去</b>。实测（SMOKE_SEED=20260918）：
     * 田赛 1680 分钟容量只用了 1068 分钟、闲置 612 分钟（大多是早先窗口 60~90 分钟的碎片），
     * 却仍有 3 个项目「排不下」——宁可整块闲置也不回填，是典型的死算法。</p>
     *
     * <p>改为按窗口记账后，放置可以回填任意窗口的剩余空间，打包浪费从「整块闲置」降到
     * 「每块至多浪费 floorDuration」，容量利用率显著提升。</p>
     */
    private static class Cursor {
        int windowIdx = 0;      // 已推进到的窗口（仅供参考/田赛分组的「不倒退」下界）
        int used = 0;           // windowIdx 窗口内的已用分钟（含间隔）
        final Map<Integer, Integer> usedByWindow = new HashMap<>();

        /**
         * 该窗口内已占用的时间段（相对窗口起点的 {@code [start, end)} 列表，按 start 升序）。
         *
         * <p>U28/B25：从「只记一个前沿」升级为「记区间集合」。约束求解器给出的位置是
         * <b>乱序</b>的（完全可能先排 10:00 的、再排 08:00 的空档），只靠单一前沿判断会把
         * 合法位置误判为冲突，导致求解结果大面积落位失败。区间集合同时让「能回填任意空隙」
         * 从口头约定变成可判定的事实。</p>
         */
        final Map<Integer, List<int[]>> occupied = new HashMap<>();

        /** 某窗口已用分钟（含该项目的前置间隔） */
        int usedAt(int wi) {
            return usedByWindow.getOrDefault(wi, 0);
        }

        /** 记下「第 wi 个窗口的 [startRel, endRel) 已被占用」，并同步推进标记 */
        private void mark(int wi, int startRel, int endRel) {
            List<int[]> list = occupied.computeIfAbsent(wi, k -> new ArrayList<>());
            list.add(new int[]{startRel, endRel});
            list.sort(Comparator.comparingInt(iv -> iv[0]));
            usedByWindow.merge(wi, endRel, Math::max);
            if (wi >= windowIdx) {
                windowIdx = wi;
                used = usedByWindow.getOrDefault(wi, 0);
            }
        }

        /** 该区间是否与该窗口已有占用冲突（含段前间隔；interval 取 0 即纯重叠判定） */
        private boolean conflict(int wi, int startRel, int endRel, int interval) {
            for (int[] iv : occupied.getOrDefault(wi, List.of())) {
                if (startRel < iv[1] + interval && iv[0] < endRel + interval) return true;
            }
            return false;
        }

        /**
         * 预定一段区间（供约束求解结果落位使用）。
         *
         * <p>与 {@link #placeAt} 的关键区别是<b>不做「不得早于前沿」的顺位假设</b>：求解器可能先给出
         * 靠后的位置、再给出靠前的空档——这正是「回填空隙」应有的能力。仍然严格校验区间不重叠，
         * 所以放开顺位不会产生重叠赛程。</p>
         *
         * @return 预定成功；该区间与已有占用冲突时返回 false（调用方应回退贪心放置）
         */
        boolean reserve(int wi, int startRel, int duration, int interval) {
            if (wi < 0 || startRel < 0) return false;
            int endRel = startRel + duration;
            if (conflict(wi, startRel, endRel, interval)) return false;
            mark(wi, startRel, endRel);
            return true;
        }

        /** 放进最早的「还放得下」窗口（回填允许）；都放不下返回 null */
        Slot place(List<Window> windows, int duration, int interval) {
            return place(windows, duration, interval, 0);
        }

        /** 同上，但起点不早于 minWindowIdx 号窗口（用于田赛分组：同组落在同一天起） */
        Slot place(List<Window> windows, int duration, int interval, int minWindowIdx) {
            for (int wi = Math.max(0, minWindowIdx); wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                int u = usedAt(wi);
                int gap = u == 0 ? 0 : interval;     // 段前间隔：除窗口起点外，项目之间留间隔
                if (u + gap + duration <= w.capacity) {
                    mark(wi, u + gap, u + gap + duration);
                    return new Slot(w, w.startMinute + u + gap);
                }
            }
            return null;
        }

        /** 探测最早可放位置（只读，不记账）；起点不早于 minWindowIdx 号窗口 */
        Probe probe(List<Window> windows, int duration, int interval, int minWindowIdx) {
            for (int wi = Math.max(0, minWindowIdx); wi < windows.size(); wi++) {
                Window w = windows.get(wi);
                int u = usedAt(wi);
                int gap = u == 0 ? 0 : interval;
                if (u + gap + duration <= w.capacity) {
                    return new Probe(wi, new Slot(w, w.startMinute + u + gap));
                }
            }
            return null;
        }

        /**
         * 在指定窗口、指定起点放置；越界 / 与该窗口已有占用重叠则返回 null。
         * 用于田赛分组/捆绑组：把同组项目强制落到同一 (窗口, 起点) 以实现同时开赛。
         * 允许「回填」——只要起点不在已有占用的前沿之前。
         */
        Probe placeAt(List<Window> windows, int wi, int startMinute, int duration) {
            if (wi < 0 || wi >= windows.size()) return null;
            Window w = windows.get(wi);
            int rel = startMinute - w.startMinute;      // 相对窗口起点的偏移
            if (rel < 0) return null;
            if (rel + duration > w.capacity) return null;
            int u = usedAt(wi);
            if (u > 0 && rel < u) return null;          // 不许压到已占用区间上
            mark(wi, rel, rel + duration);
            return new Probe(wi, new Slot(w, startMinute));
        }

        /** 比较推进程度：窗口更靠前、或同窗口已用时间更短的更"空闲" */
        boolean aheadOf(Cursor other) {
            if (windowIdx != other.windowIdx) return windowIdx < other.windowIdx;
            return used < other.used;
        }
    }

    // ==================== 工具方法 ====================

    private static int parseHhMm(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== U29/B26：自检数据装配 ====================

    /**
     * 把落库后的赛程行转成校验视图。
     *
     * <p>刻意取<b>持久化之后的真实数据</b>（场地映射、时长回写都已完成），而不是求解器内存里的解：
     * 从「解」到「赛程表」之间要经过落库、场地映射、时长写回，任何一步出错求解器都看不见，
     * 只有对最终产物复核才查得出来。</p>
     */
    private List<ScheduleVerifier.Row> collectVerifyRows(List<EventSchedule> saved,
                                                         Map<Long, String> event2Group) {
        List<ScheduleVerifier.Row> rows = new ArrayList<>();
        for (EventSchedule s : saved) {
            Event e = s.getEvent();
            if (e == null || e.getId() == null) continue;
            Set<Long> athletes = new LinkedHashSet<>();
            Map<Long, String> names = new LinkedHashMap<>();
            for (Registration reg : approvedRegs(e.getId(), s.getGrade())) {
                if (reg.getAthlete() == null || reg.getAthlete().getId() == null) continue;
                Long aid = reg.getAthlete().getId();
                athletes.add(aid);
                names.put(aid, reg.getAthlete().getName());
            }
            String group = event2Group.get(e.getId());
            String groupKey = group == null ? null
                    : group + "@" + (s.getGrade() == null ? "" : s.getGrade());
            rows.add(new ScheduleVerifier.Row(e.getId(), e.getName(), s.getGrade(),
                    s.getDay() == null ? 0 : s.getDay(), s.getScheduleDate(), s.getTimeSlot(),
                    parseMinute(s.getStartTime()), parseMinute(s.getEndTime()), s.getVenue(),
                    groupKey, athletes, names));
        }
        return rows;
    }

    /** 编排表里「应该有」的单元：用于发现被静默丢弃的项目，并算出真实压缩比 */
    private List<ScheduleVerifier.Expected> collectVerifyExpected(List<Unit> units) {
        List<ScheduleVerifier.Expected> list = new ArrayList<>();
        for (Unit u : units) {
            if (u.participants <= 0 || u.event.getId() == null) continue;
            list.add(new ScheduleVerifier.Expected(
                    ScheduleVerifier.keyOf(u.event.getId(), u.grade),
                    u.event.getName(), u.grade, u.rawDuration));
        }
        return list;
    }

    /**
     * 理论下界评估（U31/B28）。
     *
     * <p>并发位按「径赛 / 田赛」两类聚合：项目级专用池（defaultVenueCode 绑定）较少见，
     * 为一个保守估计去主循环里额外维护 Unit→Pool 映射并不划算——下界本就允许偏松，
     * 偏松只会让 gap 看起来更小，不会把不可行说成可行（方向是安全的）。</p>
     */
    private LowerBoundEstimator.Assessment assessLowerBound(List<Unit> units, List<Window> windows,
                                                            List<EventSchedule> saved,
                                                            int trackSlots, int fieldSlots) {
        Map<String, Integer> slotsByPool = new LinkedHashMap<>();
        slotsByPool.put("径赛", Math.max(1, trackSlots));
        slotsByPool.put("田赛", Math.max(1, fieldSlots));

        List<LowerBoundEstimator.Item> items = new ArrayList<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            items.add(new LowerBoundEstimator.Item(u.track ? "径赛" : "田赛",
                    u.rawDuration, Math.max(1, intervalOf(u, 5)), u.athleteIds));
        }

        Set<Integer> days = new HashSet<>();
        for (Window w : windows) days.add(w.day);

        int given = 0;
        for (EventSchedule s : saved) {
            given += Math.max(0, parseMinute(s.getEndTime()) - parseMinute(s.getStartTime()));
        }
        return lowerBoundEstimator.assess(items, slotsByPool, dailyCapacityOf(windows),
                days.size(), given);
    }

    /** 单日可用分钟总数（各天取最大值：各天时段配置通常一致，取最大避免低估容量而误报利用率） */
    private static int dailyCapacityOf(List<Window> windows) {
        Map<Integer, Integer> byDay = new LinkedHashMap<>();
        for (Window w : windows) byDay.merge(w.day, w.capacity, Integer::sum);
        int max = 0;
        for (Integer v : byDay.values()) {
            if (v != null && v > max) max = v;
        }
        return max;
    }

    /** 解析 "HH:mm"（与 {@link #fmt} 互逆）；解析不了就返回 0——不让一行脏数据把整次自检拖崩 */
    private static int parseMinute(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return 0;
        String t = hhmm.trim();
        int colon = t.indexOf(':');
        try {
            if (colon < 0) return Integer.parseInt(t);
            int h = Integer.parseInt(t.substring(0, colon));
            int m = Integer.parseInt(t.substring(colon + 1));
            return Math.max(0, h * 60 + m);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 摘出第一条阻塞级问题的摘要，用于 warnings 里的一行提示（完整清单走 verification 字段） */
    @SuppressWarnings("unchecked")
    private static String firstViolationBrief(Map<String, Object> verification) {
        Object vs = verification.get("violations");
        if (!(vs instanceof List<?> list)) return "";
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            if (!ScheduleViolation.LEVEL_BLOCKER.equals(String.valueOf(m.get("level")))) continue;
            return String.valueOf(m.get("subject")) + " → " + String.valueOf(m.get("detail"));
        }
        return "";
    }

    private static String fmt(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static String shiftDate(String startDate, int offset) {
        try {
            return LocalDate.parse(startDate).plusDays(offset).toString();
        } catch (Exception e) {
            return startDate;
        }
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    /** 读取 double 配置项，解析失败返回默认值（B05/U07：压缩告警阈值） */
    private static double dblVal(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try { return Double.parseDouble(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    private static String n(String s) { return s != null ? s : ""; }

    /** 两个年级是否同一（空 = 不分年级，视为相同） */
    private static boolean sameGrade(String a, String b) {
        boolean ea = a == null || a.isBlank();
        boolean eb = b == null || b.isBlank();
        if (ea && eb) return true;
        if (ea || eb) return false;
        return Grades.same(a, b);
    }

    /** 解析自定义项目顺序（eventId 列表） */
    private static List<Long> longList(Object v) {
        List<Long> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            Long id = asLong(o);
            if (id != null) out.add(id);
        }
        return out;
    }

    /** 解析田赛分组 [{name, eventIds:[...]}] → eventId → 组名（同名视为同组） */
    @SuppressWarnings("unchecked")
    private static Map<Long, String> parseFieldGroups(Object v) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (!(v instanceof List<?> list)) return map;
        int idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = (Map<String, Object>) o;
            String name = str(g.get("name"), null);
            if (name == null || name.isBlank()) name = "田赛组" + (++idx);
            if (!(g.get("eventIds") instanceof List<?> ids)) continue;
            for (Object idObj : ids) {
                Long id = asLong(idObj);
                if (id != null) map.put(id, name);
            }
        }
        return map;
    }

    /** 解析场地列表：兼容旧字符串数组 ["田径场", …] 与新对象数组 [{name, code}, …] */
    @SuppressWarnings("unchecked")
    private static List<String> venueNames(Object v) {
        List<String> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o == null) continue;
            if (o instanceof Map<?, ?> m) {
                Object name = ((Map<String, Object>) m).get("name");
                if (name != null && !String.valueOf(name).isBlank()) out.add(String.valueOf(name).trim());
            } else {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** 解析场地列表为完整 {name, code} 对象列表（保留编码，用于按项目级场地编码绑定并发池） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> venueListOf(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> mm = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) mm.put(String.valueOf(en.getKey()), en.getValue());
            boolean hasName = mm.get("name") != null && !String.valueOf(mm.get("name")).isBlank();
            boolean hasCode = mm.get("code") != null && !String.valueOf(mm.get("code")).isBlank();
            if (hasName || hasCode) out.add(mm);
        }
        return out;
    }

    /** 取场地对象的编码（trim 后）；无编码返回 null */
    private static String codeOf(Map<String, Object> v) {
        if (v == null) return null;
        Object code = v.get("code");
        return code != null && !String.valueOf(code).isBlank() ? String.valueOf(code).trim() : null;
    }

    /**
     * 解析单元应使用哪个并发池：
     * <ul>
     *   <li>未指定场地编码 → 按类别回退默认池（径赛主池 / 田赛池）；</li>
     *   <li>指定了编码且与主径赛场地相同 → 主径赛池；</li>
     *   <li>指定了田赛场地编码 → 该田赛场地的独立池（并发位数=fieldSlots）；</li>
     *   <li>指定了其它新场地编码（如游泳馆）→ 建立独立 1 位并发池，与主/田赛池并行（即「同排」）。</li>
     * </ul>
     * 同一编码的专用池只创建一次（缓存于 dedicatedPools）。
     */
    private Pool resolvePool(Unit u, Pool trackPool, Pool fieldPool,
                            String mainVenueCode, Set<String> fieldVenueCodes,
                            Map<String, String> codeToName, Map<String, Integer> codeToParallelMax,
                            Map<String, Pool> dedicatedPools,
                            int trackSlots, int fieldSlots) {
        String code = u.event.getDefaultVenueCode();
        if (code == null || code.isBlank()) return u.track ? trackPool : fieldPool;
        code = code.trim();
        if (dedicatedPools.containsKey(code)) return dedicatedPools.get(code);
        if (code.equals(mainVenueCode)) return trackPool;
        // 场地名优先级：Event.defaultVenue（表格2「场地」列）> 场地表按编码反查 > 编码本身
        String name = (u.event.getDefaultVenue() != null && !u.event.getDefaultVenue().isBlank())
                ? u.event.getDefaultVenue().trim()
                : codeToName.getOrDefault(code, code);
        int slots = fieldVenueCodes.contains(code) ? fieldSlots : codeToParallelMax.getOrDefault(code, 1);
        Pool p = new Pool("场地-" + code, slots, java.util.List.of(name));
        dedicatedPools.put(code, p);
        return p;
    }

    /** 取某类型的场地子集（type 精确匹配） */
    private static List<Map<String, Object>> venuesOfType(List<Map<String, Object>> venueList, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : venueList) {
            Object t = v.get("type");
            if (t != null && type.equalsIgnoreCase(String.valueOf(t).trim())) out.add(v);
        }
        return out;
    }

    private static String nameOf(Map<String, Object> v) {
        Object n = v.get("name");
        return n != null && !String.valueOf(n).isBlank() ? String.valueOf(n).trim() : codeOf(v);
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try { return Long.parseLong(String.valueOf(o).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object v) {
        if (!(v instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，]")) {
                if (!part.trim().isEmpty()) out.add(part.trim());
            }
        }
        return out;
    }
}
