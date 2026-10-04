package com.sports.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sports.schedule.core.primitive.Window;

/**
 * {@link ScheduleBuildComponent#buildWindows} 的契约测试。
 *
 * <p>重点钉住「给某天多配容量」这一项：额外容量必须加到<b>该天每个时段</b>上，
 * 且<b>不得改动时段的起止时间</b> —— 起止时间是会打印进秩序册的现场事实，
 * 额外容量表示「这天还留了余量」，两者语义不同，混起来导出的时间表就与容量对不上。</p>
 */
class ScheduleBuildComponentTest {

    /** buildWindows 不触碰任何仓库，这里传 null 即可（构造函数只做赋值）。 */
    private static final ScheduleBuildComponent COMPONENT =
            new ScheduleBuildComponent(null, null, null);

    private static Map<String, Object> day(int day, int extra, String... startEndPairs) {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i + 1 < startEndPairs.length; i += 2) {
            Map<String, Object> sl = new LinkedHashMap<>();
            sl.put("key", "K" + i);
            sl.put("name", "时段" + i);
            sl.put("start", startEndPairs[i]);
            sl.put("end", startEndPairs[i + 1]);
            slots.add(sl);
        }
        Map<String, Object> dc = new LinkedHashMap<>();
        dc.put("day", day);
        dc.put("date", "");
        dc.put("extraMinutes", extra);
        dc.put("slots", slots);
        return dc;
    }

    private static Map<String, Object> cfg(List<Map<String, Object>> days) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("startDate", "2026-10-01");
        cfg.put("dayConfigs", days);
        return cfg;
    }

    @Test
    @DisplayName("无额外容量时容量 = 时段时长（不改变既有行为）")
    void noExtraKeepsDuration() {
        List<Window> ws = COMPONENT.buildWindows(
                cfg(List.of(day(1, 0, "08:00", "11:30", "14:00", "17:30"))));
        assertEquals(2, ws.size());
        assertEquals(210, ws.get(0).capacity, "08:00→11:30 = 210 分钟");
        assertEquals(210, ws.get(1).capacity, "14:00→17:30 = 210 分钟");
        assertEquals(8 * 60, ws.get(0).startMinute, "起止时间必须原样保留");
    }

    @Test
    @DisplayName("额外容量加到该天**每个**时段上，且起止时间不变")
    void extraAppliesToEverySlotOfThatDay() {
        List<Window> ws = COMPONENT.buildWindows(
                cfg(List.of(day(1, 60, "08:00", "11:30", "14:00", "17:30"))));
        assertEquals(2, ws.size());
        assertEquals(270, ws.get(0).capacity, "210 + 60");
        assertEquals(270, ws.get(1).capacity, "210 + 60");
        // 时间不动：现场几点开始几点结束，与容量是两件事
        assertEquals(8 * 60, ws.get(0).startMinute);
        assertEquals(14 * 60, ws.get(1).startMinute);
    }

    @Test
    @DisplayName("额外容量只作用于配置的那一天，不串到别的天")
    void extraIsPerDay() {
        List<Window> ws = COMPONENT.buildWindows(cfg(List.of(
                day(1, 0, "08:00", "10:00"),
                day(2, 90, "08:00", "10:00"))));
        assertEquals(2, ws.size());
        assertEquals(120, ws.get(0).capacity, "第 1 天无额外容量");
        assertEquals(210, ws.get(1).capacity, "第 2 天 120 + 90");
    }

    @Test
    @DisplayName("非法额外容量（负数/缺失/非数字）按 0 处理，不炸也不放大容量")
    void invalidExtraTreatedAsZero() {
        Map<String, Object> bad = day(1, 0, "08:00", "10:00");
        bad.put("extraMinutes", -50);                       // 负数
        assertEquals(120, COMPONENT.buildWindows(cfg(List.of(bad))).get(0).capacity);

        Map<String, Object> absent = day(1, 0, "08:00", "10:00");
        absent.remove("extraMinutes");                      // 缺失（历史配置）
        assertEquals(120, COMPONENT.buildWindows(cfg(List.of(absent))).get(0).capacity);

        Map<String, Object> junk = day(1, 0, "08:00", "10:00");
        junk.put("extraMinutes", "abc");                    // 非数字
        assertEquals(120, COMPONENT.buildWindows(cfg(List.of(junk))).get(0).capacity);
    }
}
