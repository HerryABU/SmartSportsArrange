package com.sports.service.arrange;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.event.EventScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link HeatStaggerService} 接线测试：读库 → 组次时间轴 → 求解 → 落库。
 *
 * <p>纯函数 {@code HeatStaggerMath} 的行为已由其自身单测覆盖；本类只验证
 * 「服务层把真实实体正确翻译成求解视图」——组次数必须取自编排表（而非猜）、
 * 落库只改 heat 且不动其它字段。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeatStaggerServiceTest {

    @Mock private ArrangementRepository arrangementRepository;
    @Mock private EventScheduleRepository eventScheduleRepository;

    private HeatStaggerService service;

    @BeforeEach
    void setUp() {
        service = new HeatStaggerService(arrangementRepository, eventScheduleRepository);
    }

    private Event event(long id, String name) {
        return event(id, name, 6);
    }

    /** 每组用时显式配置为 perRound 分钟（与编排期 perBatchMinutes 同一来源） */
    private Event event(long id, String name, int perBatchMinutes) {
        Event e = new Event();
        e.setId(id);
        e.setName(name);
        e.setTrack(true);
        e.setLaneCount(8);
        e.setPerBatchMinutes(perBatchMinutes);
        return e;
    }

    private Athlete athlete(long id, String name, long classId) {
        ClassInfo c = new ClassInfo();
        c.setId(classId);
        c.setName("班级" + classId);
        Athlete a = new Athlete();
        a.setId(id);
        a.setName(name);
        a.setClassInfo(c);
        return a;
    }

    private Arrangement arrangement(long id, Event e, Athlete a, String grade, String gender,
                                   String round, int heat) {
        return Arrangement.builder()
                .id(id)
                .event(e)
                .athlete(a)
                .grade(grade)
                .gender(gender)
                .round(round)
                .heat(heat)
                .isManual(false)
                .build();
    }

    private EventSchedule schedule(long id, Event e, String grade, String round,
                                   String start, String end, int day) {
        return EventSchedule.builder()
                .id(id)
                .event(e)
                .grade(grade)
                .round(round)
                .day(day)
                .timeSlot("上午")
                .startTime(start)
                .endTime(end)
                .venue("田径场")
                .durationMinutes(60)
                .build();
    }

    @Test
    @DisplayName("两个项目同一时段、甲同在第1组 → 自动换组并落库，且只改 heat 字段")
    void staggersAndPersistsHeat() {
        // 每组 10 分钟：A 09:00-10:00（6 组）、B 09:00-09:30（3 组）
        Event a = event(10L, "100米", 10);
        Event b = event(20L, "跳远", 10);
        when(eventScheduleRepository.findAll()).thenReturn(List.of(
                schedule(1L, a, "高一", "final", "09:00", "10:00", 1),
                schedule(2L, b, "高一", "final", "09:00", "09:30", 1)));

        Athlete jia = athlete(1L, "甲", 100L);
        List<Arrangement> rows = new ArrayList<>(List.of(
                arrangement(1L, a, jia, "高一", "M", "final", 1),
                arrangement(2L, b, jia, "高一", "M", "final", 1)));
        when(arrangementRepository.findAll()).thenReturn(rows);
        when(arrangementRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> report = service.resolve(15);

        assertNotNull(report);
        assertEquals(1L, ((Number) report.get("resolved")).longValue(), "应换组 1 人次，实际=" + report);

        ArgumentCaptor<List<Arrangement>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(arrangementRepository).saveAll(captor.capture());
        List<Arrangement> saved = captor.getValue();
        // 甲在 A 的组次应被往后挪；B 的组次必须保持第 1 组不变
        Arrangement aRow = saved.stream().filter(x -> x.getEvent().getId().equals(10L)).findFirst().orElseThrow();
        Arrangement bRow = saved.stream().filter(x -> x.getEvent().getId().equals(20L)).findFirst().orElseThrow();
        assertTrue(aRow.getHeat() > 1, "A 的组次应被后移，实际=" + aRow.getHeat());
        assertEquals(1, bRow.getHeat(), "B 的组次不应被动");
        // 组次变化后仍需满足缓冲
        int aStart = 9 * 60 + (aRow.getHeat() - 1) * 10;
        int gap = Math.max((9 * 60) - (aStart + 10), aStart - (9 * 60 + 10));
        assertTrue(gap >= 15, "换后间隔应达到缓冲，实际=" + gap);
    }

    @Test
    @DisplayName("人工锁定的编排项不被移动")
    void keepsManualArrangement() {
        Event a = event(10L, "100米", 10);
        Event b = event(20L, "跳远", 10);
        when(eventScheduleRepository.findAll()).thenReturn(List.of(
                schedule(1L, a, "高一", "final", "09:00", "10:00", 1),
                schedule(2L, b, "高一", "final", "09:00", "09:30", 1)));

        Athlete jia = athlete(1L, "甲", 100L);
        Arrangement aRow = arrangement(1L, a, jia, "高一", "M", "final", 1);
        aRow.setIsManual(true);
        List<Arrangement> rows = new ArrayList<>(List.of(
                aRow, arrangement(2L, b, jia, "高一", "M", "final", 1)));
        when(arrangementRepository.findAll()).thenReturn(rows);

        Map<String, Object> report = service.resolve(15);

        assertEquals(0L, ((Number) report.get("resolved")).longValue(),
                "两侧都无可动项时应如实报 0，实际=" + report);
        assertEquals(1, aRow.getHeat(), "人工锁定项的组次不得被改");
    }

    @Test
    @DisplayName("赛程表为空时如实返回，不抛异常（编排期尚无赛程是正常状态）")
    void toleratesEmptySchedule() {
        when(eventScheduleRepository.findAll()).thenReturn(List.of());
        when(arrangementRepository.findAll()).thenReturn(List.of());

        Map<String, Object> report = service.resolve(15);

        assertEquals(0L, ((Number) report.get("resolved")).longValue());
        assertNotNull(report.get("note"));
    }

    @Test
    @DisplayName("编排记录找不到对应赛程行 → 跳过，不臆造时间窗")
    void skipsArrangementWithoutSchedule() {
        Event a = event(10L, "100米");
        when(eventScheduleRepository.findAll()).thenReturn(List.of());
        Athlete jia = athlete(1L, "甲", 100L);
        when(arrangementRepository.findAll()).thenReturn(
                new ArrayList<>(List.of(arrangement(1L, a, jia, "高一", "M", "final", 1))));

        Map<String, Object> report = service.resolve(15);

        assertEquals(0L, ((Number) report.get("resolved")).longValue());
    }
}
