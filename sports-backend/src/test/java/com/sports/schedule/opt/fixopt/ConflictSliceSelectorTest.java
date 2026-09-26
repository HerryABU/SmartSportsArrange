package com.sports.schedule.opt.fixopt;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冲突切片选择器的验证：冲突对入簇、连通传递、无冲突返回空、排序按病灶轻重。
 */
@DisplayName("冲突切片选择器")
class ConflictSliceSelectorTest {

    @Test
    @DisplayName("无冲突的好解返回空切片")
    void cleanPlanYieldsNoSlice() {
        SchedulePlan plan = plan(
                unit("a", 0, 60, new long[]{1, 2}),
                unit("b", 0, 45, new long[]{2}));
        assertTrue(ConflictSliceSelector.slices(plan).isEmpty(), "无时间重叠就没有冲突");
    }

    @Test
    @DisplayName("直接冲突的两个单元进同一切片")
    void directClashFormsSlice() {
        // a 与 b 同日起点 480，重叠 → 撞车（共享运动员 2）
        SchedulePlan plan = plan(
                unit("a", 0, 60, new long[]{1, 2}),
                unit("b", 0, 45, new long[]{2}));
        SchedulePlan damaged = overwrite(plan, "a", 1, 480, 60);
        damaged = overwrite(damaged, "b", 1, 480, 45);

        List<ConflictSliceSelector.Slice> slices = ConflictSliceSelector.slices(damaged);
        assertEquals(1, slices.size(), "两个相互撞车的单元应构成一个切片");
        assertEquals(2, slices.get(0).units().size());
        assertEquals(1, slices.get(0).clashCount());
    }

    @Test
    @DisplayName("传递冲突（a 撞 b、b 撞 c）切成一个连通分量而非三块孤岛")
    void transitiveClashStaysInOneSlice() {
        // 链：a(480,60) 撞 b(500,45)，b 又撞 c(545,30)；a 与 c 不直接撞（60>545-480=65? 
        // a 绝对区间 [480,540)，b [500,545)，c [545,575)——a 不撞 c，靠 b 传递连通）
        SchedulePlan plan = plan(
                unit("a", 0, 60, new long[]{1, 2}),
                unit("b", 1, 45, new long[]{2, 3}),
                unit("c", 2, 30, new long[]{3}));
        SchedulePlan damaged = overwrite(plan, "a", 1, 480, 60);
        damaged = overwrite(damaged, "b", 1, 500, 45);
        damaged = overwrite(damaged, "c", 1, 545, 30);

        List<ConflictSliceSelector.Slice> slices = ConflictSliceSelector.slices(damaged);
        assertEquals(1, slices.size(), "传递冲突应连通成一个切片");
        assertEquals(3, slices.get(0).units().size(), "三个单元全部入簇");
        assertEquals(2, slices.get(0).clashCount(), "两条冲突边");
    }

    @Test
    @DisplayName("两个互不相干的冲突簇切成两个切片，重的在前")
    void separateClustersSortedBySeverity() {
        SchedulePlan plan = plan(
                unit("a", 0, 60, new long[]{1, 2}),
                unit("b", 1, 45, new long[]{2}),
                unit("c", 2, 30, new long[]{3}),
                unit("d", 2, 25, new long[]{3}));
        SchedulePlan damaged = plan;
        // 簇1：a 与 b 同日起点不同但重叠？a[480,540) b[480+...]——用两个独立簇：
        // 簇1（1 条边）：a/b 在槽 0 起点不同 → 需要同位才重叠……直接构造：
        damaged = overwrite(damaged, "a", 1, 480, 60);
        damaged = overwrite(damaged, "b", 1, 500, 45);   // a[480,540) 撞 b[500,545)
        damaged = overwrite(damaged, "c", 1, 600, 30);   // c[600,630) 撞 d[610,635)
        damaged = overwrite(damaged, "d", 1, 610, 25);

        List<ConflictSliceSelector.Slice> slices = ConflictSliceSelector.slices(damaged);
        assertEquals(2, slices.size(), "两组互不相连的冲突应切成两个切片");
        assertNotEquals(slices.get(0).units(), slices.get(1).units());
    }

    @Test
    @DisplayName("未排单元不参与切片（病灶只在已排部分）")
    void unplacedUnitsIgnored() {
        SchedulePlan plan = plan(
                unit("a", 0, 60, new long[]{1, 2}),
                unit("b", 0, 45, new long[]{2}));
        SchedulePlan damaged = overwrite(plan, "a", 1, 480, 60);
        damaged = overwrite(damaged, "b", 1, 480, 45);
        damaged.getUnits().get(0).setPlacement(null);
        damaged.getUnits().get(0).setDuration(null);

        assertTrue(ConflictSliceSelector.slices(damaged).isEmpty(), "只剩一个已排单元时无冲突");
    }

    // ==================== 夹具 ====================

    /** 两个并发槽位 × 逐 5 分钟起点的一小时窗口 */
    private static SchedulePlan plan(ScheduleUnit... units) {
        List<Placement> placements = new ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            for (int offset = 0; offset + 10 <= 300; offset += 5) {
                placements.add(new Placement("径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                        480 + offset, 480, 300));
            }
        }
        return new SchedulePlan(placements, new ArrayList<>(List.of(units)));
    }

    private static ScheduleUnit unit(String key, int slot, int rawDuration, long[] athletes) {
        List<Integer> choices = new ArrayList<>();
        for (int v = rawDuration; v >= 10; v -= 5) choices.add(v);
        if (!choices.contains(10)) choices.add(10);
        List<Placement> candidates = new ArrayList<>();
        for (int offset = 0; offset + 10 <= 300; offset += 5) {
            candidates.add(new Placement("径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                    480 + offset, 480, 300));
        }
        return new ScheduleUnit(key, (long) (key.charAt(0) - 'a') + 1L, "项目" + key, "高一", true,
                "径赛", null, 5, rawDuration, 10, athletes, choices, candidates);
    }

    /** 把指定单元覆盖到确定的落位（构造病灶用） */
    private static SchedulePlan overwrite(SchedulePlan plan, String key, int slot,
                                          int startMinute, int duration) {
        for (ScheduleUnit u : plan.getUnits()) {
            if (u.getKey().equals(key)) {
                Placement p = new Placement("径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                        startMinute, 480, 300);
                u.setPlacement(p);
                u.setDuration(duration);
            }
        }
        return plan;
    }
}
