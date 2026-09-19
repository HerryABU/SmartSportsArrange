package com.sports.collab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 赛程协作中心（版本号 + 增量事件）的验证。
 *
 * <p>重点：<b>版本号单调递增</b>、<b>增量事件只返回比 since 新的</b>、
 * <b>事件环有界</b>（内存不无界增长）、<b>无认证上下文时操作者回退 system</b>。</p>
 */
@DisplayName("赛程协作中心")
class ScheduleCollaborationServiceTest {

    private final ScheduleCollaborationService service = new ScheduleCollaborationService();

    @Test
    @DisplayName("版本号单调递增，notify 每次 +1")
    void versionIncrementsMonotonically() {
        long v0 = service.currentVersion();
        ScheduleChangeEvent e1 = service.notify("schedule", "auto-arranged", "EventSchedule", 1L);
        ScheduleChangeEvent e2 = service.notify("arrangement", "arranged", "Arrangement", 7L);
        assertEquals(v0 + 1, e1.version());
        assertEquals(v0 + 2, e2.version());
        assertEquals(v0 + 2, service.currentVersion());
    }

    @Test
    @DisplayName("增量事件只返回版本号大于 since 的事件，且升序")
    void eventsSinceFiltersByVersion() {
        service.notify("schedule", "a", "X", null);
        service.notify("schedule", "b", "X", null);
        service.notify("schedule", "c", "X", null);
        long since = service.currentVersion() - 2;   // 只留最后 2 条

        List<ScheduleChangeEvent> events = service.eventsSince(since);
        assertEquals(2, events.size());
        assertTrue(events.get(0).version() < events.get(1).version(), "应升序返回");
        assertEquals("b", events.get(0).action());
        assertEquals("c", events.get(1).action());
    }

    @Test
    @DisplayName("事件环有界：超过上限后只保留最近 N 条，内存不无界增长")
    void recentEventsAreBounded() {
        for (int i = 0; i < 250; i++) {
            service.notify("schedule", "edit-" + i, "EventSchedule", (long) i);
        }
        List<ScheduleChangeEvent> all = service.eventsSince(0);
        assertTrue(all.size() <= 200, "事件环应有界，实际 " + all.size());
        // 保留的是最新一批
        assertEquals("edit-249", all.get(all.size() - 1).action());
    }

    @Test
    @DisplayName("无认证上下文时操作者回退 system（不会抛异常）")
    void actorFallsBackToSystemWithoutAuth() {
        ScheduleChangeEvent e = service.notify("schedule", "auto-arranged", "EventSchedule", 1L);
        assertEquals("system", e.actor());
    }

    @Test
    @DisplayName("toMap 序列化结构完整（前端消费字段齐全）")
    void toMapHasAllFields() {
        ScheduleChangeEvent e = service.notify("schedule", "auto-arranged", "EventSchedule", 5L);
        var m = e.toMap();
        assertEquals("schedule", m.get("type"));
        assertEquals("auto-arranged", m.get("action"));
        assertEquals("EventSchedule", m.get("entity"));
        assertEquals(5L, m.get("entityId"));
        assertEquals(e.version(), m.get("version"));
        assertNotNull(m.get("actor"));
        assertNotNull(m.get("timestamp"));
    }
}
