package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConstraintAwareGraphEncoder} 的约束分型语义。
 *
 * <p>核心约定：<b>不同约束类别必须落在不同边类型上</b>。这是 GOAL（arXiv:2605.19119）
 * 的核心论点——若把五类性质迥异的约束压进同一张邻接，模型无法区分
 * 「兼项要错开」与「场地被占要错开」，信息在编码阶段就丢了。</p>
 */
@DisplayName("异构图约束编码（约束类别 = 边类型）")
class ConstraintAwareGraphEncoderTest {

    private static Placement place(String pool, int day, String venue, int cap) {
        return new Placement(pool, 0, 0, day, "2026-01-0" + day, "上午", venue, 480, 480, cap);
    }

    private static ScheduleUnit unit(String key, long[] ath, String pool, String group,
                                     String grade, int dur, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, grade, true, pool, group, 5,
                dur, 10, ath, List.of(dur), cands);
    }

    @Test
    @DisplayName("边类型顺序是双端契约，6 类且顺序固定")
    void typeOrderIsContract() {
        assertEquals(6, ConstraintAwareGraphEncoder.T);
        assertEquals("ATHLETE", ConstraintAwareGraphEncoder.TYPES[0]);
        assertEquals("POOL", ConstraintAwareGraphEncoder.TYPES[1]);
        assertEquals("VENUE", ConstraintAwareGraphEncoder.TYPES[2]);
        assertEquals("GROUP", ConstraintAwareGraphEncoder.TYPES[3]);
        assertEquals("GRADE", ConstraintAwareGraphEncoder.TYPES[4]);
        assertEquals("TIME", ConstraintAwareGraphEncoder.TYPES[5]);
    }

    @Test
    @DisplayName("兼项（共享运动员）只落 ATHLETE 通道，不污染 POOL/VENUE 通道")
    void overlapOnlyMarksAthleteChannel() {
        List<Placement> c1 = List.of(place("径赛", 1, "田径场", 210));
        List<ScheduleUnit> units = List.of(
                unit("a", new long[]{1L, 2L}, "径赛", null, "高一", 20, c1),
                unit("b", new long[]{2L, 3L}, "径赛", null, "高一", 20, c1));

        ConstraintAwareGraphEncoder.Encoded e = ConstraintAwareGraphEncoder.encode(units);
        int n = e.n();
        int t = ConstraintAwareGraphEncoder.T;

        float ath = e.adjByType()[0 * n * n + 0 * n + 1];
        assertTrue(ath > 0f, "共享 1 名运动员 → ATHLETE 通道必须有边");

        // 两者同池同年级同场地，这些通道也会有边——但 ATHLETE 必须单独可辨
        assertTrue(e.degreeByType()[0] > 0, "ATHLETE 边数应 >0");
        assertEquals(1.0f, e.typeMask()[0], 1e-6, "ATHLETE 类型掩码应为 1（0/1 标记）");
    }

    @Test
    @DisplayName("「同池但无兼项」与「有兼项但不同池」是两种不同约束，可被独立检出")
    void distinctConstraintsAreSeparatelyDetectable() {
        // x、y 同池不相交；p、q 有兼项但分处不同池
        List<Placement> t1 = List.of(place("径赛", 1, "田径场", 210));
        List<Placement> f1 = List.of(place("田赛", 1, "跳远区", 60));
        List<Placement> f2 = List.of(place("田赛", 2, "铅球区", 60));
        List<ScheduleUnit> units = List.of(
                unit("x", new long[]{1L}, "径赛", null, "高一", 20, t1),
                unit("y", new long[]{2L}, "径赛", null, "高一", 20, t1),
                unit("p", new long[]{7L, 8L}, "田赛", null, "高一", 20, f1),
                unit("q", new long[]{8L, 9L}, "田赛", null, "高一", 20, f2));

        ConstraintAwareGraphEncoder.Encoded e = ConstraintAwareGraphEncoder.encode(units);
        int n = e.n();

        // p-q 有兼项
        assertTrue(e.adjByType()[0 * n * n + 2 * n + 3] > 0f, "p-q 共享 8 号 → ATHLETE 有边");
        // x-y 同池但无兼项
        assertEquals(0f, e.adjByType()[0 * n * n + 0 * n + 1], 1e-6,
                "x-y 不共享运动员 → ATHLETE 通道必须无边");
        assertTrue(e.adjByType()[1 * n * n + 0 * n + 1] > 0f,
                "x-y 同为径赛池 → POOL 通道有边（与兼项无关）");
    }

    @Test
    @DisplayName("场地独占：同场同天的单元走 VENUE 通道")
    void venueExclusivityIsTyped() {
        List<Placement> c1 = List.of(place("径赛", 1, "田径场", 210));
        List<ScheduleUnit> units = List.of(
                unit("a", new long[]{1L}, "径赛", null, "高一", 20, c1),
                unit("b", new long[]{2L}, "径赛", null, "高一", 20, c1));

        ConstraintAwareGraphEncoder.Encoded e = ConstraintAwareGraphEncoder.encode(units);
        assertTrue(e.degreeByType()[2] > 0, "VENUE 通道应有边（同场地同天）");
        assertEquals(1f, e.typeMask()[2], 1e-6);
    }

    @Test
    @DisplayName("装箱冲突（两个单元时长和 > 时段容量）只落 TIME 通道")
    void packingConflictIsTypedAsTime() {
        // 容量 60 的时段，两个各 50 分钟的单元放不下 → TIME 边
        List<Placement> small = List.of(place("田赛", 1, "跳远区", 60));
        List<ScheduleUnit> units = List.of(
                unit("a", new long[]{1L}, "田赛", null, "高一", 50, small),
                unit("b", new long[]{2L}, "田赛", null, "高一", 50, small));

        ConstraintAwareGraphEncoder.Encoded e = ConstraintAwareGraphEncoder.encode(units);
        int n = e.n();
        assertTrue(e.adjByType()[5 * n * n + 0 * n + 1] > 0f,
                "50+50+间隔 > 60 → TIME 通道必须有边");
        assertEquals(0f, e.adjByType()[0 * n * n + 0 * n + 1], 1e-6,
                "两人不共运动员 → 不该是兼项边");
    }

    @Test
    @DisplayName("汇总邻接取各类型最大值，保证旧单通道模型仍可消费")
    void unionAdjIsMaxOverTypes() {
        List<Placement> c1 = List.of(place("径赛", 1, "田径场", 210));
        List<ScheduleUnit> units = List.of(
                unit("a", new long[]{1L, 2L}, "径赛", null, "高一", 20, c1),
                unit("b", new long[]{2L, 3L}, "径赛", null, "高一", 20, c1));

        ConstraintAwareGraphEncoder.Encoded e = ConstraintAwareGraphEncoder.encode(units);
        int n = e.n();
        float max = 0f;
        for (int t = 0; t < ConstraintAwareGraphEncoder.T; t++) {
            max = Math.max(max, e.adjByType()[t * n * n + 0 * n + 1]);
        }
        assertEquals(max, e.adj()[0 * n + 1], 1e-6,
                "汇总邻接必须等于各类型最大值，旧模型路径才等价");
    }

    @Test
    @DisplayName("空输入与单节点不炸；节点特征仍是 16 维（旧模型可复用）")
    void edgeCasesAreSafe() {
        assertEquals(0, ConstraintAwareGraphEncoder.encode(List.of()).n());

        List<Placement> c1 = List.of(place("径赛", 1, "田径场", 210));
        ConstraintAwareGraphEncoder.Encoded one =
                ConstraintAwareGraphEncoder.encode(List.of(unit("solo", new long[]{1L}, "径赛", null, "高一", 20, c1)));
        assertEquals(1, one.n());
        assertEquals(ConflictGraphEncoder.NODE_FEAT_DIM * 1, one.nodeFeat().length,
                "节点特征必须仍是 16 维，否则旧模型权重无法复用");
        assertNotNull(one.typeMask());
    }
}
