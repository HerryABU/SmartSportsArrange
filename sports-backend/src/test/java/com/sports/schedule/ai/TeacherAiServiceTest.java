package com.sports.schedule.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 教师规避编码器的双端契约测试。
 *
 * <p>与 Python {@code teacher_advisor.py} 逐位对齐：特征顺序、4 类边的通道号、归一化口径。
 * 任何一处不同步，ONNX <b>不会报错</b>，只会让推理结果悄悄变差 —— 这是本项目反复踩过的坑。</p>
 */
@DisplayName("教师规避编码器契约")
class TeacherAiServiceTest {

    private static Set<String> set(String... v) {
        return new LinkedHashSet<>(List.of(v));
    }

    private TeacherAiService.TeacherInput tch(String id, Set<String> classes, boolean head,
                                              double admin, double busy, int prot) {
        return tch(id, classes, head, admin, busy, prot, "G-" + id);
    }

    private TeacherAiService.TeacherInput tch(String id, Set<String> classes, boolean head,
                                              double admin, double busy, int prot, String group) {
        return new TeacherAiService.TeacherInput(id, id, classes, head, admin, busy, prot, 0.0, 0.5, group);
    }

    @Test
    @DisplayName("教师不足 3 人：判定降级，交给规则（模型没有决策空间）")
    void tooFewTeachersDegrades() {
        var enc = TeacherAiService.encode(List.of(
                tch("t1", set("高一1班"), true, 0.0, 0.2, 0),
                tch("t2", set("高一2班"), false, 0.0, 0.3, 1)), set("高一1班"));
        assertTrue(enc.degraded());
        assertTrue(enc.reason().contains("3"), "理由应说明人数不足，实际=" + enc.reason());
    }

    @Test
    @DisplayName("空输入：降级而不是抛异常（编排链路上不能因 AI 崩掉）")
    void emptyInputDegrades() {
        assertTrue(TeacherAiService.encode(List.of(), Set.of()).degraded());
    }

    @Test
    @DisplayName("项目关联度 = 所带班级 ∩ 本批参赛班级 / 本批班级数（该模型最重要的一维）")
    void linkRatioIsExact() {
        // 本批 4 个班级，t1 命中 2 → 0.5；t2 命中 0 → 0.0；t3 命中 4 → 1.0
        var enc = TeacherAiService.encode(List.of(
                tch("t1", set("A", "B"), true, 0.0, 0.1, 0),
                tch("t2", set("E"), false, 0.0, 0.2, 0),
                tch("t3", set("A", "B", "C", "D"), false, 0.0, 0.3, 0)),
                set("A", "B", "C", "D"));
        assertEquals(3, enc.n());
        assertEquals(0.5f, enc.nodeFeat()[0][1], 1e-6);
        assertEquals(0.0f, enc.nodeFeat()[1][1], 1e-6);
        assertEquals(1.0f, enc.nodeFeat()[2][1], 1e-6);
        // 「本班有比赛」指示位应随关联度走
        assertEquals(1f, enc.nodeFeat()[0][7], 1e-6);
        assertEquals(0f, enc.nodeFeat()[1][7], 1e-6);
    }

    @Test
    @DisplayName("班主任 / 行政权重 / 已占课时 三处口径")
    void flagsAreEncoded() {
        var enc = TeacherAiService.encode(List.of(
                tch("t1", set("A"), true, 0.8, 0.9, 3),
                tch("t2", set("B"), false, 0.0, 0.0, 0),
                tch("t3", set("C"), false, 0.5, 0.4, 1)), set("A"));
        assertEquals(1f, enc.nodeFeat()[0][2], 1e-6, "班主任位");
        assertEquals(0.8f, enc.nodeFeat()[0][5], 1e-6, "行政权重");
        assertEquals(0.9f, enc.nodeFeat()[0][3], 1e-6, "已占课时");
        assertEquals(1f, enc.nodeFeat()[0][4], 1e-6, "保护时段数 3/3");
        assertEquals(0f, enc.nodeFeat()[1][2], 1e-6);
    }

    @Test
    @DisplayName("4 类边按契约落通道且对称；不相关的教师之间不应该有边")
    void edgesLandAndAreSymmetric() {
        // t1/t2 同班级 + 同行政层级（都在 t1=0.0）+ 都有保护时段
        // t1/t2 同教研组 G1（必须显式传，不能靠冗余度近似 —— 那是踩过的坑）
        var enc = TeacherAiService.encode(List.of(
                tch("t1", set("A"), true, 0.0, 0.1, 2, "G1"),
                tch("t2", set("A"), true, 0.0, 0.1, 1, "G1"),
                tch("t3", set("Z"), false, 0.7, 0.5, 0, "G3")), set("A"));

        assertEquals(1f, enc.adjByType()[0][0][1], 1e-6, "同班级边");
        assertEquals(1f, enc.adjByType()[0][1][0], 1e-6, "必须对称");
        assertNotEquals(enc.adjByType()[1][0][1], enc.adjByType()[1][2][0], 1e-6,
                "不同教研组之间不应有「同组」边（提示：组信息必须显式传入，不能靠冗余度近似）");
        assertEquals(1f, enc.adjByType()[2][0][1], 1e-6, "同受保护边");
        assertEquals(1f, enc.adjByType()[3][0][1], 1e-6, "同行政层级边");
        for (int t = 0; t < TeacherAiService.N_TYPES; t++) {
            assertEquals(0f, enc.adjByType()[t][0][2], 1e-6, "通道 " + t + " 与无关教师不应连边");
        }
    }

    @Test
    @DisplayName("全部特征必须落在 [0,1]（否则与 Python 归一化口径不一致）")
    void featuresAreNormalized() {
        var enc = TeacherAiService.encode(List.of(
                tch("t1", set("A", "B", "C"), true, 5.0, 9.9, 99),
                tch("t2", set(), false, -1.0, -0.5, -3),
                tch("t3", set("A"), false, 0.5, 0.5, 1)), set("A"));
        for (float[] row : enc.nodeFeat()) {
            for (int i = 0; i < row.length; i++) {
                assertTrue(row[i] >= 0f && row[i] <= 1f, "第 " + i + " 维越界: " + row[i]);
            }
        }
        assertEquals(TeacherAiService.N_TCH_FEAT, enc.nodeFeat()[0].length);
    }

    @Test
    @DisplayName("按避让度降序排序：优先级最高的教师排最前")
    void orderByAversionIsDescending() {
        var advice = new TeacherAiService.Advice(new double[]{0.2, 0.9, 0.5}, 3);
        assertArrayEquals(new int[]{1, 2, 0}, advice.orderByAversion());
    }
}
