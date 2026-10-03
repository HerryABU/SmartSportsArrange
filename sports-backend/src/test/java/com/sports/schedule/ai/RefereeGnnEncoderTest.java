package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 裁判派遣编码器的双端契约测试。
 *
 * <p>Python 侧 {@code referee_advisor.py} 与这里必须逐位对齐：特征顺序、边类型顺序、
 * 归一化口径、对称性。任何一处不同步，ONNX 都<b>不会报错</b>，只会让推理结果悄悄变差
 * ——这是本项目反复踩过的一类坑，所以用断言钉死。</p>
 */
@DisplayName("裁判派遣编码器契约")
class RefereeGnnEncoderTest {

    private final RefereeGnnEncoder encoder = new RefereeGnnEncoder();

    private static Set<String> set(String... v) {
        return new LinkedHashSet<>(List.of(v));
    }

    private RefereeGnnEncoder.RefereeInput ref(String id, Set<String> spec, String unit,
                                               boolean prot, int served) {
        return new RefereeGnnEncoder.RefereeInput(
                id, id, spec, unit, prot, served, 0.5, 1.0, 0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("裁判不足 3 人：判定降级，交给规则派遣（模型没有决策空间）")
    void tooFewRefereesDegrades() {
        var enc = encoder.encode(List.of(
                ref("r1", set("100米"), "甲", false, 0),
                ref("r2", set("跳远"), "乙", false, 1)),
                List.of("100米"), set("甲"));
        assertTrue(enc.degraded());
        assertTrue(enc.reason().contains("3"), "理由应说明人数不足，实际=" + enc.reason());
    }

    @Test
    @DisplayName("空输入：判定降级而不是抛异常（编排链路上不能因 AI 崩掉）")
    void emptyInputDegrades() {
        var enc = encoder.encode(List.of(), List.of(), Set.of());
        assertTrue(enc.degraded());
        assertEquals(0, enc.n());
    }

    @Test
    @DisplayName("专长匹配度 = 交集 / 本批项目数，逐位核对")
    void specialtyMatchIsExactRatio() {
        // 本批 4 个项目：r1 命中 2 个 → 0.5；r2 命中 0 个 → 0.0；r3 命中 4 个 → 1.0
        var enc = encoder.encode(List.of(
                ref("r1", set("100米", "跳远"), "甲", false, 0),
                ref("r2", set("跳高"), "乙", false, 0),
                ref("r3", set("100米", "跳远", "铅球", "800米"), "丙", false, 0)),
                List.of("100米", "跳远", "铅球", "800米"), Set.of("甲"));

        assertEquals(3, enc.n());
        assertEquals(0.5f, enc.nodeFeat()[0][RefereeGnnEncoder.F_SPECIALTY_MATCH], 1e-6);
        assertEquals(0.0f, enc.nodeFeat()[1][RefereeGnnEncoder.F_SPECIALTY_MATCH], 1e-6);
        assertEquals(1.0f, enc.nodeFeat()[2][RefereeGnnEncoder.F_SPECIALTY_MATCH], 1e-6);
    }

    @Test
    @DisplayName("同单位回避、受保护、负载归一三处口径")
    void unitProtectedAndLoadFlags() {
        var enc = encoder.encode(List.of(
                ref("r1", set("100米"), "甲", false, 4),
                ref("r2", set("100米"), "乙", true, 0),
                ref("r3", set("100米"), "甲", false, 2)),
                List.of("100米"), set("甲", "丙"));

        assertEquals(1.0f, enc.nodeFeat()[0][RefereeGnnEncoder.F_SAME_UNIT], 1e-6);
        assertEquals(0.0f, enc.nodeFeat()[1][RefereeGnnEncoder.F_SAME_UNIT], 1e-6);
        assertEquals(1.0f, enc.nodeFeat()[1][RefereeGnnEncoder.F_PROTECTED], 1e-6);
        // 负载按本批最大值(4)归一
        assertEquals(1.0f, enc.nodeFeat()[0][RefereeGnnEncoder.F_LOAD], 1e-6);
        assertEquals(0.0f, enc.nodeFeat()[1][RefereeGnnEncoder.F_LOAD], 1e-6);
    }

    @Test
    @DisplayName("4 类边各按契约落到对应通道，且必须对称（无向图）")
    void edgesLandOnCorrectChannelsAndAreSymmetric() {
        var enc = encoder.encode(List.of(
                ref("r1", set("100米"), "甲", true, 3),
                ref("r2", set("100米"), "甲", true, 3),
                ref("r3", set("跳远"), "乙", false, 0)),
                List.of("100米"), set("甲"));

        // r1-r2：同专长(0) + 同单位(1) + 双受保护(2) + 负载相近(3) 全部命中
        for (int t = 0; t < RefereeGnnEncoder.N_TYPES; t++) {
            assertEquals(1f, enc.adjByType()[t][0][1], 1e-6, "通道 " + t + " 应命中");
            assertEquals(1f, enc.adjByType()[t][1][0], 1e-6, "通道 " + t + " 反向必须对称");
        }
        // r1-r3：专长不同、单位不同、r3 未受保护、负载差 3 → 四条边全不命中
        for (int t = 0; t < RefereeGnnEncoder.N_TYPES; t++) {
            assertEquals(0f, enc.adjByType()[t][0][2], 1e-6, "通道 " + t + " 不应命中");
        }
    }

    @Test
    @DisplayName("mask / typeMask 全 1；形状与裁判数一致")
    void shapesAndMasks() {
        var enc = encoder.encode(List.of(
                ref("r1", set("100米"), "甲", false, 0),
                ref("r2", set("跳远"), "乙", false, 1),
                ref("r3", set("铅球"), "丙", false, 2)),
                List.of("100米"), Set.of("甲"));

        assertEquals(3, enc.nodeFeat().length);
        assertEquals(RefereeGnnEncoder.N_REF_FEAT, enc.nodeFeat()[0].length);
        assertEquals(RefereeGnnEncoder.N_TYPES, enc.adjByType().length);
        assertEquals(3, enc.adjByType()[0].length);
        assertEquals(3, enc.mask().length);
        for (float v : enc.mask()) {
            assertEquals(1f, v, 1e-6);
        }
        for (float v : enc.typeMask()) {
            assertEquals(1f, v, 1e-6);
        }
        assertFalse(enc.degraded());
    }

    @Test
    @DisplayName("特征值必须全部落在 [0,1]（否则与 Python 的归一化口径不一致）")
    void allFeaturesAreNormalized() {
        var enc = encoder.encode(List.of(
                ref("r1", set("100米"), "甲", true, 99),
                ref("r2", set("100米", "跳远"), "乙", false, 0),
                ref("r3", set(), "丙", false, 1)),
                List.of("100米", "跳远"), Set.of("甲"));

        for (float[] row : enc.nodeFeat()) {
            for (int i = 0; i < row.length; i++) {
                assertTrue(row[i] >= 0f && row[i] <= 1f,
                        "第 " + i + " 维越界: " + row[i]);
            }
        }
    }
}
