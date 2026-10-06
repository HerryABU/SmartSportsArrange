package com.sports.service.schedule;

import com.sports.entity.protection.AdminTimeProtection;
import com.sports.entity.venue.Venue;
import com.sports.repository.venue.VenueRepository;
import com.sports.schedule.analysis.DaysEstimator;
import com.sports.schedule.core.math.ScheduleAnalysisMath;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.support.protection.ProtectionMath;
import com.sports.service.protection.AdminTimeProtectionService;
import com.sports.service.system.SystemService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sports.schedule.support.ScheduleSupport.*;

/**
 * 自动编排的<b>准备阶段</b>（从 {@code ScheduleService.autoSchedule} 抽出，约 190 行内联逻辑）。
 *
 * <p>职责边界很清楚——把「排布之前必须先定下来的东西」全部算完：</p>
 * <ol>
 *   <li><b>配置合并</b>：前端覆盖 &gt; 已存编排规则 &gt; 内置默认（含此前只落库、从未被读取的 max_attempts）；</li>
 *   <li><b>场地与并发位</b>：数据库场地表的 parallelMax 决定池并发位，按 type 区分主场地/田赛池；</li>
 *   <li><b>单元与时长</b>：按年级顺序与项目顺序展开单元、估算时长；</li>
 *   <li><b>时间窗</b>：时间三态（指定 / 不限 / 尽可能减少）决定天数，再按 (天, 时段) 铺开；</li>
 *   <li><b>行政时间保护</b>：GLOBAL 避让切分时间窗（硬约束）、TEACHER 时段展开为项目黑名单；</li>
 *   <li><b>可行性预检</b>：算清缺口并先按容量等比适配时长（只告警不量化，现场无法落地）；</li>
 *   <li><b>并行分组与并发池</b>：捆绑组 / 合作组归并 + 场地不足时的前置告警。</li>
 * </ol>
 *
 * <p>本类不做任何排布、不写数据库、不报进度——那些是后续相位的事。因此它可以脱离 facade 单独测试，
 * 也是「上帝方法」被拆开之后各相位能各自演进的前提。</p>
 */
@Slf4j
public class AutoSchedulePreparer {

    private final ScheduleBuildComponent buildComponent;
    private final SystemService systemService;
    private final VenueRepository venueRepository;
    private final AdminTimeProtectionService protectionService;

    public AutoSchedulePreparer(ScheduleBuildComponent buildComponent,
                                SystemService systemService,
                                VenueRepository venueRepository,
                                AdminTimeProtectionService protectionService) {
        this.buildComponent = buildComponent;
        this.systemService = systemService;
        this.venueRepository = venueRepository;
        this.protectionService = protectionService;
    }

    /** 执行准备阶段，产出后续相位所需的全部上下文。 */
    public AutoScheduleContext prepare(Map<String, Object> override) {
        AutoScheduleContext c = new AutoScheduleContext();
        c.cfg = buildComponent.mergeConfig(override);
        Map<String, Object> cfg = c.cfg;

        mergeMaxAttemptsFromArrangeRule(cfg);

        c.gradeOrder = strList(cfg.get("gradeOrder"));
        // 并发位数：1 = 串行（同一时刻只进行 1 个项目）；n = 同时进行 n 个项目
        c.trackSlots = Math.max(1, intVal(cfg.get("trackSlots"), 1));
        c.fieldSlots = Math.max(1, intVal(cfg.get("fieldSlots"), 2));
        c.defaultDuration = intVal(cfg.get("defaultDurationMinutes"), 30);
        c.defaultInterval = intVal(cfg.get("defaultIntervalMinutes"), 5);
        c.heatMinutes = intVal(cfg.get("heatMinutes"), 6);
        c.fieldPerAthlete = intVal(cfg.get("fieldPerAthleteMinutes"), 3);
        // B05/U07：项目间隔下限（不小于此值，避免项目紧贴导致现场不可行）+ 压缩告警阈值。
        // 当某项目被压缩到「预计用时的 1/ratio 以下」时视为严重压缩，写入 warnings 告警。
        c.minInterval = Math.max(1, intVal(cfg.get("minIntervalMinutes"), 5));
        c.compressionWarnRatio = dblVal(cfg.get("compressionWarnRatio"), 1.5);

        resolveVenues(c);
        resolvePools(c);

        c.eventOrder = longList(cfg.get("eventOrder"));
        c.event2Group = parseFieldGroups(cfg.get("fieldGroups"));

        c.units = buildComponent.buildUnits(c.gradeOrder, c.eventOrder);
        // B05/U06 根因修复：venue.parallelMax 是「该场地同一时刻能并行几个**项目**」，
        // 与「一个项目内同时下场几个**运动员**」是两个正交的量。
        // 旧实现把 parallelMax 当成项目内并发人数传下去，主跑道 parallelMax=1 时
        // 会把 8 条道的 100 米压成「每组 1 人」→ ceil(24/1)×6 分 = 144 分钟虚假需求，
        // 再被 maxDurationMinutes 硬压到 20 分钟，现场时间估算彻底失真（B05 的 144→20）。
        // 正确的约束路径：parallelMax → 并发池槽位数（trackSlots/fieldSlots/专用池，已在上面处理），
        // 项目内并发只由项目自身配置（concurrency > groupSize > laneCount > defaultLanes）决定。
        buildComponent.estimateDurations(c.units, c.heatMinutes, c.fieldPerAthlete, c.defaultDuration);

        // 项目间间隔（编排放置与可行性预检统一口径）
        c.unitInterval = Math.max(c.defaultInterval, c.minInterval);

        buildWindows(c);
        applyTimeProtections(c);
        assessFeasibility(c);
        assignParallelGroups(c);

        return c;
    }

    // ==================== ① 配置合并 ====================

    /**
     * U33/B30 配套：把「系统设置 → 编排规则」里保存的「最大尝试次数」并入编排配置，
     * 使「设为无限次」真正生效——此前该参数只落库、从未被读取。
     *
     * <p>优先级：本次前端覆盖（override）&gt; 已存编排规则 &gt; 内置默认。</p>
     */
    private void mergeMaxAttemptsFromArrangeRule(Map<String, Object> cfg) {
        try {
            Map<String, Object> rule = systemService.getArrangeRule();
            Object algRaw = rule == null ? null : rule.get("algorithm_params");
            if (algRaw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> alg = (Map<String, Object>) algRaw;
                if (cfg.get("max_attempts") == null && alg.get("max_attempts") != null) {
                    cfg.put("max_attempts", alg.get("max_attempts"));
                }
            }
        } catch (Exception ex) {
            log.warn("读取编排规则算法参数失败，将沿用默认值: {}", ex.toString());
        }
    }

    // ==================== ② 场地解析 ====================

    /**
     * 场地来源：优先使用数据库「场地表」(含 parallelMax 并行上限)；为空则回退配置 JSON 的 venues。
     * 并按场地类型定位主场地/田赛池（Bug1 修复）。
     */
    private void resolveVenues(AutoScheduleContext c) {
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
            venueList = venueListOf(c.cfg.get("venues"));
        }
        c.venueList = venueList;
        c.venues = venueNames(venueList);

        Map<String, String> codeToName = new LinkedHashMap<>();
        Map<String, Integer> codeToParallelMax = new LinkedHashMap<>();
        for (Map<String, Object> v : venueList) {
            Object code = v.get("code");
            Object name = v.get("name");
            Object pm = v.get("parallelMax");
            if (code != null && !String.valueOf(code).isBlank()) {
                String code3 = String.valueOf(code).trim();
                codeToName.put(code3, name != null ? String.valueOf(name).trim() : code3);
                int max = (pm instanceof Number n) ? n.intValue() : 1;
                codeToParallelMax.put(code3, Math.max(1, max));
            }
        }
        c.codeToName = codeToName;
        c.codeToParallelMax = codeToParallelMax;

        // DB 场地表存在时，主场地/首个田赛场地的 parallelMax 即其池并发上限
        // Bug1 修复：按场地类型定位——主场地=首个 type=track；田赛池=type=field；
        // pool/other 类型（如游泳馆）不进田赛轮询池，只能被项目 defaultVenueCode 显式绑定
        c.trackTypeVenues = venuesOfType(venueList, "track");
        c.fieldTypeVenues = venuesOfType(venueList, "field");
        c.hasType = !c.trackTypeVenues.isEmpty() || !c.fieldTypeVenues.isEmpty();
        if (useDbVenues && c.hasType) {
            if (!c.trackTypeVenues.isEmpty()) {
                Integer mainPm = codeToParallelMax.get(codeOf(c.trackTypeVenues.get(0)));
                if (mainPm != null) {
                    c.trackSlots = Math.max(1, mainPm);
                }
            }
            if (!c.fieldTypeVenues.isEmpty()) {
                Integer fPm = codeToParallelMax.get(codeOf(c.fieldTypeVenues.get(0)));
                if (fPm != null) {
                    c.fieldSlots = Math.max(1, fPm);
                }
            }
        }
    }

    // ==================== ⑦ 资源池 ====================

    /**
     * 资源池：按类别分配并发位。径赛用主场地；田赛只用 type=field 的场地（不足则复用并告警）；
     * pool/other 类型（游泳馆等）不参与田赛轮询，只能被项目 defaultVenueCode 显式绑定。
     * 兼容旧配置：场地表/配置 JSON 均无类型信息时，回退「首个=主场地、其余=田赛」的旧规则。
     */
    private void resolvePools(AutoScheduleContext c) {
        if (c.hasType) {
            c.mainVenue = c.trackTypeVenues.isEmpty()
                    ? (c.venues.isEmpty() ? "田径场" : c.venues.get(0))
                    : nameOf(c.trackTypeVenues.get(0));
            c.mainVenueCode = c.trackTypeVenues.isEmpty()
                    ? (c.venueList.isEmpty() ? null : codeOf(c.venueList.get(0)))
                    : codeOf(c.trackTypeVenues.get(0));
            c.fieldVenues = new ArrayList<>();
            for (Map<String, Object> v : c.fieldTypeVenues) {
                c.fieldVenues.add(nameOf(v));
            }
        } else {
            c.mainVenue = c.venues.isEmpty() ? "田径场" : c.venues.get(0);
            c.mainVenueCode = c.venueList.isEmpty() ? null : codeOf(c.venueList.get(0));
            c.fieldVenues = c.venues.size() > 1
                    ? new ArrayList<>(c.venues.subList(1, c.venues.size()))
                    : new ArrayList<>(List.of(c.mainVenue));
        }
        Set<String> fieldVenueCodes = new HashSet<>();
        for (Map<String, Object> v : c.fieldTypeVenues) {
            String code3 = codeOf(v);
            if (code3 != null) {
                fieldVenueCodes.add(code3);
            }
        }
        c.fieldVenueCodes = fieldVenueCodes;
        c.trackPool = new Pool("径赛", c.trackSlots, new ArrayList<>(List.of(c.mainVenue)));
        c.fieldPool = new Pool("田赛", c.fieldSlots, c.fieldVenues);
        // 项目级指定场地时按需创建的独立并发池（key=场地编码），与主/田赛池并行
        c.dedicatedPools = new LinkedHashMap<>();

        // 场地相关前置告警（与放置顺序无关，先算好，最后合并进 warnings）
        c.venueWarnings = new ArrayList<>();
        if (c.trackPool.venueShortage) {
            c.venueWarnings.add(String.format("径赛并数 %d 超过可用场地数 %d，部分槽位将复用同一场地（并数取决于场地数量，请增加场地或调小并数）",
                    c.trackSlots, c.venues.size()));
        }
        if (c.fieldPool.venueShortage) {
            c.venueWarnings.add(String.format("田赛并数 %d 超过可用田赛场地数 %d，部分槽位将复用同一场地（并数取决于场地数量，请增加场地或调小并数）",
                    c.fieldSlots, c.fieldVenues.size()));
        }
    }

    // ==================== ④ 时间窗 ====================

    /**
     * 时间窗构建。
     *
     * <p><b>空时间限制（自动推算天数）</b>：未显式指定 days（或开启 autoDays）时，先按「每日时段容量」
     * 反推需要排多少天，再据此生成 dayConfigs —— 让「每天安排的时间」决定「能排多少天」，而非写死天数。
     * 每日模板取 dayConfigs[0] 的时段（各天时段本可不同，自动模式下统一按首日时段复制）。</p>
     *
     * <p><b>时间三态</b>（与球类 {@code daysLimit} 契约保持一致）：
     * days &gt;= 1 硬约束就排这么多天 · days == 0 不限（自动推算）· days == -1 尽可能减少
     * （自动推算 + 放大跨天惩罚，逼求解器把工期压紧）。
     * 原实现是 {@code intVal(days, 0) <= 0}，把 0 与负数混为一谈，于是「尽可能减少」在这条主链路上
     * 根本没有表达方式（球类有、主田径没有）。</p>
     */
    private void buildWindows(AutoScheduleContext c) {
        Map<String, Object> cfg = c.cfg;
        int rawDays = intVal(cfg.get("days"), Integer.MIN_VALUE);
        c.minimizeDays = rawDays < 0;
        c.autoDays = Boolean.TRUE.equals(cfg.get("autoDays")) || rawDays == 0;
        c.estimatedDays = 0;
        if (c.autoDays || c.minimizeDays) {
            List<Map<String, Object>> dayConfigs = castList(cfg.get("dayConfigs"));
            List<Map<String, Object>> template = dayConfigs.isEmpty()
                    ? List.of(defaultAutoDayConfig()) : dayConfigs.subList(0, 1);
            Map<String, Object> oneDay = new LinkedHashMap<>(cfg);
            oneDay.put("dayConfigs", template);
            int dailyMinutes = buildComponent.buildWindows(oneDay).stream()
                    .mapToInt(w -> w.capacity).sum();
            if (dailyMinutes <= 0) {
                dailyMinutes = 30 * 60;   // 兜底：半小时级单日容量
            }
            c.estimatedDays = DaysEstimator.estimateDays(c.units, dailyMinutes, c.trackSlots, c.fieldSlots, c.unitInterval);
            cfg.put("dayConfigs", buildAutoDayConfigs(cfg, c.estimatedDays, template));
            cfg.put("days", c.estimatedDays);
            log.info("{}：按每日 {} 分钟自动推算需 {} 天",
                    c.minimizeDays ? "时间目标=尽可能减少(压缩工期)" : "时间限制=不限",
                    dailyMinutes, c.estimatedDays);
        }

        // 时间窗：按 (天, 时段) 顺序铺开
        c.windows = buildComponent.buildWindows(cfg);
        if (c.windows.isEmpty()) {
            throw new RuntimeException("运动会日程未配置可用时段，请先在「系统设置 → 运动会日程」中配置日期与时段");
        }
    }

    /** 日期按 startDate 顺延；各天时段统一采用模板（自动模式下不区分各天差异）。 */
    private List<Map<String, Object>> buildAutoDayConfigs(Map<String, Object> cfg, int days,
                                                          List<Map<String, Object>> template) {
        String startDate = str(cfg.get("startDate"), null);
        List<Map<String, Object>> slots = template.isEmpty()
                ? List.of()
                : castList(template.get(0).get("slots"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int d = 1; d <= days; d++) {
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("day", d);
            if (startDate != null && !startDate.isBlank()) {
                dc.put("date", shiftDate(startDate, d - 1));
            }
            dc.put("slots", slots);
            out.add(dc);
        }
        return out;
    }

    /** 自动天数模式在「完全未配置时段」时的兜底模板：上午 + 下午两个时段 */
    private Map<String, Object> defaultAutoDayConfig() {
        List<Map<String, Object>> slots = new ArrayList<>();
        slots.add(autoSlot("AM", "上午", "08:00", "11:30"));
        slots.add(autoSlot("PM", "下午", "14:00", "17:30"));
        Map<String, Object> dc = new LinkedHashMap<>();
        dc.put("day", 1);
        dc.put("slots", slots);
        return dc;
    }

    private Map<String, Object> autoSlot(String key, String name, String start, String end) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("name", name);
        s.put("start", start);
        s.put("end", end);
        return s;
    }

    // ==================== ⑤ 行政时间保护 ====================

    /**
     * 行政时间保护（规避时间，对编排率先影响）：
     * GLOBAL 避让时段 → 切分时间窗（硬约束：整段不可排任何项目）；
     * TEACHER 个人时段 → 展开为「项目 id → 受保护区间」黑名单，放置时按项目阻挡。
     */
    private void applyTimeProtections(AutoScheduleContext c) {
        c.globalBlocks = protectionService.globalBlocks();
        if (!c.globalBlocks.isEmpty()) {
            c.windows = ProtectionMath.splitWindows(c.windows, c.globalBlocks);
            log.info("行政时间保护：{} 个全校避让时段已切分时间窗", c.globalBlocks.size());
        }
        c.eventBlocked = teacherEventBlocksToIntervals(protectionService.teacherEventBlocks());
        if (!c.eventBlocked.isEmpty()) {
            log.info("行政时间保护：{} 个项目受班主任个人时段保护", c.eventBlocked.size());
        }
    }

    /** 把「项目 id → 保护列表」展开为「项目 id → 受保护区间」，区间为 {day(-1=全天), startMin, endMin}。 */
    private Map<Long, List<int[]>> teacherEventBlocksToIntervals(Map<Long, List<AdminTimeProtection>> blocks) {
        Map<Long, List<int[]>> out = new LinkedHashMap<>();
        for (Map.Entry<Long, List<AdminTimeProtection>> e : blocks.entrySet()) {
            List<int[]> iv = new ArrayList<>();
            for (AdminTimeProtection p : e.getValue()) {
                int s = ProtectionMath.toMin(p.getStartTime());
                int en = ProtectionMath.toMin(p.getEndTime());
                if (s < 0 || en < 0 || en <= s) {
                    continue;
                }
                iv.add(new int[]{p.getDay() == null ? -1 : p.getDay(), s, en});
            }
            if (!iv.isEmpty()) {
                out.put(e.getKey(), iv);
            }
        }
        return out;
    }

    // ==================== ⑥ 可行性预检 ====================

    /**
     * B05/U06：时间窗容量可行性预检 + 压缩亏损量化。
     *
     * <p>只告警不量化，现场无法判断「到底差多少分钟、差在哪个项目」。这里在放置之前先算清楚：
     * 本项目类别真正需要多少分钟、时段总共能提供多少分钟、缺口多少，并把每一个被压缩的项目列成表。</p>
     *
     * <p>U24/B21：先按容量等比适配时长，再进入放置；compressionReport 放到放置之后统计，
     * 这样「就近缩短」的那部分也如实计入（报告反映的是真正落地的时长）。</p>
     */
    private void assessFeasibility(AutoScheduleContext c) {
        c.feasibility = ScheduleAnalysisMath.assessFeasibility(
                c.units, c.windows, c.trackSlots, c.fieldSlots, c.unitInterval);
        ScheduleAnalysisMath.fitDurationsToPools(c.units, c.feasibility, c.unitInterval);
    }

    // ==================== ⑦ 并行分组 ====================

    /**
     * 并行分组合并。
     *
     * <p>项目自带的「并行捆绑组」（表格2 的字母列）<b>优先于</b>配置页分组：同字母 → 同组 →
     * 安排在同一时段并行；为空则不受限制，由算法自动安排。</p>
     *
     * <p>小组合作项目归入同一「合作组」，与同年级同组项目合并到同一时段并行进行
     * （径赛/田赛皆可，含趣味；真正并行的还是各自场地池，这里只负责「同时开赛」协调）。</p>
     */
    private void assignParallelGroups(AutoScheduleContext c) {
        for (Unit u : c.units) {
            String bg = u.event.getBundleGroup();
            if (bg != null && !bg.isBlank()) {
                c.event2Group.put(u.event.getId(), "捆绑组" + bg.trim().toUpperCase());
            }
        }
        for (Unit u : c.units) {
            if (Boolean.TRUE.equals(u.event.getCooperative())) {
                c.event2Group.put(u.event.getId(), "合作组");
            }
        }
    }

    /** 供诊断/日志用的一行摘要（进度与结果展示的公共口径）。 */
    public static String describe(AutoScheduleContext c) {
        return String.format("%d 个单元 / %d 个时间窗 / 径赛 %d 位并发 / 田赛 %d 位并发 / 场地 %d 个%s",
                c.units == null ? 0 : c.units.size(), c.windows == null ? 0 : c.windows.size(),
                c.trackSlots, c.fieldSlots, c.venues == null ? 0 : c.venues.size(),
                c.hasType ? "（按类型定位主场地/田赛池）" : "（无类型信息，按顺序回退）");
    }
}
