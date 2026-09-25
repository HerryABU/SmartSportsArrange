package com.sports.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GradeMeetUtilTest {

    // ==================== 入毕年份码 ====================

    @Test
    void composeYearCode() {
        assertEquals("20252028", GradeMeetUtil.composeYearCode(2025, 2028));
        assertEquals("20262029", GradeMeetUtil.composeYearCode(2026, 2029));
        assertNull(GradeMeetUtil.composeYearCode(2025, null));
        assertNull(GradeMeetUtil.composeYearCode(null, 2028));
    }

    // ==================== 当前年级（自然升级递归） ====================

    @Test
    void currentGradeOrderUpgradesYearly() {
        // 入学时高一（序号 10），2025 入学，2025 届 → 高一；逐年 +1
        assertEquals(10, GradeMeetUtil.currentGradeOrder(10, 2025, 2025));
        assertEquals(11, GradeMeetUtil.currentGradeOrder(10, 2026, 2025));
        assertEquals(12, GradeMeetUtil.currentGradeOrder(10, 2027, 2025));
    }

    @Test
    void currentGradeOrderClampsAboveTwelve() {
        // 2028 届已超出高三（12），收敛到 12，是否毕业由 isGraduated 判定
        assertEquals(12, GradeMeetUtil.currentGradeOrder(10, 2028, 2025));
        assertEquals(12, GradeMeetUtil.currentGradeOrder(10, 2030, 2025));
    }

    @Test
    void currentGradeOrderHandlesMissingYear() {
        // 缺入学年或届年份：回退基准，不做升级
        assertEquals(10, GradeMeetUtil.currentGradeOrder(10, null, 2025));
        assertEquals(10, GradeMeetUtil.currentGradeOrder(10, 2026, null));
    }

    @Test
    void currentGradeDisplayResolvesName() {
        assertEquals("高一", GradeMeetUtil.currentGradeDisplay("高一", 2025, 2025));
        assertEquals("高二", GradeMeetUtil.currentGradeDisplay("高一", 2026, 2025));
        assertEquals("高三", GradeMeetUtil.currentGradeDisplay("高一", 2027, 2025));
        // 模糊年级输入同样生效：10年级 即高一
        assertEquals("高二", GradeMeetUtil.currentGradeDisplay("10年级", 2026, 2025));
    }

    // ==================== 毕业判定 ====================

    @Test
    void isGraduated() {
        assertFalse(GradeMeetUtil.isGraduated(2028, 2027));
        assertTrue(GradeMeetUtil.isGraduated(2028, 2028));
        assertTrue(GradeMeetUtil.isGraduated(2028, 2029));
        assertFalse(GradeMeetUtil.isGraduated(null, 2028));
        assertFalse(GradeMeetUtil.isGraduated(2028, null));
    }

    // ==================== 校验号拼装 ====================

    @Test
    void composeCheckNoFull() {
        assertEquals("第3届秋季运动会-20252028-高一-1001",
                GradeMeetUtil.composeCheckNo("第3届秋季运动会", "20252028", "高一", "1001"));
    }

    @Test
    void composeCheckNoWithoutStudentNo() {
        assertEquals("第3届秋季运动会-20252028-高一",
                GradeMeetUtil.composeCheckNo("第3届秋季运动会", "20252028", "高一", null));
    }

    @Test
    void composeCheckNoDropsMissingCoreSegments() {
        // 缺年份码 / 年级时仅保留已存在部分
        assertEquals("第3届秋季运动会-高一",
                GradeMeetUtil.composeCheckNo("第3届秋季运动会", null, "高一", null));
    }
}
