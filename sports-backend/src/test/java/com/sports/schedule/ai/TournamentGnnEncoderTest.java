package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 球类赛制 AI 的回归钉子。
 *
 * <p>核心保证三件事：<b>①编码语义正确</b>（约束落到正确的边类型）、<b>②推理结果真的依赖输入</b>
 * （不是常量返回）、<b>③规模超限如实上报</b>而不是静默截断。</p>
 */
class TournamentGnnEncoderTest {

    private static List<TournamentGnnEncoder.Team> teams(int n, boolean sameUnit) {
        java.util.List<TournamentGnnEncoder.Team> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new TournamentGnnEncoder.Team("T" + i,
                    i / (double) Math.max(1, n - 1),          // 实力递增
                    sameUnit ? "高一1班" : "高一" + (i % 6) + "班",
                    "A"));
        }
        return out;
    }

    @Test
    @DisplayName("边类型顺序即 ONNX 通道号 —— 双端契约，改序必须两侧同步")
    void edgeTypeOrderIsContract() {
        assertEquals(0, TournamentGnnEncoder.T_STRENGTH);
        assertEquals(1, TournamentGnnEncoder.T_SAME_CLASS);
        assertEquals(2, TournamentGnnEncoder.T_ROUND);
        assertEquals(3, TournamentGnnEncoder.T_VENUE);
        assertEquals(4, TournamentGnnEncoder.N_TYPES);
        assertEquals(14, TournamentGnnEncoder.NODE_FEAT_DIM);
    }

    @Test
    @DisplayName("同班扎堆必须落到 SAME_CLASS 通道，不污染其它约束")
    void sameUnitFallsIntoItsOwnChannel() {
        var enc = new TournamentGnnEncoder().encode(teams(6, true), List.of("A", "B", "C"), 40, 8, 1);
        assertFalse(enc.degraded());
        assertEquals(6, enc.n());
        // 6 队同班 → SAME_CLASS 满连接
        int same = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                if (i != j && enc.adjByType()[TournamentGnnEncoder.T_SAME_CLASS][i][j] > 0) {
                    same++;
                }
            }
        }
        assertEquals(30, same, "6 队同班应有 C(6,2)*2=30 条有向边");
        assertTrue(enc.typeMask()[TournamentGnnEncoder.T_SAME_CLASS] == 1f);
    }

    @Test
    @DisplayName("不同班时 SAME_CLASS 不应建边（约束缺席要如实为 0）")
    void differentUnitsHaveNoSameClassEdge() {
        var enc = new TournamentGnnEncoder().encode(teams(6, false), List.of("A", "B", "C"), 40, 8, 1);
        int same = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                if (enc.adjByType()[TournamentGnnEncoder.T_SAME_CLASS][i][j] > 0) {
                    same++;
                }
            }
        }
        assertEquals(0, same, "6 个不同班之间不应有 SAME_CLASS 边");
    }

    @Test
    @DisplayName("实力相近才建 STRENGTH 边，且边权随实力差单调下降")
    void strengthEdgeReflectsGap() {
        // 8 队实力均匀铺开 0..1 → 每 0.125 一档，必然有同档（实力完全相同）与跨档
        var enc = new TournamentGnnEncoder().encode(teams(8, false), List.of("A", "B"), 40, 8, 1);
        float[][] a = enc.adjByType()[TournamentGnnEncoder.T_STRENGTH];
        float same = a[0][1];   // 实力 0 与 0.143
        float far = a[0][7];    // 实力 0 与 1.0
        assertTrue(same > 0, "同档队伍应建 STRENGTH 边");
        assertTrue(same > far,
                "实力接近的 " + same + " 应大于实力远的 " + far);
        // 对称性
        assertEquals(a[0][1], a[1][0], 1e-6, "邻接必须对称");
    }

    @Test
    @DisplayName("规模超限：如实降级并带原因，绝不静默截断")
    void oversizeDegradesWithReason() {
        var enc = new TournamentGnnEncoder().encode(teams(TournamentGnnEncoder.MAX_NODES + 1, false),
                List.of("A"), 40, 8, 1);
        assertTrue(enc.degraded(), "超过单层上限必须降级");
        assertNotNull(enc.degradeReason());
        assertTrue(enc.degradeReason().contains(String.valueOf(TournamentGnnEncoder.MAX_NODES)));
    }

    @Test
    @DisplayName("空输入安全，不抛异常")
    void emptyInputIsSafe() {
        var enc = new TournamentGnnEncoder().encode(List.of(), List.of(), 40, 0, 1);
        assertEquals(0, enc.n());
        assertFalse(enc.degraded());
    }

    @Test
    @DisplayName("单队/两队也安全（不做无意义推理）")
    void tinyInputIsSafe() {
        var enc = new TournamentGnnEncoder().encode(teams(2, false), List.of("A"), 40, 8, 1);
        assertEquals(2, enc.n());
    }

    @Test
    @DisplayName("模型推理：输出真的随参赛队结构变化（不是常量返回）")
    void inferenceActuallyDependsOnInput() {
        var svc = new TournamentAiService(true, "classpath:/models", "tournament_gnn.onnx");
        assumeTrue(svc.available(), "tournament_gnn.onnx 未随 classpath 提供，跳过");

        // ① 同班扎堆 vs 全不同班：结构不同 → 种子分/公平性应不同
        var sameUnit = new TournamentGnnEncoder().encode(teams(8, true), List.of("A", "B"), 40, 8, 1);
        var diffUnit = new TournamentGnnEncoder().encode(teams(8, false), List.of("A", "B"), 40, 8, 1);
        Optional<TournamentAiService.Advice> a = svc.advise(sameUnit);
        Optional<TournamentAiService.Advice> b = svc.advise(diffUnit);
        assertTrue(a.isPresent() && b.isPresent(), "模型应可用");

        assertEquals(8, a.get().n(), "输出长度必须等于参赛队数");
        assertEquals(3, a.get().formatScores().length, "赛制 logits 应为 3 维");

        double[] pa = a.get().seedScores();
        double[] pb = b.get().seedScores();
        boolean differs = false;
        for (int i = 0; i < pa.length; i++) {
            if (Math.abs(pa[i] - pb[i]) > 1e-6) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "同班扎堆与全不同班是不同约束结构，种子分不应完全相同");

        // ② 赛制概率必须归一化
        double[] probs = a.get().formatProbabilities();
        double sum = 0;
        for (double v : probs) {
            sum += v;
        }
        assertEquals(1.0, sum, 1e-6, "赛制概率必须归一化");
        assertNotNull(a.get().recommendedFormat());
        assertTrue(List.of(TournamentAiService.FORMAT_NAMES).contains(a.get().recommendedFormat()));
    }

    @Test
    @DisplayName("规模越大赛制倾向不同 —— 模型确实读到了 N")
    void formatShiftsWithScale() {
        var svc = new TournamentAiService(true, "classpath:/models", "tournament_gnn.onnx");
        assumeTrue(svc.available(), "tournament_gnn.onnx 未随 classpath 提供，跳过");
        var small = new TournamentGnnEncoder().encode(teams(4, false), List.of("A", "B", "C"), 40, 4, 1);
        var large = new TournamentGnnEncoder().encode(teams(20, false), List.of("A", "B", "C"), 40, 4, 1);
        double[] ps = svc.advise(small).orElseThrow().formatProbabilities();
        double[] pl = svc.advise(large).orElseThrow().formatProbabilities();
        boolean differs = false;
        for (int i = 0; i < ps.length; i++) {
            if (Math.abs(ps[i] - pl[i]) > 1e-4) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "4 队与 20 队的赛制分布不应完全相同（模型应读到规模特征）");
    }

    @Test
    @DisplayName("模型缺失时优雅降级，绝不抛异常")
    void missingModelDegradesGracefully() {
        var svc = new TournamentAiService(true, "classpath:/models", "no_such_model.onnx");
        assertFalse(svc.available());
        var enc = new TournamentGnnEncoder().encode(teams(6, false), List.of("A"), 40, 8, 1);
        assertTrue(svc.advise(enc).isEmpty(), "模型缺失应返回空让上层走规则");
    }
}
