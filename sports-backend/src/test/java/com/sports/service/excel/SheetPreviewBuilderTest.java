package com.sports.service.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览报告组装（{@link SheetPreviewBuilder}）单测。
 *
 * <p><b>为什么盯这一层</b>：用户看到的「这张表是什么类型、哪些列没认出来、能不能导、为什么不导」
 * 全部由这里拼出来。它和导入链路共用同一份
 * {@link ExcelColumnMapping} / {@link SheetTypeResolver}，所以只要这里对了，
 * 「预览怎么说，导入就怎么写」才成立；log 里看不出差别，报告里一眼可见。</p>
 *
 * <p>这里刻意用<b>模板的真实表头</b>（多表导入模板里的「填写说明」「项目表（表格2）」「运动项目表」），
 * 而不是随意编几列 —— 复现的就是「照模板填的表，导入前看到的那一屏」。</p>
 */
@DisplayName("多表导入：预览报告组装")
class SheetPreviewBuilderTest {

    /** 造一行原始表头 + 一行数据（rows 含表头行，与 hasHeader=true 的读法一致）。 */
    private static SheetJob job(String sheetName, List<String> headers, Map<Integer, String> firstDataRow) {
        Map<Integer, String> headerRow = new LinkedHashMap<>();
        for (int c = 0; c < headers.size(); c++) {
            headerRow.put(c, headers.get(c));
        }
        Map<Integer, String> dataRow = new LinkedHashMap<>(firstDataRow);
        return new SheetJob(0, "多表导入模板.xlsx", 0, sheetName, headers,
                List.of(headerRow, dataRow), null, Map.of(), true);
    }

    @Test
    @DisplayName("表格2 项目表：判成 event，19 列全部有落点，能导且没有未识别列")
    void table2PreviewIsClean() {
        List<String> headers = List.of(
                "代码", "项目", "是否田径", "道次", "顺序号", "每组次几人", "捆绑字母", "并行数", "场地编码",
                "性别", "年级组", "是否团体", "团体人数", "场地", "最大用时(分)", "间隔(分)", "组次裁判数量", "抽签(是/否)", "最大报名人数");
        Map<Integer, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            row.put(i, "示例");
        }
        Map<String, Object> s = SheetPreviewBuilder.build(job("项目表（表格2）", headers, row), true, 5, true);

        assertEquals("event", s.get("type"));
        assertEquals("header", s.get("resolvedBy"), "表格2 靠表头指纹判定");
        assertEquals(Boolean.TRUE, s.get("importable"));
        assertNull(s.get("reason"), "能导的表不该给出跳过原因");
        assertTrue(((List<?>) s.get("unmappedHeaders")).isEmpty(), "表格2 不该有未识别列:" + s.get("unmappedHeaders"));
    }

    @Test
    @DisplayName("运动项目表（9 列精简版）：判成 eventsimple，「性别 / 最大报名人数」不再是未识别列")
    void eventSimplePreviewIsClean() {
        List<String> headers = List.of("项目代码", "项目名称", "每组人数", "每批组数", "项目类型", "场地号",
                "每批所需时间(分)", "性别", "最大报名人数");
        Map<Integer, String> row = Map.of(0, "100M", 1, "100米", 2, "1", 3, "6", 4, "径赛", 5, "TRACK",
                6, "20", 7, "男子组", 8, "");
        Map<String, Object> s = SheetPreviewBuilder.build(job("运动项目表", headers, row), true, 5, true);

        assertEquals("eventsimple", s.get("type"));
        assertEquals(Boolean.TRUE, s.get("importable"));
        assertTrue(((List<?>) s.get("unmappedHeaders")).isEmpty(), "精简项目表不该有未识别列:" + s.get("unmappedHeaders"));
    }

    @Test
    @DisplayName("填写说明页：判成 notice，明确说明「无业务数据、自动跳过」，且不标为可导入")
    void noticePreviewExplainsItself() {
        Map<String, Object> s = SheetPreviewBuilder.build(
                job("填写说明", List.of("字段", "填写说明"), Map.of(0, "重跑", 1, "同一份工作簿可重复导入")), true, 5, true);

        assertEquals("notice", s.get("type"));
        assertEquals(Boolean.FALSE, s.get("importable"));
        String reason = String.valueOf(s.get("reason"));
        assertTrue(reason.contains("不参与导入"), "说明页要说清不参与导入，而不是回一句「可手动指定类型」: " + reason);
        // 说明页本身的两列也算「已识别」（有落点），但不属于可导入表
        assertTrue(((List<?>) s.get("mappedFields")).size() >= 1);
        assertFalse(((List<?>) s.get("unmappedHeaders")).contains("填写说明"));
    }

    @Test
    @DisplayName("认不出的表：如实报告类型不足，不猜成可导入")
    void unknownSheetStaysUnknown() {
        Map<String, Object> s = SheetPreviewBuilder.build(
                job("随便一张表", List.of("甲", "乙"), Map.of(0, "1", 1, "2")), true, 5, true);

        assertNull(s.get("type"));
        assertFalse((Boolean) s.get("importable"));
        assertTrue(String.valueOf(s.get("reason")).contains("无法识别该表类型"),
                "认不出的表要如实说明，而不是把它当成可导入表");
    }
}
