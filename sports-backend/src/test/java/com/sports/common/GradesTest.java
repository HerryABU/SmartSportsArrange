package com.sports.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.sports.common.util.Grades;

class GradesTest {

    @Test
    void shortNameStripsSuffix() {
        assertEquals("高一", Grades.shortName("高一年级"));
        assertEquals("初二", Grades.shortName("初二年级"));
        assertEquals("高三", Grades.shortName("高三年级"));
        assertEquals("高一", Grades.shortName("高一"));
        assertEquals("高一", Grades.shortName("10年级"));   // 数字写法也归一
        assertEquals("高一", Grades.shortName("Grade 10"));
        assertNull(Grades.shortName(null));
    }

    @Test
    void sameToleratesShortVsFull() {
        assertTrue(Grades.same("高一", "高一年级"));
        assertTrue(Grades.same("高一年级", "高一"));
        assertTrue(Grades.same("高二年级", "高二年级"));
        assertFalse(Grades.same("高一", "高二年级"));
        assertFalse(Grades.same("高一", "初一年级"));
        assertFalse(Grades.same("高一", null));
        assertFalse(Grades.same(null, "高一"));
    }

    @Test
    void primaryGradeEqualsStillWorks() {
        assertTrue(Grades.same("一年级", "一年级"));
        assertTrue(Grades.same("一年级", "1年级"));
    }

    // ==================== 模糊年级：等价类 ====================

    /** 高一 / 高一年级 / 高中一年级 / 10年级 / 十年级 / Grade 10 / G10 全部等价 */
    @Test
    void seniorOneAllSpellingsAreSame() {
        String[] spellings = {"高一", "高一年级", "高中一年级", "高中1年级", "10年级", "十年级",
                "Grade 10", "grade10", "G10", "10", " 高 一 年级 "};
        for (String s : spellings) {
            assertTrue(Grades.same("高一", s), "应等价于高一: " + s);
            assertEquals(10, Grades.order(s), "序号应为 10: " + s);
        }
    }

    /** 初一 = 初一年级 = 初中一年级 = 七年级 = 7年级 */
    @Test
    void juniorOneAllSpellingsAreSame() {
        String[] spellings = {"初一", "初一年级", "初中一年级", "七年级", "7年级", "7", "Grade 7", "G7"};
        for (String s : spellings) {
            assertTrue(Grades.same("初一", s), "应等价于初一: " + s);
            assertEquals(7, Grades.order(s), "序号应为 7: " + s);
        }
    }

    /** 小学一年级 = 1年级 = 小一 = 小学一年级，且不等于高中一年级 */
    @Test
    void primaryOneAllSpellingsAreSame() {
        for (String s : new String[]{"一年级", "1年级", "小一", "小学一年级", "小学1年级", "Grade 1", "G1"}) {
            assertTrue(Grades.same("一年级", s), "应等价于一年级: " + s);
            assertEquals(1, Grades.order(s), "序号应为 1: " + s);
        }
        assertFalse(Grades.same("一年级", "高一"), "小学一年级 ≠ 高一");
        assertFalse(Grades.same("高一", "高二"), "高一 ≠ 高二");
        assertFalse(Grades.same("初一", "高一"), "初一 ≠ 高一");
    }

    /** 十二年级 = 高三；十一年级 = 高二 */
    @Test
    void twelveYearSystemBoundary() {
        assertTrue(Grades.same("十二年级", "高三"));
        assertTrue(Grades.same("十一年级", "高二"));
        assertEquals(12, Grades.order("十二年级"));
        assertEquals(11, Grades.order("11年级"));
    }

    @Test
    void normProducesCanonicalName() {
        assertEquals("高一", Grades.norm("高一年级"));
        assertEquals("高一", Grades.norm("10年级"));
        assertEquals("高一", Grades.norm("Grade 10"));
        assertEquals("初三", Grades.norm("九年级"));
        assertEquals("一年级", Grades.norm("小学1年级"));
        assertEquals("初一", Grades.norm("七年级"));
        // 识别不了的原样保留（不丢信息）；空白视为缺失
        assertEquals("实验班", Grades.norm("实验班"));
        assertNull(Grades.norm("   "));
    }

    @Test
    void keyFallsBackToRawForUnknown() {
        assertEquals("G10", Grades.key("高一年级"));
        assertEquals("G10", Grades.key("10年级"));
        assertEquals(Grades.key("不分年级"), Grades.key("不分年级"));
        assertNotEquals(Grades.key("不分年级"), Grades.key("高一"));
    }

    @Test
    void equivalentsCoverHistoricalSpellings() {
        var eq = Grades.equivalents("10年级");
        assertTrue(eq.contains("高一"));
        assertTrue(eq.contains("高一年级"));
        assertTrue(eq.contains("10年级"));
        assertTrue(eq.contains("高中一年级"));
        assertTrue(eq.contains("Grade 10"));
        // 未知年级只回原文
        assertEquals(1, Grades.equivalents("不分年级").size());
    }

    // ==================== 班级名模糊 ====================

    @Test
    void classKeyIgnoresGradeSpelling() {
        String expect = Grades.classKey("高三1班");
        assertEquals("G12#1", expect);
        assertEquals(expect, Grades.classKey("高三年级1班"));
        assertEquals(expect, Grades.classKey("高三年级（1）班"));
        assertEquals(expect, Grades.classKey("高三(1)班"));
        assertEquals(expect, Grades.classKey("12年级1班"));
        assertEquals(expect, Grades.classKey("高三一班"));
        assertEquals(expect, Grades.classKey("高三 1 班"));
    }

    @Test
    void classKeyDistinguishesGradeAndNumber() {
        assertNotEquals(Grades.classKey("高三1班"), Grades.classKey("高三2班"));
        assertNotEquals(Grades.classKey("高三1班"), Grades.classKey("高二1班"));
        // 年级解析不出时退化为原文，避免把不同年级的"1班"误判同班
        assertTrue(Grades.classKey("实验班").startsWith("RAW:"));
        assertNotEquals(Grades.classKey("1班"), Grades.classKey("2班"));
    }

    @Test
    void normClassNameCanonicalizes() {
        assertEquals("高三1班", Grades.normClassName("高三年级（1）班"));
        assertEquals("高一12班", Grades.normClassName("高一年级12班"));
        assertEquals("初一3班", Grades.normClassName("七年级3班"));
        assertEquals("实验班", Grades.normClassName("实验班"));
    }
}
