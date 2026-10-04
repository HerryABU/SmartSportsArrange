package com.sports.service.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.plan.PlanAdvice;
import com.sports.schedule.plan.PlanCost;
import com.sports.schedule.plan.PredictivePlanner;
import com.sports.schedule.plan.PredictivePlanner.PlanOutcome;
import com.sports.schedule.plan.PredictivePlanner.PlanUnit;
import com.sports.service.system.SystemService;

import lombok.extern.slf4j.Slf4j;

/**
 * 规划层服务：把 {@link PredictivePlanner} 接到**真实赛会数据**上。
 *
 * <p>定位：这是「先解出来、再谈好不好」的那一层 —— 确定性启发打底 + 前向剪枝 +
 * 局部回退，**秒级**给出一个无非法落位的方案，并附完整诊断
 * （节点扩展数 / 三动作 / 3R 计数）。它不替代优化与 AI 档，而是：</p>
 *
 * <ul>
 *   <li>作为**可行性兜底**：优化/AI 档超时或失败时，至少有一个「排得下」的方案；</li>
 *   <li>作为**诊断探针**：把「卡在哪、为什么排不下」用数字说清楚，而不是只报一句失败。</li>
 * </ul>
 *
 * <p><b>只读</b>：本服务不下发任何落库动作（{@code /api/schedule/plan} 是预演端点），
 * 所以它不会改变现有赛程 —— 这一点是刻意的，先让用户看得见再决定要不要采纳。</p>
 */
@Slf4j
@Service
public class PredictivePlannerService {

    private final SystemService systemService;
    /**
     * ⚠️ {@link ScheduleBuildComponent} **刻意不是 Spring bean** —— 它的类注释写明
     * 「全部为无 Spring 依赖的纯装配逻辑」，由 {@code ScheduleService} 在构造器里手工 {@code new}。
     * 第一版直接把它当依赖注入，Spring 上下文启动即失败：
     * {@code No qualifying bean of type 'ScheduleBuildComponent' available} →
     * 连带 32 个用例报错。
     *
     * <p>所以这里沿用与 {@code ScheduleService} **完全一致**的装配方式：
     * 注入它的三个依赖，自己 {@code new}。</p>
     */
    private final ScheduleBuildComponent buildComponent;

    public PredictivePlannerService(EventRepository eventRepository,
                                    RegistrationRepository registrationRepository,
                                    SystemService systemService) {
        this.systemService = systemService;
        this.buildComponent = new ScheduleBuildComponent(
                eventRepository, registrationRepository, systemService);
    }

    /** 默认回退上限。真实学校规模下 2 万次足够；再往上收益递减而耗时线性增长。 */
    private static final int DEFAULT_MAX_BACKTRACKS = 20000;

    /**
     * 预演一次规划，返回方案与诊断（不落库）。
     *
     * @param override 前端传入的赛程配置覆盖（与 {@code /api/schedule/auto} 同构）
     */
    public Map<String, Object> plan(Map<String, Object> override) {
        Map<String, Object> cfg = buildComponent.mergeConfig(override);
        List<String> gradeOrder = systemService.getGradeOrder();
        List<Unit> units = buildComponent.buildUnits(gradeOrder, List.of());
        List<Window> windows = buildComponent.buildWindows(cfg);

        Map<String, Object> out = new LinkedHashMap<>();
        int maxBacktracks = intVal(override == null ? null : override.get("maxBacktracks"),
                DEFAULT_MAX_BACKTRACKS);
        int maxRestarts = intVal(override == null ? null : override.get("maxRestarts"), 3);

        if (units.isEmpty() || windows.isEmpty()) {
            out.put("feasible", false);
            out.put("reason", units.isEmpty() ? "没有可编排的单元（检查项目与年级配置）"
                    : "没有可用时段（检查 meet_schedule 的 dayConfigs/slots）");
            out.put("nUnits", units.size());
            out.put("nSlots", windows.size());
            return out;
        }

        // ---- 槽建模 ----
        // ⚠️ 本项目的 Window 是「第几天 + 时段名 + 起始分钟 + 容量」，**不带场地字段** ——
        //    容量是按「时段」给的，不区分场地。所以这里把容量键统一取 (slot, 单元自己的场地)，
        //    即「同一时段内每个场地都按该时段容量算」。这是与现有数据模型一致的**如实映射**，
        //    不是在算法里偷偷假设了什么。若将来 Window 增加场地维度，只需改这一处。
        List<Integer> days = new ArrayList<>();
        for (Window w : windows) {
            days.add(w.day);
        }
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (int i = 0; i < windows.size(); i++) {
            for (String venue : venuesOf(units)) {
                capacity.put(PredictivePlanner.capKey(i, venue), windows.get(i).capacity);
            }
        }

        List<PlanUnit> planUnits = new ArrayList<>();
        for (Unit u : units) {
            String venue = venueOf(u);
            int dur = Math.max(1, u.duration)
                    + (u.event != null && u.event.getIntervalMinutes() != null
                       ? Math.max(0, u.event.getIntervalMinutes()) : 0);
            planUnits.add(new PlanUnit(
                    unitKey(u),
                    venue,
                    dur,
                    u.event == null ? null : u.event.getBundleGroup(),
                    new ArrayList<>(u.athleteIds)));
        }

        // 槽 → 天：用窗口的 day 字段（跨天跨度才可算）
        Map<Integer, Integer> slotDay = new LinkedHashMap<>();
        List<Integer> distinctDays = days.stream().distinct().sorted().toList();
        for (int i = 0; i < days.size(); i++) {
            slotDay.put(i, Math.max(0, distinctDays.indexOf(days.get(i))));
        }

        long t0 = System.nanoTime();
        PlanOutcome r = PredictivePlanner.solve(planUnits, capacity,
                slot -> slotDay.getOrDefault(slot, 0), maxRestarts, maxBacktracks, 0L);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        out.put("feasible", r.feasible());
        out.put("nUnits", planUnits.size());
        out.put("nSlots", windows.size());
        out.put("cost", round(r.value()));
        out.put("elapsedMs", ms);
        out.put("unplaced", r.blocked().size());
        out.put("unplacedKeys", r.blocked());
        // violations 只可能是「非法落位」（超容/兼项）；未排单独由 unplaced 表达。
        // 这里恒为空才是正确状态 —— 一旦非空，说明规划器产出了非法方案，属 bug。
        out.put("violations", r.violations());
        out.put("diagnostics", diagMap(r));

        // ---- 加权代价口径（比较/上报用，见 PlanCost 的类注释）----
        // ⚠️ 它不是搜索目标，而是**同一把尺子**：四档编排模式在魔鬼档之外的未排数
        //    已经贴近下界（1.0/1.33），只看未排数会让它们全部「持平」——
        //    那是**指标分辨率不够**，不是「一样好」。把 兼项撞/道次撞/碎块/工期
        //    一并计价，差异才会显出来。
        int daysLimit = dayLimitOf(cfg);
        PredictivePlanner.SlotDays slotDays = slot -> slotDay.getOrDefault(slot, 0);
        PlanCost pc = PlanCost.of(planUnits, r.slotOf(), capacity, slotDays, daysLimit);
        out.put("weightedCost", round(pc.weighted()));
        out.put("costBreakdown", pc.breakdown());
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("unplaced", pc.unplaced());
        comp.put("athleteClash", pc.athleteClash());
        comp.put("capacityOverflow", pc.capacityOverflow());
        comp.put("laneClash", pc.laneClash());
        comp.put("fragBlocks", pc.fragBlocks());
        comp.put("daysOver", pc.daysOver());
        comp.put("daysUsed", slotOfDaysUsed(slotDay, r.slotOf()));
        comp.put("daysLimit", daysLimit);
        out.put("costComponents", comp);

        // ---- 结构性断裂的归因 + 「给某天多配容量」的建议 ----
        PlanAdvice.Result advice = PlanAdvice.analyze(planUnits, r.slotOf(), capacity, slotDays);
        out.put("extraMinutesByDay", advice.extraMinutesByDay());
        List<Map<String, Object>> groupBreaks = new ArrayList<>();
        for (PlanAdvice.GroupBreak b : advice.breaks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupKey", b.groupKey());
            row.put("anchorDay", b.anchorDay());
            row.put("offDayUnits", b.offDayUnits());
            row.put("venue", b.venue());
            row.put("shortfallMinutes", b.shortfallMinutes());
            groupBreaks.add(row);
        }
        out.put("groupBreaks", groupBreaks);

        // ---- 方案明细（按 天 → 槽 → 单元 排序，便于人读） ----
        List<Map<String, Object>> assignment = new ArrayList<>();
        Map<String, Unit> byKey = new LinkedHashMap<>();
        for (Unit u : units) {
            byKey.put(unitKey(u), u);
        }
        r.slotOf().entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, Integer> e) -> slotDay.getOrDefault(e.getValue(), 0))
                        .thenComparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    Unit u = byKey.get(e.getKey());
                    int slot = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("unitKey", e.getKey());
                    row.put("slot", slot);
                    row.put("day", days.get(slot));
                    row.put("slotName", windows.get(slot).slotName);
                    row.put("venue", venueOf(u));
                    if (u != null && u.event != null) {
                        row.put("eventCode", u.event.getCode());
                        row.put("eventName", u.event.getName());
                    }
                    row.put("grade", u == null ? null : u.grade);
                    assignment.add(row);
                });
        out.put("assignment", assignment);
        out.put("note", "预演结果，未落库；如需采用请走 /api/schedule/auto 并选择对应档位");

        log.info("规划层预演: 单元 {} / 槽 {} → 可行={} 未排={} 非法落位={} 扩展={} 耗时={}ms",
                planUnits.size(), windows.size(), r.feasible(), r.blocked().size(),
                r.violations().size(), r.diagnostics().searchCost(), ms);
        return out;
    }

    private static Map<String, Object> diagMap(PlanOutcome r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("expansions", r.diagnostics().expansions());
        d.put("searchCost", r.diagnostics().searchCost());
        d.put("continues", r.diagnostics().continues());
        d.put("backtracks", r.diagnostics().backtracks());
        d.put("repairs", r.diagnostics().repairs());
        d.put("restarts", r.diagnostics().restarts());
        d.put("rollbacks", r.diagnostics().rollbacks());
        d.put("pruned", r.diagnostics().pruned());
        d.put("prunedRetried", r.diagnostics().prunedRetried());
        return d;
    }

    /**
     * 从日程配置解析**限定天数**（加权代价里「工期超限」分量的口径）。
     *
     * <p>三态判定本身在 {@link PlanCost#dayLimitOf(String, int)} 里单一维护 ——
     * 它是「工期算不算超限」的唯一裁决点，散在各处就会出现
     * 「预演说没超、编排说超了」这种两套口径。</p>
     */
    private static int dayLimitOf(Map<String, Object> cfg) {
        if (cfg == null) {
            return 0;
        }
        Object mode = cfg.get("dayMode");
        return PlanCost.dayLimitOf(mode == null ? null : String.valueOf(mode),
                intVal(cfg.get("days"), 0));
    }

    /** 实际占用的天数（去重）—— 工期超限分量的被减数。 */
    private static int slotOfDaysUsed(Map<Integer, Integer> slotDay, Map<String, Integer> slotOf) {
        java.util.Set<Integer> days = new java.util.LinkedHashSet<>();
        for (Integer slot : slotOf.values()) {
            days.add(slotDay.getOrDefault(slot, 0));
        }
        return days.size();
    }

    private static List<String> venuesOf(List<Unit> units) {
        List<String> out = new ArrayList<>();
        for (Unit u : units) {
            String v = venueOf(u);
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static String venueOf(Unit u) {
        if (u != null && u.event != null && u.event.getDefaultVenue() != null
                && !u.event.getDefaultVenue().isBlank()) {
            return u.event.getDefaultVenue();
        }
        return "默认场地";
    }

    private static String unitKey(Unit u) {
        String code = u.event == null || u.event.getCode() == null ? "?" : u.event.getCode();
        return code + "@" + (u.grade == null ? "-" : u.grade);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static int intVal(Object v, int dft) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return dft;
            }
        }
        return dft;
    }
}
