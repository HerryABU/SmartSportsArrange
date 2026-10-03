package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 超级编排模型（{@code super_moe.onnx} + {@link SuperScheduleEncoder}）的回归钉子。
 *
 * <p>核心保证四件事：<b>①双端契约</b>（边类型顺序/特征维度/任务数）、
 * <b>②时间目标三态</b>、<b>③约束落到正确通道</b>、<b>④推理真的依赖输入</b>。</p>
 */
class SuperScheduleEncoderTest {

    private static SuperScheduleEncoder.Unit unit(int i, int task, String groupKey,
                                                 String venue, int duration, int heat) {
        List<Long> ath = new ArrayList<>();
        for (long a = 0; a < 10; a++) {
            ath.add(i * 10L + a);
        }
        return new SuperScheduleEncoder.Unit("u" + i, "项目" + i, task, true, false,
                null, groupKey, venue, "P0", "高一", duration, 15, heat,
                ath, null, null, "main");
    }

    private static List<SuperScheduleEncoder.Window> windows() {
        return List.of(
                new SuperScheduleEncoder.Window(1, 0, 240, "田径场", "P0"),
                new SuperScheduleEncoder.Window(1, 1, 240, "田径场", "P0"),
                new SuperScheduleEncoder.Window(2, 0, 240, "沙坑", "P0"));
    }

    @Test
    @DisplayName("图级特征八维与 super_encode.py 逐位对齐（填充率不能被整数截断）")
    void graphFeatMatchesPythonContract() {
        SuperScheduleEncoder enc = new SuperScheduleEncoder();
        List<SuperScheduleEncoder.Unit> us = List.of(
                unit(0, 0, "blkA", "田径场", 60, 0),
                unit(1, 0, "blkA", "田径场", 60, 0),
                unit(2, 0, "blkB", "田径场", 60, 0));
        var e = enc.encode(us, windows(), 2, 100);
        float[] g = e.graphFeat();
        assertEquals(8, g.length, "图级特征必须是八维");
        // 0 冲突密度：三人运动员互不重叠 → 0
        assertEquals(0f, g[0], 1e-6);
        // 1 单元规模 3/128
        assertEquals(3f / 128f, g[1], 1e-6);
        // 2 场地数：田径场 + 沙坑 = 2
        assertEquals(2f / 12f, g[2], 1e-6);
        // 3 天数：windows 里最大 day = 2
        assertEquals(2f / 7f, g[3], 1e-6);
        // 4 时间目标：daysLimit>0 编码为 0.5
        assertEquals(0.5f, g[4], 1e-6);
        // 5 每时段并行场地数：三个时段各 1 个场地
        assertEquals(1f / 4f, g[5], 1e-6);
        // 6 填充率：需求 3*(60+15)=225，容量 3*240=720
        // ⚠️ 这里钉死「必须浮点除」：写成 int 相除会先截断成 0，图级路由少收
        //    一个最关键的结构信号，而且不报错。
        assertEquals(225f / 720f, g[6], 1e-6);
        // 7 块压力：2 块 / 2 天 / 4
        assertEquals(2f / 2f / 4f, g[7], 1e-6);
        for (float v : g) {
            assertTrue(v >= 0f && v <= 1f, "图级特征应在 [0,1]，实际 " + v);
        }
    }

    @Test
    @DisplayName("并行场地数按时段内不同场地计，不是窗口条数")
    void parallelCountsDistinctVenuesPerSlot() {
        // 同一 (day, windowIdx) 开两个场地 = 并行 2；不同 windowIdx 是不同时段
        var ws = List.of(
                new SuperScheduleEncoder.Window(1, 0, 120, "田径场", "P0"),
                new SuperScheduleEncoder.Window(1, 0, 120, "沙坑", "P0"),
                new SuperScheduleEncoder.Window(1, 1, 240, "田径场", "P0"));
        var e = new SuperScheduleEncoder().encode(
                List.of(unit(0, 0, null, "田径场", 60, 0), unit(1, 0, null, "沙坑", 60, 0)),
                ws, 1, 100);
        assertEquals(2f / 4f, e.graphFeat()[5], 1e-6, "同一时段开两个场地才算并行 2");
    }

    @Test
    @DisplayName("双端契约：边类型顺序 / 特征维度 / 任务数 / 赛制数")
    void contractIsStable() {
        assertEquals(0, SuperScheduleEncoder.E_ATHLETE);
        assertEquals(1, SuperScheduleEncoder.E_BLOCK);
        assertEquals(2, SuperScheduleEncoder.E_VENUE);
        assertEquals(3, SuperScheduleEncoder.E_POOL);
        assertEquals(4, SuperScheduleEncoder.E_LANE);
        assertEquals(5, SuperScheduleEncoder.E_TIME);
        assertEquals(6, SuperScheduleEncoder.E_BRACKET);
        assertEquals(7, SuperScheduleEncoder.E_TEAM);
        assertEquals(8, SuperScheduleEncoder.N_EDGES);
        assertEquals(20, SuperScheduleEncoder.NODE_FEAT_DIM);
        assertEquals(9, SuperScheduleEncoder.N_TASKS);
        assertEquals(4, SuperScheduleEncoder.N_FORMATS);
        // 合并裁判编排 / 教师规避后：9 → 11 类任务（顺序即 ONNX 输出通道号）
        assertEquals(11, SuperScheduleEncoder.taskNames().length);
    }

    @Test
    @DisplayName("时间目标三态：硬约束 0.5 / 不限 0.0 / 最小化 1.0")
    void timeGoalHasThreeStates() {
        SuperScheduleEncoder enc = new SuperScheduleEncoder();
        List<SuperScheduleEncoder.Unit> us = List.of(
                unit(0, 0, null, "田径场", 60, 0), unit(1, 0, null, "田径场", 60, 0),
                unit(2, 0, null, "田径场", 60, 0));

        var hard = enc.encode(us, windows(), 3, 100);
        var free = enc.encode(us, windows(), 0, 100);
        var mini = enc.encode(us, windows(), -1, 100);

        // 第 13 维是 time_goal（与 super_encode.py 的 feat[i][13] 一致）
        assertEquals(0.5f, hard.nodeFeat()[0][13], 1e-6, "硬约束应编码为 0.5");
        assertEquals(0.0f, free.nodeFeat()[0][13], 1e-6, "不限时间应编码为 0.0");
        assertEquals(1.0f, mini.nodeFeat()[0][13], 1e-6, "最小化工期应编码为 1.0");
        // 原值必须保留供 Java 端解释
        assertEquals(3, hard.daysLimit());
        assertEquals(0, free.daysLimit());
        assertEquals(-1, mini.daysLimit());
    }

    @Test
    @DisplayName("项目块走 BLOCK 通道，且不污染兼项通道")
    void blockConstraintLandsInItsChannel() {
        var enc = new SuperScheduleEncoder().encode(
                List.of(unit(0, 0, "blkA", "田径场", 60, 0),
                        unit(1, 0, "blkA", "田径场", 60, 0),
                        unit(2, 0, "blkB", "田径场", 60, 0),
                        unit(3, 0, "blkB", "田径场", 60, 0)),
                windows(), 2, 100);
        assertTrue(enc.adjByType()[SuperScheduleEncoder.E_BLOCK][0][1] > 0, "同块单元应建边");
        assertEquals(0f, enc.adjByType()[SuperScheduleEncoder.E_BLOCK][0][2], 1e-6,
                "不同块之间不应建边");
        assertEquals(0f, enc.typeMask()[SuperScheduleEncoder.E_ATHLETE], 1e-6,
                "各单元运动员不重叠时不应有兼项边");
    }

    @Test
    @DisplayName("道次容量 > 0 才建 LANE 边")
    void laneEdgeOnlyWhenHeatCapacityPresent() {
        // ⚠️ 道次分组的键是「项目名|年级|场地」——**必须三者都同**才是同一批次。
        //    单元 0/1 用同一 name（不同 key），才能构成「同一项目分两个批次」的真实场景。
        List<SuperScheduleEncoder.Unit> us = List.of(
                new SuperScheduleEncoder.Unit("h0", "100米", 1, true, false, null, null,
                        "田径场", "P0", "高一", 60, 15, 4, List.of(1L, 2L), null, null, "prelim"),
                new SuperScheduleEncoder.Unit("h1", "100米", 1, true, false, null, null,
                        "田径场", "P0", "高一", 60, 15, 4, List.of(3L, 4L), null, null, "prelim"),
                new SuperScheduleEncoder.Unit("f0", "跳远", 0, false, false, null, null,
                        "沙坑", "P0", "高一", 60, 15, 0, List.of(5L, 6L), null, null, "main"));
        var enc = new SuperScheduleEncoder().encode(us, windows(), 2, 100);
        assertTrue(enc.adjByType()[SuperScheduleEncoder.E_LANE][0][1] > 0, "同道次应建边");
        assertEquals(1f, enc.typeMask()[SuperScheduleEncoder.E_LANE], 1e-6);
    }

    @Test
    @DisplayName("淘汰赛晋级 + 二次编排走 BRACKET 通道")
    void bracketAndResecondLandInChannel() {
        var enc = new SuperScheduleEncoder();
        List<SuperScheduleEncoder.Unit> us = List.of(
                new SuperScheduleEncoder.Unit("ko0", "篮球决赛", 3, false, true, 2,
                        null, "篮球场", "P1", "高一", 120, 0, 0,
                        List.of(1L, 2L), 0, null, "final"),
                new SuperScheduleEncoder.Unit("ko1", "篮球半决赛", 3, false, true, 2,
                        null, "篮球场", "P1", "高一", 120, 0, 0,
                        List.of(3L, 4L), 1, null, "prelim"),
                new SuperScheduleEncoder.Unit("ko1|RE", "篮球半决赛·二次编排", 8, false, true, 2,
                        null, "篮球场", "P1", "高一", 120, 0, 0,
                        List.of(3L, 4L), 1, "ko1", "resecond"));
        var e = enc.encode(us, windows(), 1, 10);
        assertEquals(1f, e.typeMask()[SuperScheduleEncoder.E_BRACKET], 1e-6, "应存在晋级边");
        // 二次编排单元（第 2 行）应与 ko1（第 1 行）相连
        assertTrue(e.adjByType()[SuperScheduleEncoder.E_BRACKET][2][1] > 0,
                "二次编排单元应挂到首轮");
        // stage 在第 15 维：main=0 / prelim=0.33 / final=0.66 / resecond=1.0
        assertEquals(1.0f, e.nodeFeat()[2][15], 1e-6, "二次编排的 stage 应为 1.0");
        assertEquals(0.66f, e.nodeFeat()[0][15], 1e-6, "决赛的 stage 应为 0.66");
        // 赛制 onehot 在 16-19：knockout=2 → 第 18 维为 1
        assertEquals(1f, e.nodeFeat()[0][18], 1e-6, "淘汰赛应编码在赛制 onehot");
    }

    @Test
    @DisplayName("装箱耦合走 TIME 通道：两单元时长和超容量才建边")
    void timeEdgeWhenPackingOverCapacity() {
        var enc = new SuperScheduleEncoder();
        // 窗口容量 240，两个 200 分钟单元放不下
        var over = enc.encode(List.of(
                        new SuperScheduleEncoder.Unit("a", "A", 0, true, false, null, null,
                                "沙坑", "P0", "高一", 200, 0, 0, List.of(1L, 2L), null, null, "main"),
                        new SuperScheduleEncoder.Unit("b", "B", 0, true, false, null, null,
                                "沙坑", "P0", "高一", 200, 0, 0, List.of(3L, 4L), null, null, "main")),
                List.of(new SuperScheduleEncoder.Window(1, 0, 240, "沙坑", "P0")), 1, 10);
        assertTrue(over.adjByType()[SuperScheduleEncoder.E_TIME][0][1] > 0, "超容量应建装箱边");

        // 容量放大到 500 后不再建边
        var fit = enc.encode(List.of(
                        new SuperScheduleEncoder.Unit("a", "A", 0, true, false, null, null,
                                "沙坑", "P0", "高一", 200, 0, 0, List.of(1L, 2L), null, null, "main"),
                        new SuperScheduleEncoder.Unit("b", "B", 0, true, false, null, null,
                                "沙坑", "P0", "高一", 200, 0, 0, List.of(3L, 4L), null, null, "main")),
                List.of(new SuperScheduleEncoder.Window(1, 0, 500, "沙坑", "P0")), 1, 10);
        assertEquals(0f, fit.adjByType()[SuperScheduleEncoder.E_TIME][0][1], 1e-6,
                "容量足够时不应有装箱边");
    }

    @Test
    @DisplayName("规模超限：如实降级并带原因，绝不静默截断")
    void oversizeDegradesWithReason() {
        List<SuperScheduleEncoder.Unit> us = new ArrayList<>();
        for (int i = 0; i < SuperScheduleEncoder.MAX_NODES + 1; i++) {
            us.add(unit(i, 0, null, "田径场", 30, 0));
        }
        var enc = new SuperScheduleEncoder().encode(us, windows(), 2, 9999);
        assertTrue(enc.degraded());
        assertNotNull(enc.degradeReason());
        assertTrue(enc.degradeReason().contains(String.valueOf(SuperScheduleEncoder.MAX_NODES)));
    }

    @Test
    @DisplayName("空输入与极小输入安全")
    void tinyInputIsSafe() {
        var enc = new SuperScheduleEncoder();
        assertEquals(0, enc.encode(List.of(), windows(), 0, 0).n());
        assertEquals(2, enc.encode(List.of(unit(0, 0, null, "田径场", 30, 0),
                unit(1, 0, null, "田径场", 30, 0)), windows(), 2, 50).n());
    }

    @Test
    @DisplayName("推理：输出真的随编排结构变化（不是常量返回）")
    void inferenceActuallyDependsOnInput() {
        var svc = new SuperMoeService(true, "classpath:/models", "super_moe.onnx");
        assumeTrue(svc.available(), "super_moe.onnx 未随 classpath 提供，跳过");

        var enc = new SuperScheduleEncoder();
        // ① 结构不同：项目块 vs 全散开
        var blocked = enc.encode(List.of(
                unit(0, 0, "blkA", "田径场", 60, 0), unit(1, 0, "blkA", "田径场", 60, 0),
                unit(2, 0, "blkB", "田径场", 60, 0), unit(3, 0, "blkB", "田径场", 60, 0)),
                windows(), 2, 100);
        var scattered = enc.encode(List.of(
                unit(0, 0, null, "田径场", 60, 0), unit(1, 0, null, "田径场", 60, 0),
                unit(2, 0, null, "田径场", 60, 0), unit(3, 0, null, "田径场", 60, 0)),
                windows(), 2, 100);

        Optional<SuperMoeService.Advice> a = svc.advise(blocked);
        Optional<SuperMoeService.Advice> b = svc.advise(scattered);
        assertTrue(a.isPresent() && b.isPresent(), "模型应可用");

        assertEquals(4, a.get().n(), "输出长度必须等于单元数");
        assertEquals(11, a.get().taskProbs().length, "任务权重应为 11 维（含裁判编排 / 教师规避）");
        assertEquals(4, a.get().formatLogits().length, "赛制应为 4 维");
        assertEquals(4, a.get().slotLogits().length, "槽位 logits 应为 [N][K]");
        assertEquals(SuperScheduleEncoder.MAX_SLOTS, a.get().slotLogits()[0].length);

        boolean differs = false;
        double[] pa = a.get().priority();
        double[] pb = b.get().priority();
        for (int i = 0; i < pa.length; i++) {
            if (Math.abs(pa[i] - pb[i]) > 1e-6) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "项目块结构不同，优先级不应完全相同");

        // ② 时间目标三态应影响输出
        var hard = enc.encode(List.of(unit(0, 0, null, "田径场", 60, 0),
                unit(1, 0, null, "田径场", 60, 0), unit(2, 0, null, "田径场", 60, 0)),
                windows(), 3, 100);
        var mini = enc.encode(List.of(unit(0, 0, null, "田径场", 60, 0),
                unit(1, 0, null, "田径场", 60, 0), unit(2, 0, null, "田径场", 60, 0)),
                windows(), -1, 100);
        var ph = svc.advise(hard).orElseThrow();
        var pm = svc.advise(mini).orElseThrow();
        assertFalse(java.util.Arrays.equals(ph.priority(), pm.priority()),
                "时间目标（硬约束 vs 最小化）不同，输出不应完全相同");
    }

    @Test
    @DisplayName("专家分工：模型真的分出不同任务权重（不是均匀分布）")
    void expertsAreDifferentiated() {
        var svc = new SuperMoeService(true, "classpath:/models", "super_moe.onnx");
        assumeTrue(svc.available(), "super_moe.onnx 未随 classpath 提供，跳过");
        var enc = new SuperScheduleEncoder();
        // 纯球类场景：应偏向「球类赛制 / 淘汰赛晋级」类专家
        List<SuperScheduleEncoder.Unit> ball = List.of(
                new SuperScheduleEncoder.Unit("b0", "篮球决赛", 3, false, true, 2,
                        null, "篮球场", "P1", "高一", 120, 0, 0, List.of(1L, 2L), 0, null, "final"),
                new SuperScheduleEncoder.Unit("b1", "篮球半决赛", 3, false, true, 2,
                        null, "篮球场", "P1", "高一", 120, 0, 0, List.of(3L, 4L), 1, null, "prelim"),
                new SuperScheduleEncoder.Unit("b2", "篮球三四名", 3, false, true, 2,
                        null, "篮球场", "P1", "高一", 120, 0, 0, List.of(5L, 6L), 1, null, "prelim"));
        var a = svc.advise(enc.encode(ball, windows(), 1, 12)).orElseThrow();
        double maxTask = 0;
        int argmax = 0;
        for (int i = 0; i < a.taskProbs().length; i++) {
            if (a.taskProbs()[i] > maxTask) {
                maxTask = a.taskProbs()[i];
                argmax = i;
            }
        }
        assertTrue(maxTask > 0, "任务权重必须为正");
        assertTrue(argmax >= 0 && argmax < SuperScheduleEncoder.N_TASKS);
        // 专家权重应有区分度（若全相等说明路由退化）
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        for (double v : a.taskProbs()) {
            mn = Math.min(mn, (float) v);
            mx = Math.max(mx, (float) v);
        }
        assertTrue(mx - mn > 1e-4, "九类任务权重应互不相同（路由退化则全等）");
    }

    @Test
    @DisplayName("模型缺失时优雅降级，绝不抛异常")
    void missingModelDegradesGracefully() {
        var svc = new SuperMoeService(true, "classpath:/models", "no_such_super.onnx");
        assertFalse(svc.available());
        var enc = new SuperScheduleEncoder().encode(List.of(
                unit(0, 0, null, "田径场", 60, 0), unit(1, 0, null, "田径场", 60, 0),
                unit(2, 0, null, "田径场", 60, 0)), windows(), 2, 100);
        assertTrue(svc.advise(enc).isEmpty(), "模型缺失应返回空让上层走规则");
    }

    @Test
    @DisplayName("随机场景：任意合法输入都不应崩溃")
    void randomScenariosDoNotCrash() {
        var enc = new SuperScheduleEncoder();
        Random rnd = new Random(42);
        for (int t = 0; t < 30; t++) {
            int n = 3 + rnd.nextInt(60);
            List<SuperScheduleEncoder.Unit> us = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int task = rnd.nextInt(SuperScheduleEncoder.N_TASKS);
                Integer fmt = task == SuperScheduleEncoder.TASK_BALL
                        || task == SuperScheduleEncoder.TASK_KNOCKOUT
                        || task == SuperScheduleEncoder.TASK_RESECOND
                        ? rnd.nextInt(SuperScheduleEncoder.N_FORMATS) : null;
                List<Long> ath = new ArrayList<>();
                int na = rnd.nextInt(30);
                for (int a = 0; a < na; a++) {
                    ath.add((long) rnd.nextInt(200));
                }
                us.add(new SuperScheduleEncoder.Unit("u" + i, "P" + rnd.nextInt(6), task,
                        rnd.nextBoolean(), fmt != null, fmt,
                        rnd.nextInt(4) == 0 ? "blk" + rnd.nextInt(3) : null,
                        "V" + rnd.nextInt(3), "P" + rnd.nextInt(2), "高" + rnd.nextInt(3),
                        10 + rnd.nextInt(200), rnd.nextInt(4) == 0 ? 30 : 0,
                        rnd.nextInt(4) == 0 ? 4 + rnd.nextInt(4) : 0, ath,
                        fmt != null ? rnd.nextInt(3) : null,
                        fmt != null && rnd.nextBoolean() ? "k" + rnd.nextInt(n) : null,
                        rnd.nextInt(4) == 0 ? "resecond" : "main"));
            }
            int days = rnd.nextInt(5) == 0 ? -1 : (rnd.nextInt(5) == 0 ? 0 : 1 + rnd.nextInt(4));
            var e = enc.encode(us, windows(), days, 300);
            assertEquals(n, e.n());
            assertEquals(SuperScheduleEncoder.NODE_FEAT_DIM, e.nodeFeat()[0].length,
                    "特征维度必须与 ONNX 契约一致");
            for (float[] row : e.nodeFeat()) {
                for (float v : row) {
                    assertTrue(Float.isFinite(v), "特征出现非有限值: " + v);
                }
            }
        }
    }
}
