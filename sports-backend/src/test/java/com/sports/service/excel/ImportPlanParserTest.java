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
 * 导入计划解析 / 匹配 / 列建议的单测。
 *
 * <p>这层是「按用户指定重新解析」的入口：前端把 sheets JSON 打回来，任何一处解析走偏，
 * 用户都会看到「我明明选了 grade 表，怎么还判成 athlete」。所以解析、匹配、建议三件事各测一遍。</p>
 */
class ImportPlanParserTest {

    /** 组装一份计划：第 1 参是 hasHeader，其余是「一张表一个 Map」的覆盖项。 */
    private static Map<String, Object> plan(Object headerFlag, Map<String, Object>... sheets) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("hasHeader", headerFlag);
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Map<String, Object> s : sheets) {
            list.add(s);
        }
        plan.put("sheets", list);
        return plan;
    }

    /** 一张表的覆盖项。 */
    private static Map<String, Object> sheet(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("空计划：默认有表头、无覆盖项")
    void emptyPlan() {
        ImportPlanParser.PlanInfo info = ImportPlanParser.parse(null);
        assertTrue(info.hasHeader());
        assertTrue(info.entries().isEmpty());

        assertEquals(true, ImportPlanParser.parse(Map.of()).hasHeader());
    }

    @Test
    @DisplayName("hasHeader=false 能被识别出来")
    void hasHeaderFalse() {
        assertFalse(ImportPlanParser.hasHeader(Map.of("hasHeader", false)));
        assertFalse(ImportPlanParser.hasHeader(Map.of("hasHeader", "false")));
        assertTrue(ImportPlanParser.hasHeader(Map.of("hasHeader", true)));
        assertTrue(ImportPlanParser.hasHeader(Map.of("hasHeader", "true")));
        assertTrue(ImportPlanParser.hasHeader(Map.of()));
    }

    @Test
    @DisplayName("sheets 既可以是数组也可以是 JSON 字符串")
    void sheetsBothShapes() {
        Map<String, Object> a = plan(true, sheet("file", "名单.xlsx"));
        ImportPlanParser.PlanInfo fromList = ImportPlanParser.parse(a);
        assertEquals(1, fromList.entries().size());

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sheets", "[{\"file\":\"名单.xlsx\",\"sheet\":\"Sheet1\",\"type\":\"roster\"}]");
        ImportPlanParser.PlanInfo fromString = ImportPlanParser.parse(b);
        assertEquals(1, fromString.entries().size());
        assertEquals("roster", fromString.entries().get(0).type());
        assertEquals("名单.xlsx", fromString.entries().get(0).file());
    }

    @Test
    @DisplayName("数字与字符串混写的序号都能解析成 int")
    void looseNumbers() {
        Map<String, Object> p = plan(true, sheet("fileIndex", 0, "sheetIndex", "1", "type", "athlete"));
        ImportPlanParser.PlanEntry e = ImportPlanParser.parse(p).entries().get(0);
        assertEquals(0, e.fileIndex());
        assertEquals(1, e.sheetIndex());
        assertEquals("athlete", e.type());
    }

    @Test
    @DisplayName("include=false 的计划项会被如实记住")
    void includeFalse() {
        Map<String, Object> p = plan(true, sheet("file", "a.xlsx", "sheet", "Sheet1", "include", false));
        assertFalse(ImportPlanParser.parse(p).entries().get(0).isIncluded());

        Map<String, Object> p2 = plan(true, sheet("file", "a.xlsx", "include", "0"));
        assertFalse(ImportPlanParser.parse(p2).entries().get(0).isIncluded());

        Map<String, Object> p3 = plan(true, sheet("file", "a.xlsx", "include", true));
        assertTrue(ImportPlanParser.parse(p3).entries().get(0).isIncluded());

        // 没写 include 时按「参与」处理
        Map<String, Object> p4 = plan(true, sheet("file", "a.xlsx"));
        assertTrue(ImportPlanParser.parse(p4).entries().get(0).isIncluded());
    }

    @Test
    @DisplayName("匹配优先用序号，序号对不上才用名字兜底")
    void matchOrder() {
        ImportPlanParser.PlanEntry byIndex = new ImportPlanParser.PlanEntry(0, "旧名.xlsx", 2, "旧表名",
                "athlete", Map.of(), true);
        ImportPlanParser.PlanEntry byName = new ImportPlanParser.PlanEntry(null, "名单.xlsx", null, "全名单",
                "roster", Map.of(), true);
        List<ImportPlanParser.PlanEntry> entries = List.of(byIndex, byName);

        // 文件被重命名成「名单.xlsx」、Sheet 被改名成「全名单」后，靠序号仍然命中（人工指定不失效）
        assertEquals("athlete",
                ImportPlanParser.match(entries, 0, "名单.xlsx", new ExcelSheetRef(2, "全名单")).type());
        // 纯名字匹配也能命中
        assertEquals("roster",
                ImportPlanParser.match(entries, 1, "名单.xlsx", new ExcelSheetRef(0, "全名单")).type());
        // 完全无关的表 → null（走自动判定）
        assertNull(ImportPlanParser.match(entries, 3, "别的.xlsx", new ExcelSheetRef(1, "其它")));
        assertTrue(ImportPlanParser.match(List.of(), 0, "x", new ExcelSheetRef(0, "y")) == null);
    }

    @Test
    @DisplayName("columnMap 支持 Map 与 JSON 字符串两种形态，且忽略空值")
    void columnMapShapes() {
        Map<String, String> fromMap = ImportPlanParser.columnMapOf(Map.of("0", "name", "1", "grade"));
        assertEquals("name", fromMap.get("0"));
        assertEquals("grade", fromMap.get("1"));

        Map<String, String> fromString = ImportPlanParser.columnMapOf("{\"0\":\"name\",\"2\":\"\"}");
        assertEquals("name", fromString.get("0"));
        assertFalse(fromString.containsKey("2")); // 空字段值直接丢掉，避免下游按空字段取值

        assertTrue(ImportPlanParser.columnMapOf(null).isEmpty());
        assertTrue(ImportPlanParser.columnMapOf("这不是 JSON").isEmpty());
    }

    @Test
    @DisplayName("列建议：精确命中排最前，认不出的排最后")
    void columnAdvice() {
        List<ImportColumnAdvisor.ColumnAdvice> advice =
                ImportColumnAdvisor.advise("athlete", List.of("姓名", "班级", "联系方式1"));

        assertEquals(3, advice.size());
        assertEquals("姓名", advice.get(0).header());
        assertEquals("name", advice.get(0).field());
        assertTrue(advice.get(0).matched());

        assertEquals("className", advice.get(1).field());

        // 「联系方式1」在 athlete 的字段里没有任何一个精确/包含命中 → 不自动落点，但候选仍在
        assertEquals(null, advice.get(2).field());
        assertFalse(advice.get(2).matched());
        assertFalse(advice.get(2).options().isEmpty());
    }

    @Test
    @DisplayName("同一列在不同类型下给出不同首选（user 表的「姓名」要落到 realName）")
    void typeAwareSuggestion() {
        assertEquals("realName", ImportColumnAdvisor.advise("user", List.of("姓名")).get(0).field());
        assertEquals("name", ImportColumnAdvisor.advise("athlete", List.of("姓名")).get(0).field());
        // class 表读 name（班级名称），全局匹配会把「班级」落到 className（处理器不读）
        assertEquals("name", ImportColumnAdvisor.advise("class", List.of("班级")).get(0).field());
    }

    @Test
    @DisplayName("候选顺序：精确 → 包含 → 无，同级保持类型字段声明顺序")
    void candidateOrder() {
        List<ImportColumnAdvisor.FieldOption> options =
                ImportColumnAdvisor.advise("athlete", List.of("姓名")).get(0).options();
        assertTrue(!options.isEmpty());
        assertEquals(ImportColumnAdvisor.RANK_ALIAS_EXACT, options.get(0).rank());

        // 单调不降
        int prev = -1;
        for (ImportColumnAdvisor.FieldOption o : options) {
            assertTrue(o.rank() >= prev, "候选等级必须单调不降");
            prev = o.rank();
        }
    }
}
