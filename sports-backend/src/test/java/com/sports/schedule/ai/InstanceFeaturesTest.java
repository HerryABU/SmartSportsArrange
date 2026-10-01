package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 16 维实例特征契约的验证——与训练侧 {@code sports_ai/data/features.py} 严格同构。
 *
 * <p>这里手算校验每一维，确保「训练用的特征」与「推理用的特征」口径一致，否则
 * ONNX 模型喂给 Java 推理语义就漂移了。</p>
 */
@DisplayName("实例特征契约（16 维）")
class InstanceFeaturesTest {

    @Test
    @DisplayName("两单元一冲突边：需求/供给/兼项占比/冲突图逐维手算校验")
    void extractMatchesHandComputedValues() {
        // 两个单元：a 运动员[1,2]，b 运动员[2] → 共享运动员 2，一条冲突边
        List<Placement> placements = new ArrayList<>();
        for (int start = 480; start <= 690; start += 10) {
            placements.add(new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场",
                    start, 480, 210));
        }
        List<ScheduleUnit> units = List.of(
                unit("a", 100, new long[]{1L, 2L}, placements),
                unit("b", 200, new long[]{2L}, placements));

        double[] f = InstanceFeatures.extract(units, placements);

        assertEquals(2, f[0], 1e-9, "unit_count");
        assertEquals(300, f[1], 1e-9, "demand_minutes = 100 + 200");
        assertEquals(210, f[2], 1e-9, "supply_minutes：同一并发位多起点只算一次容量");
        assertEquals(300.0 / 210.0, f[3], 0.01, "tension_ratio");
        assertEquals(0.5, f[4], 0.01, "multi_event_athlete_ratio：1 人兼项 / 2 人");
        assertEquals(2, f[5], 1e-9, "athlete_count");
        assertEquals(1, f[6], 1e-9, "conflict_edges：a-b 共享运动员 2");
        assertEquals(1.0, f[7], 1e-9, "conflict_density = 2*1/(2*1)");
        assertEquals(1, f[8], 1e-9, "conflict_components：a、b 连通");
        assertEquals(1, f[9], 1e-9, "max_degree");
        assertEquals(1.0, f[10], 1e-9, "avg_degree");
        assertEquals(1, f[11], 1e-9, "pool_count：只有径赛");
        assertEquals(1, f[12], 1e-9, "day_count");
        assertEquals(150.0, f[13], 1e-9, "avg_duration = (100+200)/2");
        assertEquals(0, f[15], 1e-9, "group_count：无 group_key → 0");
    }

    @Test
    @DisplayName("空输入安全：不抛异常，返回零向量")
    void emptyInputIsSafe() {
        double[] f = InstanceFeatures.extract(List.of(), List.of());
        assertEquals(16, f.length);
        assertEquals(0, f[0], 1e-9);
        assertEquals(0.0, f[3], 1e-9);
    }

    private static ScheduleUnit unit(String key, int rawDuration, long[] athletes, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, List.of(rawDuration), cands);
    }
}
