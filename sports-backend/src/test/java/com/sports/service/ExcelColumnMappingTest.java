package com.sports.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.sports.service.excel.ExcelColumnMapping;

/**
 * 列映射（
 * {@link ExcelColumnMapping}）单测。
 *
 * <p><b>为什么必须有这组用例</b>：多表导入靠表头自动映射，而旧实现是「按别名表顺序取首个包含命中」，
 * 会让更具体的列名被更短的别名抢走——「项目名称」「项目类型」都被 {@code eventCode} 的别名「项目」命中。
 * 症状是<b>导入成功、但字段全空或串列，全程不报错</b>。这类 bug 只能靠断言把映射结果钉死。</p>
 */
@DisplayName("Excel 列映射（精确优先 + 类型优先）")
class ExcelColumnMappingTest {

    @Test
    @DisplayName("全局匹配：精确优先，不让短别名抢走更具体的列名")
    void globalMatchPrefersExact() {
        assertEquals("eventName", ExcelColumnMapping.matchColumnName("项目名称"));
        assertEquals("eventCode", ExcelColumnMapping.matchColumnName("项目代码"));
        assertEquals("eventCode", ExcelColumnMapping.matchColumnName("项目编码"));
        assertEquals("category", ExcelColumnMapping.matchColumnName("项目类型"));
        assertEquals("name", ExcelColumnMapping.matchColumnName("姓名"));
        assertEquals("className", ExcelColumnMapping.matchColumnName("班级"));
        assertNull(ExcelColumnMapping.matchColumnName("这件事跟字段无关"));
        assertNull(ExcelColumnMapping.matchColumnName("  "));
    }

    @Test
    @DisplayName("类型优先：事件表（表格2）把「项目名称/项目编码」落到处理器真正读的 name/code")
    void eventTypeUsesProcessorFieldNames() {
        assertEquals("name", ExcelColumnMapping.matchHeaderForType("event", "项目名称"));
        assertEquals("name", ExcelColumnMapping.matchHeaderForType("event", "项目"));
        assertEquals("code", ExcelColumnMapping.matchHeaderForType("event", "项目编码"));
        assertEquals("category", ExcelColumnMapping.matchHeaderForType("event", "类别"));
        assertEquals("genderLimit", ExcelColumnMapping.matchHeaderForType("event", "性别限制"));
        assertEquals("refereesPerGroup", ExcelColumnMapping.matchHeaderForType("event", "组次裁判数量"));
    }

    @Test
    @DisplayName("类型优先：班级表把「班级名称/班级」落到 name、把「班级编码」落到 code")
    void classTypeDistinguishesNameAndCode() {
        assertEquals("name", ExcelColumnMapping.matchHeaderForType("class", "班级名称"));
        assertEquals("name", ExcelColumnMapping.matchHeaderForType("class", "班级"));
        assertEquals("code", ExcelColumnMapping.matchHeaderForType("class", "班级编码"));
        assertEquals("grade", ExcelColumnMapping.matchHeaderForType("class", "年级"));
        assertEquals("teacherName", ExcelColumnMapping.matchHeaderForType("class", "班主任"));
    }

    @Test
    @DisplayName("类型优先：用户表「姓名」落 realName（不是全局的 name）")
    void userTypeUsesRealName() {
        assertEquals("realName", ExcelColumnMapping.matchHeaderForType("user", "姓名"));
        assertEquals("username", ExcelColumnMapping.matchHeaderForType("user", "用户名"));
    }

    @Test
    @DisplayName("运动项目表（7列）7 个表头全部落位，且「项目类型」不被 eventCode 抢走")
    void eventSimpleAllHeadersMapped() {
        List<String> headers = List.of("项目代码", "项目名称", "每组人数", "每批组数", "项目类型", "场地号", "每批所需时间(分)");
        Map<String, String> mapped = com.sports.service.excel.SheetTypeResolver.autoColumnMap("eventsimple", headers);
        assertEquals("eventCode", mapped.get("0"));
        assertEquals("eventName", mapped.get("1"));
        assertEquals("teamMembers", mapped.get("2"));
        assertEquals("concurrency", mapped.get("3"));
        assertEquals("category", mapped.get("4"));
        assertEquals("defaultVenueCode", mapped.get("5"));
        assertEquals("perBatchMinutes", mapped.get("6"));
        assertEquals(7, mapped.size());
    }

    @Test
    @DisplayName("全名单表 / 报名表 / 年级表：自动映射覆盖全部列")
    void otherTypesAllHeadersMapped() {
        Map<String, String> roster = com.sports.service.excel.SheetTypeResolver.autoColumnMap(
                "roster", List.of("年级", "班级", "姓名", "学号", "性别"));
        assertEquals(Map.of("0", "grade", "1", "className", "2", "name", "3", "studentId", "4", "gender"), roster);

        Map<String, String> signup = com.sports.service.excel.SheetTypeResolver.autoColumnMap(
                "signup", List.of("年级", "班级", "姓名", "学号", "性别", "项目", "组号"));
        assertEquals("eventCode", signup.get("5"));
        assertEquals("teamTag", signup.get("6"));

        Map<String, String> grade = com.sports.service.excel.SheetTypeResolver.autoColumnMap(
                "grade", List.of("年级", "序号"));
        assertEquals(Map.of("0", "name", "1", "sortOrder"), grade);
    }

    @Test
    @DisplayName("同一字段被多列命中时只保留最靠左一列（避免后者覆盖前者）")
    void duplicateFieldKeepsLeftmost() {
        Map<String, String> mapped = com.sports.service.excel.SheetTypeResolver.autoColumnMap(
                "roster", List.of("年级", "姓名", "运动员姓名", "学号", "性别"));
        assertEquals("name", mapped.get("1"));
        assertFalse(mapped.containsKey("2"), "重复的姓名列不应再占位");
    }

    @Test
    @DisplayName("表头指纹：判定类型；认不出则返回 null（绝不默认成运动员表）")
    void inferTypeByHeader() {
        assertEquals("roster", com.sports.service.excel.SheetTypeResolver.inferByHeader(
                List.of("年级", "班级", "姓名", "学号", "性别")));
        assertEquals("class", com.sports.service.excel.SheetTypeResolver.inferByHeader(
                List.of("班级名称", "年级", "班主任")));
        assertEquals("grade", com.sports.service.excel.SheetTypeResolver.inferByHeader(
                List.of("年级", "序号")));
        assertEquals("eventsimple", com.sports.service.excel.SheetTypeResolver.inferByHeader(
                List.of("项目代码", "项目名称", "每组人数", "每批组数", "项目类型", "场地号", "每批所需时间(分)")));
        assertNull(com.sports.service.excel.SheetTypeResolver.inferByHeader(List.of("备注")),
                "单列命中不足以判定");
        assertNull(com.sports.service.excel.SheetTypeResolver.inferByHeader(List.of("甲", "乙", "丙")));
    }

    @Test
    @DisplayName("依赖顺序：年级/班级/名单 先于 报名/成绩")
    void dependencyOrder() {
        assertTrue(com.sports.service.excel.SheetTypeResolver.priority("grade")
                < com.sports.service.excel.SheetTypeResolver.priority("class"));
        assertTrue(com.sports.service.excel.SheetTypeResolver.priority("class")
                < com.sports.service.excel.SheetTypeResolver.priority("roster"));
        assertTrue(com.sports.service.excel.SheetTypeResolver.priority("roster")
                < com.sports.service.excel.SheetTypeResolver.priority("signup"));
        assertTrue(com.sports.service.excel.SheetTypeResolver.priority("signup")
                < com.sports.service.excel.SheetTypeResolver.priority("score"));
        assertEquals(50, com.sports.service.excel.SheetTypeResolver.priority("不存在的类型"));
    }
}
