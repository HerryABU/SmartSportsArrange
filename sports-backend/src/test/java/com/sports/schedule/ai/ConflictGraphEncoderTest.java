package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲突簇 GNN 输入编码的验证——与训练侧 {@code sports_ai/data/gnn_io.py} 严格同构。
 */
@DisplayName("冲突图 GNN 编码契约")
class ConflictGraphEncoderTest {

    @Test
    @DisplayName("邻接对称、无自环、只在 mask 内；节点特征落在 [0,1]")
    void encodeProducesValidContract() {
        List<Placement> placements = new ArrayList<>();
        for (int start = 480; start <= 690; start += 10) {
            placements.add(new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场",
                    start, 480, 210));
        }
        // 三个单元：a[1,2]、b[2]、c[1] → 边 a-b、a-c（b、c 不共享）
        List<ScheduleUnit> units = List.of(
                unit("a", 100, new long[]{1L, 2L}, placements),
                unit("b", 200, new long[]{2L}, placements),
                unit("c", 150, new long[]{1L}, placements));

        ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(units);
        int n = ConflictGraphEncoder.MAX_NODES;

        assertEquals(3, enc.nodeCount, "真实节点数 = 3");
        assertEquals(n * ConflictGraphEncoder.NODE_FEAT_DIM, enc.nodeFeat.length);
        assertEquals(n * n, enc.adj.length);
        assertEquals(n, enc.mask.length);

        // 邻接对称、无自环
        for (int i = 0; i < n; i++) {
            assertEquals(0.0f, enc.adj[i * n + i], 1e-6, "无自环");
            for (int j = 0; j < n; j++) {
                assertEquals(enc.adj[i * n + j], enc.adj[j * n + i], 1e-6, "邻接对称");
            }
        }
        // 边 a-b、a-c
        assertEquals(1.0f, enc.adj[0 * n + 1], 1e-6);
        assertEquals(1.0f, enc.adj[0 * n + 2], 1e-6);
        assertEquals(0.0f, enc.adj[1 * n + 2], 1e-6, "b、c 不共享运动员");

        // mask：前 3 个为 1，其余为 0
        for (int i = 0; i < n; i++) {
            assertEquals(i < 3 ? 1.0f : 0.0f, enc.mask[i], 1e-6);
        }

        // 节点特征全部落在 [0,1]
        for (int i = 0; i < 3 * ConflictGraphEncoder.NODE_FEAT_DIM; i++) {
            assertTrue(enc.nodeFeat[i] >= 0.0f && enc.nodeFeat[i] <= 1.0f, "节点特征需在 [0,1]");
        }
    }

    @Test
    @DisplayName("径赛单元 track 特征为 1，田赛为 0")
    void trackFeatureIsEncoded() {
        List<Placement> placements = List.of(
                new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场", 480, 480, 210));
        ScheduleUnit track = new ScheduleUnit("t", 1L, "100m", "高一", true, "径赛", null, 5,
                20, 10, new long[]{1L}, List.of(20), placements);
        ScheduleUnit field = new ScheduleUnit("f", 2L, "跳远", "高一", false, "田赛", "跳跃组", 5,
                90, 40, new long[]{2L}, List.of(90), placements);

        ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(List.of(track, field));
        int fdim = ConflictGraphEncoder.NODE_FEAT_DIM;
        assertEquals(1.0f, enc.nodeFeat[0 * fdim + 0], 1e-6, "径赛 track=1");
        assertEquals(0.0f, enc.nodeFeat[1 * fdim + 0], 1e-6, "田赛 track=0");
        assertEquals(1.0f, enc.nodeFeat[1 * fdim + 3], 1e-6, "跳远有 group_key → has_group=1");
    }

    private static ScheduleUnit unit(String key, int rawDuration, long[] athletes, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, List.of(rawDuration), cands);
    }
}
