package com.sports.service.arrange;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 兼项冲突检测：聚焦「径赛+田赛同一时段」应降级为「可顺延（非致命）」这一语义变更。
 *
 * <p>该语义由 {@link com.sports.schedule.core.math.ConflictMath} 在 {@code gapOf} 中判定为
 * {@code SEQUENCED}，本测试守护 {@link ConflictService} 上游的检测/汇总/建议文本与之保持一致：
 * 严重度=可顺延、类型=径田可顺延、建议含「径赛优先、田赛后放」，且 {@code countConflicts} 计入总数但不计入严重数。</p>
 */
@ExtendWith(MockitoExtension.class)
class ConflictServiceTest {

    @Mock
    private ArrangementRepository arrangementRepository;
    @Mock
    private EventScheduleRepository eventScheduleRepository;
    @Mock
    private AthleteRepository athleteRepository;

    @InjectMocks
    private ConflictService conflictService;

    private static Event track(long id, String name) {
        Event e = new Event();
        e.setId(id);
        e.setName(name);
        e.setCode("T" + id);
        e.setTrack(true);
        return e;
    }

    private static Event field(long id, String name) {
        Event e = new Event();
        e.setId(id);
        e.setName(name);
        e.setCode("F" + id);
        e.setTrack(false);
        return e;
    }

    private static EventSchedule sched(long id, Event ev, int day, String start, String end, String venue) {
        EventSchedule s = new EventSchedule();
        s.setId(id);
        s.setEvent(ev);
        s.setDay(day);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setVenue(venue);
        return s;
    }

    private static Arrangement entry(Athlete a, Event e) {
        Arrangement arr = new Arrangement();
        arr.setAthlete(a);
        arr.setEvent(e);
        return arr;
    }

    @Test
    void detect_trackPlusFieldSamePeriod_isSequencedNotFatal() {
        Athlete z = new Athlete();
        z.setId(1L);
        z.setName("张三");
        z.setNumber("001");

        Event t100 = track(10L, "100米");
        Event highJump = field(20L, "跳高");
        EventSchedule s1 = sched(100L, t100, 1, "08:00", "08:30", "径赛A");
        EventSchedule s2 = sched(200L, highJump, 1, "08:10", "08:40", "田赛A");
        Arrangement a1 = entry(z, t100);
        Arrangement a2 = entry(z, highJump);

        when(eventScheduleRepository.findAll()).thenReturn(List.of(s1, s2));
        when(arrangementRepository.findAll()).thenReturn(List.of(a1, a2));
        when(athleteRepository.findById(1L)).thenReturn(Optional.of(z));

        List<Map<String, Object>> conflicts = conflictService.detectConflicts();
        assertEquals(1, conflicts.size(), "径赛+田赛同时间段应命中 1 处（可顺延）冲突");
        Map<String, Object> c = conflicts.get(0);
        assertEquals(ConflictService.SEVERITY_SEQUENCED, c.get("severity"),
                "径田同时间段严重度应为「可顺延」而非致命");
        assertEquals(ConflictService.TYPE_SEQUENCED, c.get("type"),
                "类型应为「径田可顺延」");
        String suggestion = String.valueOf(c.get("suggestion"));
        assertTrue(suggestion.contains("径赛优先、田赛后放"),
                "建议应给出「径赛优先、田赛后放」的可执行结论，实际：" + suggestion);

        int[] cnt = conflictService.countConflicts();
        assertEquals(1, cnt[0], "可顺延冲突应计入总数");
        assertEquals(0, cnt[1], "可顺延冲突不应计入严重数（致命）");
    }

    @Test
    void detect_twoTracksOverlapStillFatal_control() {
        Athlete z = new Athlete();
        z.setId(1L);
        z.setName("张三");
        z.setNumber("001");

        Event t100 = track(10L, "100米");
        Event t200 = track(30L, "200米");
        EventSchedule s1 = sched(100L, t100, 1, "08:00", "08:30", "径赛A");
        EventSchedule s2 = sched(200L, t200, 1, "08:10", "08:40", "径赛A");
        Arrangement a1 = entry(z, t100);
        Arrangement a2 = entry(z, t200);

        when(eventScheduleRepository.findAll()).thenReturn(List.of(s1, s2));
        when(arrangementRepository.findAll()).thenReturn(List.of(a1, a2));
        when(athleteRepository.findById(1L)).thenReturn(Optional.of(z));

        List<Map<String, Object>> conflicts = conflictService.detectConflicts();
        assertEquals(1, conflicts.size());
        Map<String, Object> c = conflicts.get(0);
        assertEquals(ConflictService.SEVERITY_BLOCKER, c.get("severity"),
                "两个径赛同时间段应仍判为致命冲突（对照：证明降级仅限径田）");
        assertEquals(ConflictService.TYPE_OVERLAP, c.get("type"));

        int[] cnt = conflictService.countConflicts();
        assertEquals(1, cnt[0]);
        assertEquals(1, cnt[1], "两个径赛重叠必须计入严重数");
    }
}
