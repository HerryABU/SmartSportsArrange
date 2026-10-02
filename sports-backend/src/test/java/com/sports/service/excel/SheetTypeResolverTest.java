package com.sports.service.excel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表类型判定（{@link SheetTypeResolver}）单测。
 *
 * <p><b>为什么必须钉死「填写说明」这类说明页</b>：系统自带的多表模板里就有一张「填写说明」页，
 * 表头是「字段 / 填写说明」。它不属于任何业务类型，旧逻辑一律判成「未识别」，
 * 于是预览里出现「无法识别该表类型，可在列表里手动指定类型」——
 * 可这张表<b>本来就没有要导的数据</b>，让人去指定类型是误导，还会把顶部「已识别类型」统计做成虚高。</p>
 *
 * <p>本组用例同时守住两条边界：说明页要认出来，业务表（哪怕列里有「说明」「备注」字样）不能被误判成说明页。</p>
 */
@DisplayName("多表导入：表类型判定（表头指纹 / Sheet 名 / 说明页）")
class SheetTypeResolverTest {

    /** 表格2 项目表模板的 19 列（顺序与「项目表导入模板_表格2」一致）。 */
    private static final List<String> TABLE2_HEADERS = List.of(
            "代码", "项目", "是否田径", "道次", "顺序号", "每组次几人", "捆绑字母", "并行数", "场地编码",
            "性别", "年级组", "是否团体", "团体人数", "场地", "最大用时(分)", "间隔(分)", "组次裁判数量", "抽签(是/否)", "最大报名人数");

    /** 运动项目表（精简版）模板 9 列。 */
    private static final List<String> EVENT_SIMPLE_HEADERS = List.of(
            "项目代码", "项目名称", "每组人数", "每批组数", "项目类型", "场地号", "每批所需时间(分)", "性别", "最大报名人数");

    private static final List<String> ROSTER_HEADERS = List.of("年级", "班级", "姓名", "学号", "性别");

    @Test
    @DisplayName("模板自带的「填写说明」页：Sheet 名命中即判 notice")
    void noticeDetectedBySheetName() {
        assertEquals("notice", SheetTypeResolver.resolve("填写说明", List.of("字段", "填写说明"), null));
        assertEquals("notice", SheetTypeResolver.resolve("使用说明", List.of("字段", "填写说明"), null));
        assertEquals("notice", SheetTypeResolver.resolve("instruction", List.of("字段", "填写说明"), null));
    }

    @Test
    @DisplayName("没有 Sheet 名时，表头本身是「字段 / 填写说明」也能判成 notice")
    void noticeDetectedByHeaders() {
        assertEquals("notice", SheetTypeResolver.resolve(null, List.of("字段", "填写说明"), null));
        assertEquals("notice", SheetTypeResolver.resolve("", List.of("字段", "填写说明"), null));
    }

    @Test
    @DisplayName("表头指纹优先于 Sheet 名：名叫「填写说明」但列是项目表 → 判成项目表")
    void headerFingerprintWinsOverSheetName() {
        assertEquals("eventsimple", SheetTypeResolver.resolve("填写说明", EVENT_SIMPLE_HEADERS, null));
        assertEquals("event", SheetTypeResolver.resolve("填写说明", TABLE2_HEADERS, null));
    }

    @Test
    @DisplayName("业务表不会被误判成说明页（哪怕列名带「说明 / 备注」字样）")
    void businessTablesNeverBecomeNotice() {
        assertEquals("roster", SheetTypeResolver.resolve(null, ROSTER_HEADERS, null));
        assertEquals("eventsimple", SheetTypeResolver.resolve(null, EVENT_SIMPLE_HEADERS, null));
        assertEquals("event", SheetTypeResolver.resolve(null, TABLE2_HEADERS, null));
        assertEquals("score", SheetTypeResolver.resolve(null,
                List.of("项目编码", "运动员号码", "运动员姓名", "成绩", "组别", "道次", "风速", "备注"), null));
    }

    @Test
    @DisplayName("表格2 全 19 列：不落「未识别列」，全部落到 event 类型字段")
    void table2AllColumnsRecognized() {
        Map<String, String> map = SheetTypeResolver.autoColumnMap("event", TABLE2_HEADERS);
        assertEquals(TABLE2_HEADERS.size(), map.size(), "表格2 每一列都必须有落点，否则导入时静默丢列");
        assertEquals("code", map.get("0"));
        assertEquals("name", map.get("1"));
        assertEquals("track", map.get("2"));
        assertEquals("laneCount", map.get("3"));
        assertEquals("sortOrder", map.get("4"));
        assertEquals("groupSize", map.get("5"));
        assertEquals("bundleGroup", map.get("6"));
        assertEquals("concurrency", map.get("7"));
        assertEquals("defaultVenueCode", map.get("8"));
        assertEquals("genderLimit", map.get("9"));
        assertEquals("gradeGroup", map.get("10"));
        assertEquals("team", map.get("11"));
        assertEquals("teamMembers", map.get("12"));
        // 「场地」必须落到 defaultVenue（场地名称），不能被「场地编码」的包含匹配抢走
        assertEquals("defaultVenue", map.get("13"));
        assertEquals("maxDurationMinutes", map.get("14"));
        assertEquals("intervalMinutes", map.get("15"));
        assertEquals("refereesPerGroup", map.get("16"));
        assertEquals("drawLots", map.get("17"));
        assertEquals("maxParticipants", map.get("18"));
        assertTrue(SheetPreviewBuilder.unmappedHeaders(TABLE2_HEADERS, map).isEmpty(),
                "表格2 不该有任何「未识别列」");
    }

    @Test
    @DisplayName("运动项目表 9 列：性别落 gender、最大报名人数落 maxParticipants（旧实现这两列是未识别列）")
    void eventSimpleTrailingColumnsRecognized() {
        Map<String, String> map = SheetTypeResolver.autoColumnMap("eventsimple", EVENT_SIMPLE_HEADERS);
        assertEquals(EVENT_SIMPLE_HEADERS.size(), map.size());
        assertEquals("gender", map.get("7"));
        assertEquals("maxParticipants", map.get("8"));
        assertTrue(SheetPreviewBuilder.unmappedHeaders(EVENT_SIMPLE_HEADERS, map).isEmpty(),
                "精简项目表不该有任何「未识别列」");
    }

    @Test
    @DisplayName("说明页：能判出类型，但不该被当成可导入表")
    void noticeIsNotImportable() {
        assertEquals("notice", SheetTypeResolver.resolve("填写说明", List.of("字段", "填写说明"), null));
        assertNotNull(SheetPreviewBuilder.skipReason("notice", Map.of("0", "field", "1", "note")),
                "说明页要给出「不参与导入」的原因，而不是回一句「可在列表里手动指定类型」");
    }
}
