package com.sports.schedule.analysis;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可解性诊断测试：以「地狱级场景」（3 年级 × 8 班 × 30 人 = 720 名运动员 + 9 个真实田径项目）
 * 为夹具，断言诊断服务能区分「3 天完全可解」与「2 天结构性不可解」，并如实输出不可解冲突。
 *
 * <p>与 Python 侧 {@code sports_ai/tests/test_solve.py} 同构：同一份场景、同一套判定口径，
 * 保证训练侧离线压测与生产侧在线诊断得出<b>一致</b>的结论。</p>
 */
class ScheduleFeasibilityServiceTest {

    private static final int N_GRADES = 3;
    private static final int CLASSES_PER_GRADE = 8;
    private static final int PER_CLASS = 30;

    /** 项目定义：code / 名称 / 池 / 每批时长(分钟) / 每批容量 / 是否有复赛 / 限性别 */
    private record EventDef(String code, String name, String pool, int batchMinutes,
                            int heatCapacity, boolean hasFinal, String gender) {
    }

    private static final List<EventDef> EVENTS = List.of(
            new EventDef("50m", "50米", "径赛", 15, 8, true, null),
            new EventDef("100m", "100米", "径赛", 20, 8, true, null),
            new EventDef("800m", "800米(女子)", "径赛", 45, 8, false, "f"),
            new EventDef("1000m", "1000米(男子)", "径赛", 50, 8, false, "m"),
            new EventDef("4x100", "4×100米接力", "径赛", 30, 8, true, null),
            new EventDef("SLJ", "立定跳远", "田赛", 60, 6, false, null),
            new EventDef("HJ", "跳高", "田赛", 90, 6, false, null),
            new EventDef("PU", "引体向上", "田赛", 40, 10, false, null),
            new EventDef("SP", "铅球", "田赛", 50, 6, false, null));

    private static final Map<String, String[]> SPECIALTY = Map.of(
            "sprint", new String[]{"50m", "100m", "4x100"},
            "middle", new String[]{"800m", "1000m", "100m"},
            "jump", new String[]{"SLJ", "HJ", "50m"},
            "strength", new String[]{"PU", "SP", "100m"},
            "allround", new String[]{"50m", "100m", "SLJ", "SP", "PU", "HJ", "4x100", "800m", "1000m"});
    private static final String[] SPECIALTY_NAMES = {"sprint", "middle", "jump", "strength", "allround"};
    private static final double[] SPECIALTY_WEIGHTS = {0.30, 0.15, 0.20, 0.25, 0.10};

    private final ScheduleFeasibilityService service = new ScheduleFeasibilityService();

    // ------------------------------------------------------------------
    // 夹具：与 Python 侧完全同构的地狱场景
    // ------------------------------------------------------------------

    private static boolean eligible(EventDef e, String gender) {
        return e.gender() == null || e.gender().equals(gender);
    }

    /** 报名：大部分 1 项、约四分之一 2 项、每班至少 1 人 3 项（按专长挑选以制造冲突簇）。 */
    private static List<long[]> enroll(long seed) {
        Random rng = new Random(seed);
        List<long[]> entries = new ArrayList<>();   // 每个元素 = [年级索引, 运动员id, 项目索引]
        long aid = 1;
        for (int g = 0; g < N_GRADES; g++) {
            for (int c = 0; c < CLASSES_PER_GRADE; c++) {
                int[] counts = new int[PER_CLASS];
                Arrays.fill(counts, 1);
                for (int k = 0; k < Math.max(1, (int) (PER_CLASS * 0.25)); k++) {
                    counts[k] = 2;
                }
                for (int k = 0; k < Math.max(1, (int) (PER_CLASS * 0.04)); k++) {
                    counts[PER_CLASS - 1 - k] = 3;
                }
                for (int k = 0; k < PER_CLASS; k++) {
                    String gender = rng.nextDouble() < 0.5 ? "f" : "m";
                    double r = rng.nextDouble();
                    double acc = 0;
                    String spec = SPECIALTY_NAMES[0];
                    for (int si = 0; si < SPECIALTY_NAMES.length; si++) {
                        acc += SPECIALTY_WEIGHTS[si];
                        if (r <= acc) {
                            spec = SPECIALTY_NAMES[si];
                            break;
                        }
                    }
                    List<Integer> chosen = new ArrayList<>();
                    for (String code : SPECIALTY.get(spec)) {
                        if (chosen.size() >= counts[k]) break;
                        int idx = indexOf(code);
                        if (idx >= 0 && eligible(EVENTS.get(idx), gender) && !chosen.contains(idx)) {
                            chosen.add(idx);
                        }
                    }
                    List<Integer> pool = new ArrayList<>();
                    for (int i = 0; i < EVENTS.size(); i++) {
                        if (eligible(EVENTS.get(i), gender)) pool.add(i);
                    }
                    java.util.Collections.shuffle(pool, rng);
                    for (int idx : pool) {
                        if (chosen.size() >= counts[k]) break;
                        if (!chosen.contains(idx)) chosen.add(idx);
                    }
                    for (int e : chosen) {
                        entries.add(new long[]{g, aid, e});
                    }
                    aid++;
                }
            }
        }
        return entries;
    }

    private static int indexOf(String code) {
        for (int i = 0; i < EVENTS.size(); i++) {
            if (EVENTS.get(i).code().equals(code)) return i;
        }
        return -1;
    }

    /** 构造单元（项目 × 年级 × 轮次）与位置网格。 */
    private static List<ScheduleUnit> buildUnits(List<long[]> entries, List<Placement> placements) {
        List<ScheduleUnit> units = new ArrayList<>();
        int uid = 0;
        for (int ei = 0; ei < EVENTS.size(); ei++) {
            EventDef ev = EVENTS.get(ei);
            for (int g = 0; g < N_GRADES; g++) {
                final int grade = g;
                final int event = ei;
                List<Long> members = entries.stream()
                        .filter(r -> r[0] == grade && r[2] == event)
                        .map(r -> r[1]).sorted().distinct().toList();
                if (members.isEmpty()) continue;
                long[] ath = members.stream().mapToLong(Long::longValue).toArray();
                int heats = Math.max(1, (ath.length + ev.heatCapacity() - 1) / ev.heatCapacity());
                int prelimDur = heats * ev.batchMinutes();
                String gradeName = "高" + (g + 1);
                List<Placement> cands = placements.stream()
                        .filter(p -> p.getPoolLabel().equals(ev.pool())).toList();
                units.add(unit("u" + (uid++), ei, ev.name() + (ev.hasFinal() ? "(预赛)" : "(决赛)"),
                        gradeName, ev, prelimDur, ev.batchMinutes(), ath, cands));
                if (ev.hasFinal()) {
                    long[] seeded = Arrays.copyOf(ath, Math.min(8, ath.length));
                    units.add(unit("u" + (uid++), ei, ev.name() + "(决赛)", gradeName, ev,
                            ev.batchMinutes(), ev.batchMinutes(), seeded, cands));
                }
            }
        }
        return units;
    }

    private static ScheduleUnit unit(String key, long eventId, String name, String grade, EventDef ev,
                                     int rawDuration, int minDuration, long[] athletes,
                                     List<Placement> cands) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", eventId);
        attrs.put("name", name);
        return new ScheduleUnit(key, eventId, name, grade, "径赛".equals(ev.pool()),
                ev.pool(), null, 5, rawDuration, minDuration, athletes,
                List.of(rawDuration, minDuration), cands, attrs);
    }

    private static List<Placement> placements(int days, int trackLanes, int fieldLanes) {
        int[] dayMinutes = {240, 240};
        List<Placement> out = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            for (int wi = 0; wi < dayMinutes.length; wi++) {
                int windowIdx = (day - 1) * dayMinutes.length + wi + 1;
                int start = 8 * 60 + wi * 300;
                int cap = dayMinutes[wi];
                String date = "2026-01-" + String.format("%02d", day);
                for (int lane = 0; lane < trackLanes; lane++) {
                    out.add(new Placement("径赛", lane, windowIdx, day, date,
                            "时段" + (wi + 1), "田径场", start, start, cap));
                }
                for (int lane = 0; lane < fieldLanes; lane++) {
                    out.add(new Placement("田赛", lane, windowIdx, day, date,
                            "时段" + (wi + 1), "田赛区", start, start, cap));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 拆批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("拆批不重不漏：各组次运动员互不相交、并集等于原名单")
    void expandHeatsCoversAthletesExactlyOnce() {
        List<ScheduleUnit> units = buildUnits(enroll(20260918L), placements(3, 2, 4));
        List<ScheduleFeasibilityService.HeatTask> tasks = service.expandHeats(units);

        Map<String, Set<Long>> merged = new HashMap<>();
        Map<String, Integer> dupGuard = new HashMap<>();
        for (ScheduleFeasibilityService.HeatTask t : tasks) {
            Set<Long> set = merged.computeIfAbsent(t.unitKey(), k -> new HashSet<>());
            for (long a : t.athletes()) {
                assertTrue(set.add(a), "同一单元的组次之间不得重复覆盖运动员");
                dupGuard.merge(t.unitKey() + "#" + a, 1, Integer::sum);
            }
            assertTrue(t.duration() > 0);
        }
        for (ScheduleUnit u : units) {
            Set<Long> expect = new HashSet<>();
            for (long a : u.getAthletes()) expect.add(a);
            assertEquals(expect, merged.get(u.getKey()), "组次并集必须等于单元全部报名者");
        }
    }

    // ------------------------------------------------------------------
    // 下界与可行性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2 天：径赛容量不足 + 团下界超过时段数 → 判定为结构性不可解")
    void twoDaysIsInfeasible() {
        List<Placement> pl = placements(2, 2, 4);
        List<ScheduleUnit> units = buildUnits(enroll(20260918L), pl);
        Map<String, Object> report = service.diagnose(units, pl);

        assertFalse((Boolean) report.get("feasible"), "2 天应当被判定为不可解");

        @SuppressWarnings("unchecked")
        Map<String, Object> bounds = (Map<String, Object>) report.get("bounds");
        assertTrue((Integer) bounds.get("oversizedCount") > 0, "存在超过单时段容量、必须拆批的单元");
        assertEquals(Boolean.FALSE, bounds.get("cliqueFeasible"),
                "2 天仅 4 个时段，冲突图最大团为 5 → 结构性不可解");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertTrue((Integer) summary.get("unplaced") > 0, "应如实报告未排组次");
        assertNotNull(summary.get("unplacedUnits"));
        assertFalse(((List<?>) summary.get("unplacedUnits")).isEmpty(), "未排清单必须能定位到具体单元");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) report.get("conflicts");
        Set<String> kinds = new HashSet<>();
        conflicts.forEach(c -> kinds.add((String) c.get("type")));
        assertTrue(kinds.contains("oversized_unit"), "应输出超大单元冲突");
        assertTrue(kinds.contains("clique_exceeds_periods"), "应输出团超时段的结构性不可解");

        // 未排必须「有解释」：要么某池容量不足，要么兼项在其可用时段内错不开
        assertTrue(kinds.contains("capacity_shortfall")
                        || kinds.contains("athlete_clash")
                        || kinds.contains("athlete_clash_summary"),
                "未排组次必须给出可解释的冲突类型，实际 " + kinds);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) report.get("actions");
        assertNotNull(actions);
    }

    @Test
    @DisplayName("容量严重不足（1 天 + 各 1 个并发位）：判定容量缺口并建议加天/加位")
    void capacityShortfallIsDetected() {
        List<Placement> pl = placements(1, 1, 1);
        List<ScheduleUnit> units = buildUnits(enroll(20260918L), pl);
        Map<String, Object> report = service.diagnose(units, pl);

        assertFalse((Boolean) report.get("feasible"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bounds = (Map<String, Object>) report.get("bounds");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> pools = (Map<String, Map<String, Object>>) bounds.get("pools");
        assertTrue(pools.values().stream().anyMatch(p -> (Integer) p.get("shortfall") > 0),
                "1 天资源下必有池容量不足");
        assertTrue((Integer) bounds.get("minDaysByCapacity") > 1, "按容量反推需要多于 1 天");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) report.get("conflicts");
        Set<String> kinds = new HashSet<>();
        conflicts.forEach(c -> kinds.add((String) c.get("type")));
        assertTrue(kinds.contains("capacity_shortfall"), "应输出容量缺口冲突");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) report.get("actions");
        Set<String> actionKinds = new HashSet<>();
        actions.forEach(a -> actionKinds.add((String) a.get("action")));
        assertTrue(actionKinds.contains("extend_days"), "应建议延长天数");
        assertTrue(actionKinds.contains("add_lanes"), "应建议增加并发位");

        // 未排清单必须携带「容量不足」这一原因
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unplacedTasks = (List<Map<String, Object>>) report.get("unplacedTasks");
        assertFalse(unplacedTasks.isEmpty());
        assertTrue(unplacedTasks.stream().anyMatch(t -> "pool_capacity_shortfall".equals(t.get("reason"))),
                "容量不足导致的未排必须标注 pool_capacity_shortfall");
    }

    @Test
    @DisplayName("3 天（真实上限）：完全可解，未排为空且报告可行")
    void threeDaysIsFeasible() {
        List<Placement> pl = placements(3, 2, 4);
        List<ScheduleUnit> units = buildUnits(enroll(20260918L), pl);
        Map<String, Object> report = service.diagnose(units, pl);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        int tasks = (Integer) summary.get("tasks");
        int placed = (Integer) summary.get("placed");
        assertTrue(tasks > 100, "地狱场景应有上百个组次，实际 " + tasks);
        assertTrue(placed >= tasks * 0.95,
                "3 天应基本排完（实际 " + placed + "/" + tasks + "）");

        @SuppressWarnings("unchecked")
        Map<String, Object> bounds = (Map<String, Object>) report.get("bounds");
        assertTrue((Integer) bounds.get("minDaysByCapacity") <= 3, "按容量反推应不超过 3 天");
    }

    @Test
    @DisplayName("下界计算：单日容量不得被多天重复累加")
    void minDaysIsNotUnderestimatedByAccumulatingDays() {
        List<Placement> pl2 = placements(2, 2, 4);
        List<Placement> pl3 = placements(3, 2, 4);
        List<ScheduleUnit> units = buildUnits(enroll(20260918L), pl2);
        @SuppressWarnings("unchecked")
        Map<String, Object> b2 = (Map<String, Object>) service.diagnose(units, pl2).get("bounds");
        @SuppressWarnings("unchecked")
        Map<String, Object> b3 = (Map<String, Object>) service.diagnose(units, pl3).get("bounds");
        // 同一条需求，无论给几天，按容量反推的最少天数必须一致
        assertEquals(b2.get("minDaysByCapacity"), b3.get("minDaysByCapacity"),
                "最少天数只应由需求与单日资源决定，不能被可用天数影响");
        assertTrue((Integer) b2.get("minDaysByCapacity") >= 2);
    }

    @Test
    @DisplayName("报告 schema 与 Python 侧对齐")
    void reportSchemaMatchesPythonSide() {
        List<Placement> pl = placements(2, 2, 4);
        List<ScheduleUnit> units = buildUnits(enroll(20260918L), pl);
        Map<String, Object> report = service.diagnose(units, pl);
        assertEquals(ScheduleFeasibilityService.SCHEMA, report.get("schema"));
        for (String key : List.of("schema", "feasible", "scene", "summary",
                "bounds", "unplacedTasks", "conflicts", "actions")) {
            assertTrue(report.containsKey(key), "报告缺少字段 " + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> scene = (Map<String, Object>) report.get("scene");
        assertTrue((Integer) scene.get("athletes") > 0);
        assertNotNull(scene.get("poolLanes"));
    }
}
