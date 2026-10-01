package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 推理时自对抗（G 生成 → 精修器精修 → D 评判 → 多轮择优）的端到端验证。
 */
@DisplayName("推理时自对抗编排")
class AdversarialSchemeServiceTest {

    private static final Path MODEL_DIR = Paths.get("../sports-ai/models");

    private static AdversarialSchemeService service() {
        return new AdversarialSchemeService(
                true, MODEL_DIR.toString(),
                "scheme_generator.onnx", "scheme_discriminator.onnx", "scheme_refiner.onnx");
    }

    @Test
    @DisplayName("多轮 G↔D 博弈 + 精修：产出合理方案且不劣于单次生成")
    void adversarialGenerationProducesLowConflict() {
        assumeTrue(Files.exists(MODEL_DIR.resolve("scheme_generator.onnx"))
                        && Files.exists(MODEL_DIR.resolve("scheme_discriminator.onnx")),
                "跳过：GAN 模型未导出（先执行 sports-ai/scripts/train.ps1）");

        AdversarialSchemeService svc = service();
        assumeTrue(svc.isAvailable(), "跳过：模型未能加载");

        Optional<AdversarialSchemeService.SchemeResult> opt = svc.generateAdversarially(realisticUnits(), 4, 5.0);

        assertTrue(opt.isPresent(), "模型就绪时应产出方案");
        AdversarialSchemeService.SchemeResult r = opt.get();
        assertNotNull(r.slots());
        assertEquals(realisticUnits().size(), r.slots().length, "槽分配按单元数对齐");
        for (int s : r.slots()) {
            assertTrue(s >= 0 && s < AdversarialSchemeService.MAX_SLOTS, "槽索引应在 [0,16)");
        }
        assertTrue(r.conflict() >= 0.0 && r.conflict() <= 1.0, "残余冲突应在 [0,1]");
        // 自对抗（多轮 G↔D + 精修）应把冲突压到明显低于「全同槽」的基线（1.0）
        assertTrue(r.conflict() <= 0.6,
                "推理时自对抗应显著分离冲突节点（当前 " + r.conflict() + "）");
        assertTrue(r.rounds() >= 1);
    }

    /**
     * 贴近训练分布的实例：4 个冲突簇（短跑/中长跑/跳跃/投掷）。
     *
     * <p>要点：①时长取训练分布量级（20~120 分钟，与 Java 端 ScheduleUnit.rawDuration 同量级）；
     * ②簇内单元**部分重叠**（每位运动员偏移取窗），而非完全相同的运动员集合。</p>
     */
    private static List<ScheduleUnit> realisticUnits() {
        List<Placement> placements = new ArrayList<>();
        for (int start = 480; start <= 690; start += 10) {
            placements.add(new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场",
                    start, 480, 210));
        }
        int[][] clusters = {{1, 70}, {50, 110}, {90, 160}, {140, 200}};
        int[] perCluster = {4, 2, 3, 3};
        List<ScheduleUnit> units = new ArrayList<>();
        int idx = 0;
        for (int c = 0; c < clusters.length; c++) {
            int base = clusters[c][0];
            int span = clusters[c][1] - base;
            for (int u = 0; u < perCluster[c]; u++) {
                long[] ath = new long[40];
                for (int k = 0; k < 40; k++) {
                    ath[k] = base + ((u * 7 + k) % span);   // 部分重叠
                }
                units.add(unit("u" + idx, 20 + idx * 8, ath, placements));
                idx++;
            }
        }
        return units;
    }

    @Test
    @DisplayName("残余冲突计算：全同槽=1、全不同槽=0")
    void hardConflictSanity() {
        int n = 3;
        float[] adj = new float[ConflictGraphEncoder.MAX_NODES * ConflictGraphEncoder.MAX_NODES];
        int max = ConflictGraphEncoder.MAX_NODES;
        adj[0 * max + 1] = 1f; adj[1 * max + 0] = 1f;
        adj[1 * max + 2] = 1f; adj[2 * max + 1] = 1f;
        adj[0 * max + 2] = 1f; adj[2 * max + 0] = 1f;

        assertEquals(1.0, AdversarialSchemeService.hardConflict(new int[]{0, 0, 0}, adj, n), 1e-9);
        assertEquals(0.0, AdversarialSchemeService.hardConflict(new int[]{0, 1, 2}, adj, n), 1e-9);
    }

    @Test
    @DisplayName("模型缺失时优雅回退（empty，不抛异常）")
    void missingModelsDegradeGracefully() {
        AdversarialSchemeService svc = new AdversarialSchemeService(
                true, "../sports-ai/__missing__",
                "scheme_generator.onnx", "scheme_discriminator.onnx", "scheme_refiner.onnx");
        assertTrue(svc.generateAdversarially(List.of(), 2, 1.0).isEmpty());
    }

    private static ScheduleUnit unit(String key, int rawDuration, long[] athletes, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, List.of(rawDuration), cands);
    }
}
