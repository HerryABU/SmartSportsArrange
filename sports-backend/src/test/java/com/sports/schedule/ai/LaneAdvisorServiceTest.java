package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 道次编排 AI 的端到端验证：Java 端加载训练侧导出的 ONNX，给出**派遣顺序**。
 *
 * <p>关注三件事：</p>
 * <ol>
 *   <li>模型能随 jar 交付（classpath）并从 jar 内加载；</li>
 *   <li>运动员数 ``n`` 是动态轴 —— 同一模型要能吃下 2 人和上百人；</li>
 *   <li>输出是一个**完整排列**（每人恰好一次），因为调用方要拿它直接做 argsort 分组。</li>
 * </ol>
 */
@DisplayName("道次编排 AI（模型就绪时）")
class LaneAdvisorServiceTest {

    private static LaneAdvisorService classpathService() {
        return new LaneAdvisorService(true, "classpath:/models", "lane_advisor.onnx");
    }

    private static boolean modelReady() {
        return ModelSource.read("classpath:/models", "lane_advisor.onnx").isPresent();
    }

    @Test
    @DisplayName("classpath（jar 内）模型可加载并给出完整派遣排列")
    void suggestsOrderFromClasspathModel() {
        assumeTrue(modelReady(), "跳过：resources/models 下无 lane_advisor.onnx");
        LaneAdvisorService svc = classpathService();
        assertTrue(svc.isAvailable(), "随 jar 交付的模型应能直接从 classpath 加载");

        int n = 16;
        List<String> classes = new ArrayList<>();
        boolean[] female = new boolean[n];
        int[] eventCounts = new int[n];
        double[] seed = new double[n];
        for (int i = 0; i < n; i++) {
            classes.add("高一" + (i % 4 + 1) + "班");   // 4 个班
            female[i] = i % 2 == 0;
            eventCounts[i] = 1 + i % 3;
        }

        Optional<int[]> order = svc.suggestOrder(classes, female, eventCounts, seed, 8);
        assertTrue(order.isPresent(), "模型就绪时应给出派遣顺序");
        int[] idx = order.get();
        assertEquals(n, idx.length, "顺序长度 = 运动员数");

        boolean[] seen = new boolean[n];
        for (int v : idx) {
            assertTrue(v >= 0 && v < n, "下标越界: " + v);
            assertFalse(seen[v], "同一运动员不应出现两次（必须是排列）");
            seen[v] = true;
        }
    }

    @Test
    @DisplayName("运动员数 n 为动态轴：同一模型同时支持极小与上百人")
    void handlesArbitraryAthleteCount() {
        assumeTrue(modelReady(), "跳过：模型未导出");
        LaneAdvisorService svc = classpathService();
        for (int n : new int[]{2, 37, 260}) {
            List<String> classes = new ArrayList<>();
            boolean[] female = new boolean[n];
            int[] eventCounts = new int[n];
            for (int i = 0; i < n; i++) {
                classes.add("班" + (i % 7));
                eventCounts[i] = 1;
            }
            Optional<int[]> order = svc.suggestOrder(classes, female, eventCounts, null, 8);
            assertTrue(order.isPresent(), "n=" + n + " 时应能推理");
            assertEquals(n, order.get().length, "n=" + n + " 时顺序长度应匹配");
        }
    }

    @Test
    @DisplayName("模型的排序确实区分运动员（不是常数输出）")
    void orderIsNotDegenerate() {
        assumeTrue(modelReady(), "跳过：模型未导出");
        LaneAdvisorService svc = classpathService();
        int n = 24;
        List<String> classes = new ArrayList<>();
        boolean[] female = new boolean[n];
        int[] eventCounts = new int[n];
        double[] seed = new double[n];
        for (int i = 0; i < n; i++) {
            classes.add("班" + (i % 6));
            female[i] = i % 2 == 0;
            eventCounts[i] = 1 + (i % 3);
            seed[i] = (i % 5) / 4.0;             // 部分人有成绩
        }
        int[] order = svc.suggestOrder(classes, female, eventCounts, seed, 8).orElseThrow();
        // 全同分时 argsort 会退化为原始顺序；这里要求模型给出非平凡排序
        boolean identity = true;
        for (int i = 0; i < n; i++) {
            if (order[i] != i) {
                identity = false;
                break;
            }
        }
        assertFalse(identity, "模型应给出非平凡派遣顺序（否则等于没有排序能力）");
    }

    @Test
    @DisplayName("模型缺失时返回空（调用方回退既有排序）")
    void missingModelDegradesGracefully() {
        LaneAdvisorService svc = new LaneAdvisorService(
                true, "classpath:/__no_such_models__", "lane_advisor.onnx");
        assertFalse(svc.isAvailable());
        assertTrue(svc.suggestOrder(List.of("A"), new boolean[]{true},
                new int[]{1}, null, 8).isEmpty());

        LaneAdvisorService disabled = new LaneAdvisorService(
                false, "classpath:/models", "lane_advisor.onnx");
        assertFalse(disabled.isAvailable());
        assertNotNull(disabled.modelInfo());
    }
}
