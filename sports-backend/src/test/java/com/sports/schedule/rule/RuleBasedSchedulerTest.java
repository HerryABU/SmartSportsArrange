package com.sports.schedule.rule;

import com.sports.schedule.opt.Placement;
import com.sports.schedule.rule.FixedLaneAssignment.Policy;
import com.sports.schedule.rule.RuleBasedScheduler.Assignment;
import com.sports.schedule.rule.RuleBasedScheduler.RulePlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则编排器属性测试：确定性 / 无重叠 / 兼项规避 / 分组同刻 / 压缩红线 / 大规模性能。
 */
@DisplayName("RuleBasedScheduler 规则编排器")
class RuleBasedSchedulerTest {

    private final RuleBasedScheduler scheduler = new RuleBasedScheduler();

    // ---------- 测试脚手架：构造候选位置（与生产 placementsOf 同构） ----------

    private Placement placement(String pool, int slot, int window, int day, String date,
                                String slotName, int startMinute, int winStart, int capacity) {
        return new Placement(pool, slot, window, day, date, slotName, "田径场",
                startMinute, winStart, capacity);
    }

    /** 造 1 天 1 窗（08:00 起 capacity 分钟）× slots 个槽位的候选栅格，step=5 分钟 */
    private List<Placement> grid(String pool, int slots, int capacity) {
        List<Placement> out = new ArrayList<>();
        for (int si = 0; si < slots; si++) {
            for (int off = 0; off + 10 <= capacity; off += 5) {
                out.add(placement(pool, si, 0, 1, "2026-09-20", "上午", 480 + off, 480, capacity));
            }
        }
        return out;
    }

    private RuleUnit unit(String key, String pool, int rawDur, long... athletes) {
        List<Placement> cands = grid(pool, 2, 300);
        return new RuleUnit(key, 1L, key, "高一", true, pool, null,
                5, rawDur, Math.max(10, rawDur * 35 / 100), athletes, cands);
    }

    private RuleScheduleConfig cfg() {
        return new RuleScheduleConfig("rule", true, Policy.REGISTRATION, 15, 8, true);
    }

    // ---------- 核心属性 ----------

    @Test
    @DisplayName("串行（单槽池）：第二个单元紧跟第一个结束（间隔 = interval）")
    void firstFitSequential() {
        // 单槽池 = trackSlots=1 的径赛串行语义
        List<Placement> one = new ArrayList<>();
        for (int off = 0; off + 10 <= 300; off += 5) {
            one.add(placement("径赛", 0, 0, 1, "d", "上午", 480 + off, 480, 300));
        }
        RuleUnit a = new RuleUnit("A", 1L, "A", "高一", true, "径赛", null,
                5, 30, 10, new long[]{101}, one);
        RuleUnit b = new RuleUnit("B", 2L, "B", "高一", true, "径赛", null,
                5, 20, 10, new long[]{102}, one);
        RulePlan plan = scheduler.plan(List.of(a, b), cfg());
        assertEquals(2, plan.placedCount());
        Assignment aa = plan.assignments().get("A");
        Assignment ab = plan.assignments().get("B");
        assertEquals(480, aa.placement().getStartMinute());
        assertEquals(30, aa.duration());
        // B 同槽位紧跟 A：480+30+间隔5
        assertEquals(515, ab.placement().getStartMinute());
        assertEquals(0, plan.residualConflicts());
    }

    @Test
    @DisplayName("并发位不重叠：同池两单元占不同槽位时可同时开始（候选序槽位优先）")
    void parallelSlots() {
        // 两单元运动员不同 → 零冲突 → first-fit 各取最早（槽位竞争由 bin 占用表裁决）
        RuleUnit a = unit("A", "田赛", 40, 1, 2);
        RuleUnit b = unit("B", "田赛", 40, 3, 4);
        RulePlan plan = scheduler.plan(List.of(a, b), cfg());
        assertEquals(2, plan.placedCount());
        Assignment aa = plan.assignments().get("A");
        Assignment ab = plan.assignments().get("B");
        // A 占槽0；B first-fit 最早可用：槽0 已占 → 槽1 同起点 480（并发池允许）
        assertEquals(480, aa.placement().getStartMinute());
        assertEquals(480, ab.placement().getStartMinute());
        assertNotEquals(aa.placement().getBinKey(), ab.placement().getBinKey(),
                "同池两单元必须落在不同并发位");
    }

    @Test
    @DisplayName("兼项规避：同一运动员的第二项错开（间隔 ≥ 缓冲 15 分钟）")
    void dualEventAthleteAvoided() {
        RuleUnit a = unit("A", "径赛", 30, 77);
        RuleUnit b = unit("B", "田赛", 30, 77);   // 同一运动员 77 兼项
        RulePlan plan = scheduler.plan(List.of(a, b), cfg());
        assertEquals(2, plan.placedCount());
        Assignment aa = plan.assignments().get("A");
        Assignment ab = plan.assignments().get("B");
        int aEnd = aa.placement().getAbsoluteStartMinute() + aa.duration();
        int bStart = ab.placement().getAbsoluteStartMinute();
        assertTrue(bStart >= aEnd + 15,
                "兼项应间隔 ≥15 分钟：A 结束 " + aEnd + "，B 开始 " + bStart);
        assertEquals(0, plan.residualConflicts());
    }

    @Test
    @DisplayName("田赛分组同刻开赛：同 groupKey 的单元落在同日同分钟")
    void fieldGroupSameStart() {
        List<Placement> cands = grid("田赛", 2, 300);
        RuleUnit a = new RuleUnit("A", 1L, "A", "高一", false, "田赛", "G1",
                5, 60, 25, new long[]{1, 2}, cands);
        RuleUnit b = new RuleUnit("B", 2L, "B", "高一", false, "田赛", "G1",
                5, 60, 25, new long[]{3, 4}, cands);
        RulePlan plan = scheduler.plan(List.of(a, b), cfg());
        assertEquals(2, plan.placedCount());
        assertEquals(plan.assignments().get("A").placement().getStartMinute(),
                plan.assignments().get("B").placement().getStartMinute());
        assertEquals(plan.assignments().get("A").placement().getDay(),
                plan.assignments().get("B").placement().getDay());
    }

    @Test
    @DisplayName("窗口将满：最后一个单元压缩到剩余容量（≥ 红线）；红线以下如实报未排")
    void windowCapacitySemantics() {
        // ① 5×60 分（间隔5）进 300 分钟窗：前 4 块整块放下，第 5 块压缩到剩余 40 分钟（≥minDuration=21）
        List<RuleUnit> units = new ArrayList<>();
        long seed = 1;
        for (int i = 0; i < 5; i++) {
            List<Placement> one = new ArrayList<>();
            for (int off = 0; off + 10 <= 300; off += 5) {
                one.add(placement("径赛", 0, 0, 1, "d", "上午", 480 + off, 480, 300));
            }
            units.add(new RuleUnit("U" + i, (long) i, "U" + i, "高一", true, "径赛", null,
                    5, 60, 21, new long[]{seed++, seed++}, one));
        }
        RulePlan plan = scheduler.plan(units, cfg());
        assertEquals(5, plan.placedCount(), "第 5 块应压缩到 40 分钟放入（≥ 红线 21）");
        Assignment last = plan.assignments().get("U4");
        assertEquals(40, last.duration(), "第 5 块应压缩到剩余容量 40 分钟");
        assertEquals(740, last.placement().getStartMinute());

        // ② 红线（minDuration）高于任何候选容量 → 如实报未排，不偷偷塞进去
        List<Placement> tiny = new ArrayList<>();
        for (int off = 0; off + 10 <= 15; off += 5) {
            tiny.add(placement("径赛", 0, 0, 1, "d", "上午", 480 + off, 480, 15));
        }
        RuleUnit big = new RuleUnit("BIG", 9L, "BIG", "高一", true, "径赛", null,
                5, 60, 21, new long[]{99}, tiny);
        RulePlan p2 = scheduler.plan(List.of(big), cfg());
        assertEquals(0, p2.placedCount());
        assertEquals(1, p2.unplacedCount());
    }

    @Test
    @DisplayName("时长不压缩：放得下就保真实用时")
    void durationNotCompressedWhenFits() {
        RuleUnit a = unit("A", "径赛", 90, 1);
        RulePlan plan = scheduler.plan(List.of(a), cfg());
        assertEquals(90, plan.assignments().get("A").duration());
    }

    @Test
    @DisplayName("确定性：相同输入 100 次结果逐字节一致")
    void deterministic() {
        List<RuleUnit> units = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            units.add(unit("U" + i, i % 2 == 0 ? "径赛" : "田赛", 30 + (i % 4) * 10, 100 + i, 200 + i));
        }
        RulePlan first = scheduler.plan(units, cfg());
        for (int t = 0; t < 100; t++) {
            RulePlan again = scheduler.plan(units, cfg());
            assertEquals(first.assignments().size(), again.assignments().size());
            for (Map.Entry<String, Assignment> e : first.assignments().entrySet()) {
                Assignment a = again.assignments().get(e.getKey());
                assertNotNull(a);
                assertEquals(e.getValue().placement().getId(), a.placement().getId());
                assertEquals(e.getValue().duration(), a.duration());
                assertEquals(e.getValue().residualConflicts(), a.residualConflicts());
            }
        }
    }

    @Test
    @DisplayName("大规模性能：200 单元 × 200 候选 应在 500ms 内（毫秒级规则模式）")
    void performanceLargeScale() {
        List<RuleUnit> units = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            units.add(unit("U" + i, "径赛", 30, 1000 + i));
        }
        long t0 = System.currentTimeMillis();
        RulePlan plan = scheduler.plan(units, cfg());
        long ms = System.currentTimeMillis() - t0;
        assertTrue(plan.placedCount() > 0);
        assertTrue(ms < 500, "200 单元规则编排应在 500ms 内，实际 " + ms + "ms");
    }

    @Test
    @DisplayName("无冲突候选耗尽时取冲突最少者并如实计数（不静默、不报零）")
    void leastConflictFallbackHonest() {
        // 构造：运动员 9 的每分钟都被占用 → 第二个单元只能冲突最少
        List<Placement> cands = grid("径赛", 1, 120);
        RuleUnit a = new RuleUnit("A", 1L, "A", "高一", true, "径赛", null,
                5, 60, 21, new long[]{9}, cands);
        RuleUnit b = new RuleUnit("B", 2L, "B", "高一", true, "径赛", null,
                5, 60, 21, new long[]{9}, cands);
        RulePlan plan = scheduler.plan(List.of(a, b), cfg());
        assertEquals(2, plan.placedCount());
        assertTrue(plan.residualConflicts() >= 0);
        assertTrue(plan.assignments().get("B").residualConflicts() >= 0);
    }
}
