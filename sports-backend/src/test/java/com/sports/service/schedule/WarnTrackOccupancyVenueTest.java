package com.sports.service.schedule;

import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 占道冲突告警的「按场地分组」回归测试。
 *
 * <p>契约：<b>径赛只有串行，除非多场地</b>。两条不同跑道同一时刻各跑一个径赛项目是合法的，
 * 不能被误报成「跑道被同时占用」；只有<b>同一条跑道</b>上的时间重叠才是真冲突。</p>
 *
 * <p>旧实现把所有占道项目混在一起两两比较 → 双跑道部署的合法并行被误判为冲突，
 * 连带 {@code businessOk=false}（/api/schedule/auto 返回 success=false）。</p>
 */
class WarnTrackOccupancyVenueTest {

    private final SchedulePlacementComponent component =
            new SchedulePlacementComponent(null, null, null, null);

    private EventSchedule row(long id, String name, String venue, String start, int minutes) {
        Event e = Event.builder().id(id).name(name).code("T" + id).track(true)
                .occupiesTrack(false).build();
        return EventSchedule.builder().event(e).day(1).scheduleDate("2026-09-20")
                .startTime(start).endTime(start).venue(venue).durationMinutes(minutes).build();
    }

    @Test
    void differentVenuesSameTimeIsLegitimateParallel_noWarning() {
        // 双跑道：08:00 各跑一个项目 —— 合法的多场地并行，不得告警
        List<EventSchedule> saved = List.of(
                row(9101L, "100米", "田径场A", "08:00", 10),
                row(9102L, "200米", "田径场B", "08:00", 10));
        List<String> warnings = new ArrayList<>();
        component.warnTrackOccupancy(warnings, saved);
        assertTrue(warnings.isEmpty(),
                "不同跑道同时开赛是合法并行，不应报占道冲突；实际告警=" + warnings);
    }

    @Test
    void sameVenueOverlapIsRealConflict_warningEmitted() {
        // 同一条跑道 08:00 两个项目重叠 —— 真冲突，必须告警
        List<EventSchedule> saved = List.of(
                row(9101L, "100米", "田径场A", "08:00", 10),
                row(9102L, "200米", "田径场A", "08:05", 10));
        List<String> warnings = new ArrayList<>();
        component.warnTrackOccupancy(warnings, saved);
        assertEquals(1, warnings.size(), "同一跑道时间重叠必须报 1 条占道冲突");
        assertTrue(warnings.get(0).contains("100米") && warnings.get(0).contains("200米"));
    }

    @Test
    void sameVenueSequentialIsFine() {
        // 同一条跑道但时间错开 —— 串行，正常
        List<EventSchedule> saved = List.of(
                row(9101L, "100米", "田径场A", "08:00", 10),
                row(9102L, "200米", "田径场A", "08:10", 10));
        List<String> warnings = new ArrayList<>();
        component.warnTrackOccupancy(warnings, saved);
        assertTrue(warnings.isEmpty(), "边界相接不算重叠");
    }

    @Test
    void blankVenueFallsBackToSharedTrack_conservative() {
        // 场地未指定：保守按共享跑道处理，维持旧口径（仍会报重叠）
        List<EventSchedule> saved = List.of(
                row(9101L, "100米", null, "08:00", 10),
                row(9102L, "200米", "", "08:00", 10));
        List<String> warnings = new ArrayList<>();
        component.warnTrackOccupancy(warnings, saved);
        assertEquals(1, warnings.size(), "场地为空按同一条跑道保守处理");
    }

    @Test
    void crossDayOverlapDoesNotClash() {
        // 跨天：第 1 天 23:50~24:00 与第 2 天 00:00~00:10 边界相接，不算重叠
        EventSchedule a = row(9101L, "100米", "田径场A", "23:50", 10);
        EventSchedule b = row(9102L, "200米", "田径场A", "00:00", 10);
        b.setDay(2);
        List<String> warnings = new ArrayList<>();
        component.warnTrackOccupancy(warnings, List.of(a, b));
        assertTrue(warnings.isEmpty(), "跨天不应误判为重叠");
    }
}
