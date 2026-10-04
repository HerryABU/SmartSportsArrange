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

        assertEquals(3, enc.nodeCount, "真实节点数 = 3");
        // 动态节点数：数组按实际 n 分配，不补齐到 MAX_NODES
        assertEquals(3, enc.totalUnits);
        assertEquals(0, enc.dropped);
        assertEquals(enc.nodeCount * ConflictGraphEncoder.NODE_FEAT_DIM, enc.nodeFeat.length);
        assertEquals(enc.nodeCount * enc.nodeCount, enc.adj.length);
        assertEquals(enc.nodeCount, enc.mask.length);

        int n = enc.nodeCount;
        // 邻接对称、无自环
        for (int i = 0; i < n; i++) {
            assertEquals(0.0f, enc.adj[i * n + i], 1e-6, "无自环");
            for (int j = 0; j < n; j++) {
                assertEquals(enc.adj[i * n + j], enc.adj[j * n + i], 1e-6, "邻接对称");
            }
        }
        // 边 a-b、a-c（各共享 1 人 → 权重 1.0）
        assertEquals(1.0f, enc.adj[0 * n + 1], 1e-6);
        assertEquals(1.0f, enc.adj[0 * n + 2], 1e-6);
        assertEquals(0.0f, enc.adj[1 * n + 2], 1e-6, "b、c 不共享运动员");

        // mask：所有真实节点为 1
        for (int i = 0; i < n; i++) {
            assertEquals(1.0f, enc.mask[i], 1e-6);
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

    @Test
    @DisplayName("带权邻接：边权 = 共享运动员数 / 全局最大共享数")
    void weightedAdjacencyReflectsSharedAthletes() {
        List<Placement> placements = List.of(
                new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场", 480, 480, 210));
        // a∩b = {3,4}（2 人）；a∩c = {5}（1 人）；b∩c = ∅ → maxShared = 2
        List<ScheduleUnit> units = List.of(
                unit("a", 100, new long[]{1L, 3L, 4L, 5L}, placements),
                unit("b", 100, new long[]{2L, 3L, 4L}, placements),
                unit("c", 100, new long[]{6L, 5L}, placements));

        ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(units);
        int n = enc.nodeCount;

        assertEquals(2, enc.maxShared, "全局最大共享运动员数");
        assertEquals(1.0f, enc.adj[0 * n + 1], 1e-6, "共享 2 人 → 权重 1.0");
        assertEquals(0.5f, enc.adj[0 * n + 2], 1e-6, "共享 1 人 → 权重 0.5（这正是二值邻接丢掉的信息）");
        assertEquals(0.0f, enc.adj[1 * n + 2], 1e-6, "b、c 不共享运动员");
        assertEquals(enc.adj[0 * n + 1], enc.adj[1 * n + 0], 1e-6, "邻接对称");
    }

    @Test
    @DisplayName("超出 MAX_NODES 的单元被截断并如实报告（不静默丢弃）")
    void overflowTruncationIsReported() {
        List<Placement> placements = List.of(
                new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场", 480, 480, 210));
        int over = ConflictGraphEncoder.MAX_NODES + 37;
        List<ScheduleUnit> many = new ArrayList<>();
        for (int i = 0; i < over; i++) {
            many.add(unit("u" + i, 20, new long[]{i + 1L}, placements));
        }

        ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(many);
        assertEquals(ConflictGraphEncoder.MAX_NODES, enc.nodeCount);
        assertEquals(over, enc.totalUnits);
        assertEquals(37, enc.dropped, "必须如实报告被截断的单元数，避免无声降级");
    }

    @Test
    @DisplayName("扩展特征维度：决赛标记 / 年级序号 / 时长占比 / 超大单元 / 位置")
    void extendedFeaturesAreEncoded() {
        List<Placement> placements = List.of(
                new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场", 480, 480, 210));
        ScheduleUnit prelim = new ScheduleUnit("p", 1L, "100米(预赛)", "高一", true, "径赛", null, 5,
                100, 10, new long[]{1L}, List.of(100), placements);
        ScheduleUnit fin = new ScheduleUnit("f", 1L, "100米(决赛)", "高二", true, "径赛", null, 5,
                20, 10, new long[]{1L}, List.of(20), placements);
        ScheduleUnit big = new ScheduleUnit("b", 2L, "立定跳远(决赛)", "高三", false, "田赛", null, 5,
                540, 60, new long[]{2L}, List.of(540), placements);

        ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(List.of(prelim, fin, big));
        int d = ConflictGraphEncoder.NODE_FEAT_DIM;

        assertEquals(16, d, "通用节点特征仍是 16 维（生成式三件套共用，改不得）");
        assertEquals(17, ConflictGraphEncoder.CONFLICT_MODEL_FEAT_DIM,
                "冲突模型专用输入 = 16 通用 + 1 度数");
        // 扩展后的特征必须真的多出「归一化度数」那一维，且与邻接算出的度数一致
        float[] ext = ConflictGraphEncoder.conflictModelFeat(enc);
        int n = enc.nodeCount;
        assertEquals(n * 17, ext.length, "扩展特征长度 = n × 17");
        for (int i = 0; i < n; i++) {
            int deg = 0;
            for (int j = 0; j < n; j++) {
                if (enc.adj[i * n + j] > 0f) {
                    deg++;
                }
            }
            float expect = (n > 1) ? deg / (float) (n - 1) : 0f;
            assertEquals(expect, ext[i * 17 + 16], 1e-6f,
                    "第 17 维必须等于归一化度数（节点 " + i + "）");
        }
        assertEquals(0.0f, enc.nodeFeat[0 * d + 8], 1e-6, "预赛 is_final=0");
        assertEquals(1.0f, enc.nodeFeat[1 * d + 8], 1e-6, "决赛 is_final=1");
        assertEquals(0.0f, enc.nodeFeat[0 * d + 9], 1e-6, "高一 grade_idx=0");
        assertEquals(1.0f, enc.nodeFeat[2 * d + 9], 1e-6, "高三 grade_idx 归一化到 1");
        assertEquals(1.0f, enc.nodeFeat[2 * d + 14], 1e-6, "540 分钟 → is_large_unit=1");
        assertEquals(0.0f, enc.nodeFeat[0 * d + 14], 1e-6, "100 分钟 → 非超大单元");
        assertEquals(540f / 660f, enc.nodeFeat[2 * d + 10], 1e-4, "duration_share = 540/660");
        assertEquals(0.0f, enc.nodeFeat[0 * d + 15], 1e-6, "order_norm 首个为 0");
        assertEquals(1.0f, enc.nodeFeat[2 * d + 15], 1e-6, "order_norm 末个为 1");
    }

    @Test
    @DisplayName("归一化度数中心度可作为 GNN 的对照基线")
    void degreeCentralityBaseline() {
        List<Placement> placements = List.of(
                new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场", 480, 480, 210));
        List<ScheduleUnit> units = List.of(
                unit("a", 100, new long[]{1L, 2L}, placements),
                unit("b", 200, new long[]{2L}, placements),
                unit("c", 150, new long[]{1L}, placements));
        ConflictGraphEncoder.Encoded enc = ConflictGraphEncoder.encode(units);
        float[] c = ConflictGraphEncoder.degreeCentrality(enc);
        assertEquals(3, c.length);
        assertEquals(1.0f, c[0], 1e-6, "a 连接 b、c → 度数 2 → 中心度 1.0");
        assertEquals(0.5f, c[1], 1e-6);
        assertEquals(0.5f, c[2], 1e-6);
    }

    private static ScheduleUnit unit(String key, int rawDuration, long[] athletes, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, List.of(rawDuration), cands);
    }
}
