package com.sports.schedule.opt.alns;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 破坏算子的验证：随机破坏规模可控、冲突簇能抓到撞车单元、最忙运动员整拔、窗口整拔。
 */
@DisplayName("ALNS 破坏算子")
class DestroyOperatorsTest {

    private SchedulePlan base;
    private Random rnd;

    @BeforeEach
    void setUp() {
        ScheduleOptimizer optimizer = new ScheduleOptimizer(1);
        base = optimizer.solve(problem(), java.time.Duration.ofMillis(400)).orElseThrow();
        rnd = new Random(5);
    }

    @Test
    @DisplayName("随机破坏：规模在 2..8 之间，且只挑已排单元")
    void randomDestroyBounded() {
        RandomDestroyOperator op = new RandomDestroyOperator();
        List<ScheduleUnit> picked = op.pick(base, rnd);
        assertFalse(picked.isEmpty());
        assertTrue(picked.size() >= 2 && picked.size() <= RandomDestroyOperator.MAX_DESTROY,
                "破坏规模应落在 2.." + RandomDestroyOperator.MAX_DESTROY + "，实际 " + picked.size());
        for (ScheduleUnit u : picked) {
            assertTrue(u.isPlaced(), "只能挑已排单元");
        }
    }

    @Test
    @DisplayName("冲突簇破坏：撞车对两端必入簇；无冲突时退化为随机破坏")
    void clashClusterCapturesClashingUnits() {
        // 人工制造撞车：b（运动员 2,3）与 e（运动员 3）同位重叠
        SchedulePlan damaged = base.deepCopy();
        List<ScheduleUnit> units = damaged.getUnits();
        Placement target = units.get(0).getCandidatePlacements().get(0);
        units.get(1).setPlacement(target);
        units.get(1).setDuration(45);
        units.get(4).setPlacement(target);
        units.get(4).setDuration(20);

        ClashClusterDestroyOperator op = new ClashClusterDestroyOperator();
        List<ScheduleUnit> picked = op.pick(damaged, rnd);

        assertTrue(picked.contains(units.get(1)), "撞车对一端（b）应入簇");
        assertTrue(picked.contains(units.get(4)), "撞车对另一端（e）应入簇");

        // 无冲突的好解：退化为随机破坏（非空、规模有限）
        List<ScheduleUnit> fallback = op.pick(base, rnd);
        assertFalse(fallback.isEmpty());
        assertTrue(fallback.size() <= RandomDestroyOperator.MAX_DESTROY + ClashClusterDestroyOperator.MAX_CLUSTER,
                "退化路径的规模仍应有限");
    }

    @Test
    @DisplayName("最忙运动员破坏：其全部单元被整拔")
    void busyAthleteFullyExtracted() {
        // 夹具报名：a{1,2}、b{2,3}、e{3} → 运动员 2 与 3 并列最忙（各 2 项），
        // 遍历顺序按首次出现取先者 → 最忙者是 2，其单元 a、b 应全被拔出
        BusyAthleteDestroyOperator op = new BusyAthleteDestroyOperator();
        List<ScheduleUnit> picked = op.pick(base, rnd);
        List<ScheduleUnit> units = base.getUnits();
        assertTrue(picked.contains(units.get(0)), "a（运动员 1,2）应被拔出");
        assertTrue(picked.contains(units.get(1)), "b（运动员 2,3）应被拔出");
    }

    @Test
    @DisplayName("窗口破坏：同一并发位的单元整拔")
    void windowDestroyPicksWholeBin() {
        WindowDestroyOperator op = new WindowDestroyOperator();
        List<ScheduleUnit> picked = op.pick(base, rnd);
        assertFalse(picked.isEmpty());
        String bin = picked.get(0).getPlacement().getBinKey();
        for (ScheduleUnit u : picked) {
            assertEquals(bin, u.getPlacement().getBinKey(), "窗口破坏必须整格拔出");
        }
    }

    // ==================== 夹具 ====================

    private static SchedulePlan problem() {
        List<Placement> placements = new ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            for (int offset = 0; offset + 10 <= 210; offset += 10) {
                placements.add(new Placement("径赛", slot, 0, 1, "2026-01-01", "上午", "场地" + slot,
                        480 + offset, 480, 210));
            }
        }
        List<ScheduleUnit> units = List.of(
                unit("a", 60, placements, new long[]{1, 2}),
                unit("b", 45, placements, new long[]{2, 3}),
                unit("c", 30, placements, new long[]{4}),
                unit("d", 25, placements, new long[]{}),
                unit("e", 20, placements, new long[]{3}));
        return new SchedulePlan(placements, units);
    }

    private static ScheduleUnit unit(String key, int rawDuration, List<Placement> candidates, long[] athletes) {
        List<Integer> choices = new ArrayList<>();
        for (int v = rawDuration; v >= 10; v -= 5) choices.add(v);
        if (!choices.contains(10)) choices.add(10);
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, choices, candidates);
    }
}
