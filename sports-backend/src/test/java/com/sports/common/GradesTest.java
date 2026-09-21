package com.sports.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GradesTest {

    @Test
    void shortNameStripsSuffix() {
        assertEquals("高一", Grades.shortName("高一年级"));
        assertEquals("初二", Grades.shortName("初二年级"));
        assertEquals("高三", Grades.shortName("高三年级"));
        assertEquals("高一", Grades.shortName("高一"));
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
        assertTrue(Grades.same("一年级", "一年级"));
    }
}
