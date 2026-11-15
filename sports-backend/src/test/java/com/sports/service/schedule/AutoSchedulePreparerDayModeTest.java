package com.sports.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时间三态 I/O 契约测试：把「天数输入 → dayMode / 天数上界」的推导钉死，
 * 确保 days=0/-1 真正交给算法「无时间约束」，而不是被静默当成固定上限。
 */
class AutoSchedulePreparerDayModeTest {

    /** 复刻 {@code buildWindows} 的三态推导，避免两套口径 */
    private static String modeOf(int rawDays, boolean autoDaysFlag) {
        boolean minimizeDays = rawDays < 0;
        boolean autoDays = autoDaysFlag || rawDays == 0;
        return AutoSchedulePreparer.deriveDayMode(minimizeDays, autoDays);
    }

    @Test
    @DisplayName("三态推导：days<0→minimize；days==0→unlimited；days>0 且未开 autoDays→fixed")
    void deriveDayModeThreeStates() {
        assertEquals("minimize", modeOf(-1, false), "days=-1 必须映射为 minimize");
        assertEquals("minimize", modeOf(-5, false), "任何负数统一视作 minimize");
        assertEquals("unlimited", modeOf(0, false), "days=0 必须映射为 unlimited（无时间约束）");
        assertEquals("fixed", modeOf(5, false), "days>0 且未开 autoDays 必须映射为 fixed");
        assertEquals("unlimited", modeOf(5, true), "显式 autoDays 标志覆盖正整数天数");
    }

    @Test
    @DisplayName("无时间约束天数上界：估算下界 + 缓冲，缓冲随规模温和增长且封顶 +6")
    void noConstraintMaxDaysBuffer() {
        assertEquals(3, AutoSchedulePreparer.noConstraintMaxDays(1));    // +2
        assertEquals(4, AutoSchedulePreparer.noConstraintMaxDays(2));    // +2
        assertEquals(6, AutoSchedulePreparer.noConstraintMaxDays(4));    // 4/4=1 → +2
        assertEquals(10, AutoSchedulePreparer.noConstraintMaxDays(8));   // 8/4=2 → +2
        assertEquals(12, AutoSchedulePreparer.noConstraintMaxDays(10));  // 10/4=2 → +2
        assertEquals(15, AutoSchedulePreparer.noConstraintMaxDays(12));  // 12/4=3 → +3
        assertEquals(30, AutoSchedulePreparer.noConstraintMaxDays(24));  // 24/4=6 → +6
        assertEquals(106, AutoSchedulePreparer.noConstraintMaxDays(100)); // 封顶 +6
    }
}
