package com.sports.service;

import com.sports.entity.Athlete;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.entity.Registration;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.repository.RegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 赛程编排测试（并发位模型）。
 *
 * <p>覆盖：田赛项目内并发折算轮次、并发位数并行/串行、自定义项目顺序、田赛分组同批并行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleServiceTest {

    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private ArrangementService arrangementService;
    @Mock private SystemService systemService;

    @InjectMocks private ScheduleService scheduleService;

    /** 捕获落库的赛程行，供断言与 buildResult 查询 */
    private final List<EventSchedule> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        saved.clear();
        when(scheduleRepository.save(any(EventSchedule.class))).thenAnswer(inv -> {
            EventSchedule s = inv.getArgument(0);
            saved.add(s);
            return s;
        });
        when(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc())
                .thenAnswer(inv -> new ArrayList<>(saved));
    }

    // ==================== 夹具 ====================

    /** 一天、一个长时段（08:00-18:00），便于观察并行/串行差异 */
    private Map<String, Object> cfg(int trackSlots, int fieldSlots) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("startDate", "2026-09-20");
        c.put("days", 1);
        c.put("gradeOrder", List.of("高一年级"));
        c.put("venues", List.of("田径场", "田赛A区", "田赛B区"));
        c.put("trackSlots", trackSlots);
        c.put("fieldSlots", fieldSlots);
        c.put("defaultDurationMinutes", 600);   // 不封顶，便于断言真实估算值
        c.put("defaultIntervalMinutes", 0);
        c.put("heatMinutes", 6);
        c.put("fieldPerAthleteMinutes", 3);
        c.put("eventOrder", new ArrayList<Long>());
        c.put("fieldGroups", new ArrayList<Map<String, Object>>());
        c.put("dayConfigs", List.of(Map.of("day", 1, "date", "2026-09-20", "slots",
                List.of(Map.of("key", "AM", "name", "上午", "start", "08:00", "end", "18:00")))));
        return c;
    }

    private Event track(Long id, String name) {
        return Event.builder().id(id).name(name).code("T" + id)
                .track(true).laneCount(8).category("径赛").genderLimit("男子组")
                .gradeGroup("高一年级").isEnabled(true).sortOrder(id.intValue()).build();
    }

    private Event field(Long id, String name, int concurrency) {
        return Event.builder().id(id).name(name).code("F" + id)
                .track(false).laneCount(0).concurrency(concurrency).category("田赛")
                .genderLimit("男子组").gradeGroup("高一年级").isEnabled(true)
                .sortOrder(id.intValue()).build();
    }

    private void events(Event... list) {
        when(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(list));
    }

    private void regs(Long eventId, int n) {
        List<Registration> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Athlete a = Athlete.builder().id(eventId * 1000 + i)
                    .grade("高一年级").gender("M").build();
            list.add(Registration.builder().athlete(a).status("approved").build());
        }
        when(registrationRepository.findApprovedByEventId(eventId)).thenReturn(list);
    }

    // ==================== 用例 ====================

    /** 田赛项目内并发：20 人 / 并发 4 = 5 轮 × 3 分钟 = 15 分钟 */
    @Test
    void fieldDurationDividedByProjectConcurrency() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        Event f = field(11L, "跳远", 4);
        events(f);
        regs(11L, 20);

        scheduleService.autoSchedule(null);

        assertEquals(1, saved.size());
        assertEquals(15, saved.get(0).getDurationMinutes());
        assertEquals("08:00", saved.get(0).getStartTime());
    }

    /** 田赛并发位数 2：两个田赛项目同时段开赛（并行） */
    @Test
    void fieldSlotsAllowParallelProjects() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regs(11L, 2);
        regs(12L, 2);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals("08:00", saved.get(0).getStartTime());
        assertEquals("08:00", saved.get(1).getStartTime(), "并发位=2 时两个田赛应同时开赛");
    }

    /** 径赛并发位数 1（串行）：第二个项目排在第一个之后 */
    @Test
    void trackSlotsOneKeepsSerial() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        events(track(21L, "100米"), track(22L, "200米"));
        regs(21L, 8);
        regs(22L, 8);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals("08:00", saved.get(0).getStartTime());
        // 8 人 / 8 道 = 1 组 × 6 分钟 → 不足最短 10 分钟，取 10 分钟
        assertEquals(10, saved.get(0).getDurationMinutes());
        assertEquals("08:10", saved.get(1).getStartTime(), "串行位=1 时第二个径赛应顺延");
    }

    /** 自定义项目顺序：eventOrder=[2,1] 时先排 2 号项目 */
    @Test
    void customEventOrderWins() {
        Map<String, Object> c = cfg(1, 1);
        c.put("eventOrder", List.of(2L, 1L));
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(1L, "跳远", 1), field(2L, "铅球", 1));
        regs(1L, 1);
        regs(2L, 1);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals(2L, saved.get(0).getEvent().getId(), "应按 eventOrder 先排 2 号项目");
        assertEquals(1L, saved.get(1).getEvent().getId());
    }

    /** 田赛分组：同组项目安排在同一时段并行 */
    @Test
    void fieldGroupScheduledInSameSlot() {
        Map<String, Object> c = cfg(1, 2);
        c.put("fieldGroups", List.of(Map.of("name", "田赛A组", "eventIds", List.of(11L, 12L))));
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regs(11L, 3);
        regs(12L, 3);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals("08:00", saved.get(0).getStartTime());
        assertEquals("08:00", saved.get(1).getStartTime(), "同组田赛应在同一时段并行");
        assertEquals(saved.get(0).getTimeSlot(), saved.get(1).getTimeSlot());
    }
}
