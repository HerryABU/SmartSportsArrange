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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>地狱级</b>端到端测试：3 年级 × 8 班 × 30 人 = 720 名运动员、9 个真实田径项目、
 * 含预赛/决赛（复赛）与「每批时长 × 组数 = 全部时长」的真实时长模型。
 *
 * <p>验证 AI 模型（GAN 生成 → 精修器精修 → 判别器评判 → 多轮自对抗择优）在真实规模
 * 数据结构上的表现，并对比「单次生成基线冲突」与「自对抗后冲突」。</p>
 */
@DisplayName("地狱级场景（720 人）推理时自对抗")
class HellScenarioAdversarialTest {

    private static final Path MODEL_DIR = Paths.get("../sports-ai/models");

    /** 真实田径项目：code, track, pool, 每批时长(分钟), 每批容量, 是否有复赛, 同组 */
    private record EventDef(String code, boolean track, String pool, int batchMinutes,
                            int heatCapacity, boolean hasFinal, String group) {
    }

    private static final EventDef[] EVENTS = {
            new EventDef("50m", true, "径赛", 15, 8, true, null),
            new EventDef("100m", true, "径赛", 20, 8, true, null),
            new EventDef("800m", true, "径赛", 45, 8, false, null),
            new EventDef("1000m", true, "径赛", 50, 8, false, null),
            new EventDef("4x100", true, "径赛", 30, 8, true, null),
            new EventDef("SLJ", false, "田赛", 60, 6, false, "田赛跳跃组"),
            new EventDef("HJ", false, "田赛", 90, 6, false, "田赛跳跃组"),
            new EventDef("PU", false, "田赛", 40, 10, false, "田赛力量组"),
            new EventDef("SP", false, "田赛", 50, 6, false, "田赛投掷组"),
    };

    private static final int N_GRADES = 3;
    private static final int N_CLASSES = 8;
    private static final int PER_CLASS = 30;
    private static final int N_ATHLETES = N_GRADES * N_CLASSES * PER_CLASS;   // 720

    @Test
    @DisplayName("720 人地狱场景：自对抗把冲突压到不高于单次生成基线")
    void hellScaleAdversarial() {
        assumeTrue(Files.exists(MODEL_DIR.resolve("scheme_generator.onnx"))
                        && Files.exists(MODEL_DIR.resolve("scheme_discriminator.onnx")),
                "跳过：GAN 模型未导出");

        AdversarialSchemeService svc = new AdversarialSchemeService(
                true, MODEL_DIR.toString(),
                "scheme_generator.onnx", "scheme_discriminator.onnx", "scheme_refiner.onnx");
        assumeTrue(svc.isAvailable(), "跳过：模型未能加载");

        List<ScheduleUnit> units = buildHellUnits();
        assertTrue(units.size() >= 30, "地狱场景应有 ≥30 个单元（含预赛/决赛），实际 " + units.size());

        Optional<AdversarialSchemeService.SchemeResult> opt = svc.generateAdversarially(units, 6, 5.0);
        assertTrue(opt.isPresent(), "应产出方案");
        AdversarialSchemeService.SchemeResult r = opt.get();
        assertEquals(units.size(), r.slots().length);
        for (int s : r.slots()) {
            assertTrue(s >= 0 && s < AdversarialSchemeService.MAX_SLOTS);
        }
        assertTrue(r.conflict() >= 0.0 && r.conflict() <= 1.0);
        // 自对抗应不劣于单次生成基线
        assertTrue(r.conflict() <= r.conflictBefore() + 1e-6,
                "自对抗不应比单次生成更差（基线 " + r.conflictBefore() + " vs " + r.conflict() + "）");
    }

    /**
     * 构造地狱级单元列表：真实时长模型（全部时长 = ceil(人数/每批容量) × 每批时长）+ 预赛/决赛。
     */
    private static List<ScheduleUnit> buildHellUnits() {
        List<Placement> placements = new ArrayList<>();
        for (int day = 1; day <= 2; day++) {
            for (int win = 1; win <= 2; win++) {
                int cap = win == 1 ? 180 : 150;
                int widx = (day - 1) * 2 + win;
                for (int s = 0; s < 3; s++) {
                    placements.add(new Placement("田赛", s, widx, day, "2026-01-01",
                            win == 1 ? "上午" : "下午", "田径场", 480, 480, cap));
                }
                placements.add(new Placement("径赛", 0, widx, day, "2026-01-01",
                        win == 1 ? "上午" : "下午", "田径场", 480, 480, cap));
            }
        }

        // 报名：按专长（同簇倾向，制造真实兼项冲突簇）——绝大部分 1 项、少部分 2 项、
        // 每班至少 1 人 3 项。与 Python 侧 hell.py 的报名结构一致。
        int[][] specialties = {
                {0, 1, 4},   // 短跑：50m / 100m / 4x100
                {2, 3, 1},   // 中长跑：800m / 1000m / 100m
                {5, 6, 0},   // 跳跃：立定跳远 / 跳高 / 50m
                {7, 8, 1},   // 力量：引体向上 / 铅球 / 100m
        };
        int[] specWeight = {30, 15, 20, 25};
        Random rng = new Random(20260918L);
        List<List<Long>>[] participants = new List[N_GRADES];
        for (int g = 0; g < N_GRADES; g++) {
            List<List<Long>> perGrade = new ArrayList<>();
            for (EventDef e : EVENTS) perGrade.add(new ArrayList<>());
            participants[g] = perGrade;
        }
        long aid = 1;
        for (int g = 0; g < N_GRADES; g++) {
            for (int c = 0; c < N_CLASSES; c++) {
                for (int k = 0; k < PER_CLASS; k++) {
                    int want = 1;
                    if (k < PER_CLASS * 0.25) want = 2;
                    if (k == PER_CLASS - 1) want = 3;   // 每班至少 1 人报 3 项
                    int[] pref = specialties[weightedPick(specWeight, rng)];
                    List<Integer> picks = new ArrayList<>();
                    for (int e : pref) {
                        if (picks.size() >= want) break;
                        if (!picks.contains(e)) picks.add(e);
                    }
                    int guard = 0;
                    while (picks.size() < want && guard++ < 50) {
                        int e = rng.nextInt(EVENTS.length);
                        if (!picks.contains(e)) picks.add(e);
                    }
                    for (int e : picks) participants[g].get(e).add(aid);
                    aid++;
                }
            }
        }

        List<ScheduleUnit> units = new ArrayList<>();
        for (int e = 0; e < EVENTS.length; e++) {
            EventDef ev = EVENTS[e];
            for (int g = 0; g < N_GRADES; g++) {
                List<Long> parts = participants[g].get(e);
                if (parts.isEmpty()) continue;
                String grade = "高" + (g + 1);
                long[] arr = parts.stream().mapToLong(Long::longValue).sorted().toArray();
                int heats = Math.max(1, (int) Math.ceil(arr.length / (double) ev.heatCapacity()));
                int totalDuration = heats * ev.batchMinutes();
                String round = ev.hasFinal() ? "预赛" : "决赛";
                units.add(new ScheduleUnit(ev.code() + "@" + grade + "@" + round, (long) e,
                        ev.code() + "(" + round + ")", grade, ev.track(), ev.pool(),
                        ev.group(), 5, totalDuration, Math.max(10, totalDuration / 3),
                        arr, List.of(totalDuration), placements));
                if (ev.hasFinal()) {
                    long[] finalists = new long[Math.min(8, arr.length)];
                    System.arraycopy(arr, 0, finalists, 0, finalists.length);
                    units.add(new ScheduleUnit(ev.code() + "@" + grade + "@决赛", (long) e,
                            ev.code() + "(决赛)", grade, ev.track(), ev.pool(),
                            ev.group(), 5, ev.batchMinutes(), 10,
                            finalists, List.of(ev.batchMinutes()), placements));
                }
            }
        }
        return units;
    }

    /** 按权重随机取索引（确定性）。 */
    private static int weightedPick(int[] weights, Random rng) {
        int total = 0;
        for (int w : weights) total += w;
        int r = rng.nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) return i;
        }
        return weights.length - 1;
    }
}
