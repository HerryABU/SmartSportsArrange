package com.sports.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性别归一化/匹配测试（兼容 男子组/M、女子组/F 双轨写法）。
 */
class GenderUtilTest {

    @Test
    void normalize_mapsCnAndEn() {
        assertEquals("M", GenderUtil.normalize("男子组"));
        assertEquals("M", GenderUtil.normalize("男"));
        assertEquals("M", GenderUtil.normalize("M"));
        assertEquals("M", GenderUtil.normalize("m"));
        assertEquals("F", GenderUtil.normalize("女子组"));
        assertEquals("F", GenderUtil.normalize("女"));
        assertEquals("F", GenderUtil.normalize("F"));
    }

    @Test
    void matches_acceptsSameGenderRegardlessOfNotation() {
        assertTrue(GenderUtil.matches("男子组", "M"));
        assertTrue(GenderUtil.matches("M", "男"));
        assertTrue(GenderUtil.matches("女子组", "F"));
        assertTrue(GenderUtil.matches("F", "女"));
    }

    @Test
    void matches_mixedAndNullAlwaysAllowed() {
        assertTrue(GenderUtil.matches(null, "M"));
        assertTrue(GenderUtil.matches("", "M"));
        assertTrue(GenderUtil.matches("混合组", "M"));
        assertTrue(GenderUtil.matches("mixed", "F"));
    }

    @Test
    void matches_rejectsOppositeGender() {
        assertFalse(GenderUtil.matches("男子组", "F"));
        assertFalse(GenderUtil.matches("女子组", "M"));
        assertFalse(GenderUtil.matches("M", "女"));
    }
}
