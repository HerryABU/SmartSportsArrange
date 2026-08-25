package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.RegistrationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 智能编排核心算法测试。
 * 覆盖：贪心分配完整性（每人恰好一次）、组数计算、空数据异常、预览不落库、
 * 查看空编排、版本回滚边界、Controller 适配方法。
 */
@ExtendWith(MockitoExtension.class)
class ArrangementServiceTest {

    @Mock private ArrangementRepository arrangementRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SystemService systemService;

    @InjectMocks private ArrangementService arrangementService;

    private Athlete athlete(Long id, String name, Long classId, String className) {
        ClassInfo ci = ClassInfo.builder().id(classId).name(className).build();
        return Athlete.builder().id(id).name(name).number("N" + id)
                .grade("高一年级").gender("男").classInfo(ci).build();
    }

    private Registration reg(Athlete a) {
        return Registration.builder().id(a.getId()).athlete(a)
                .event(Event.builder().id(100L).build()).status("approved").build();
    }

    private Map<String, Object> defaultRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("soft_constraints", new LinkedHashMap<>());
        rule.put("algorithm_params", new LinkedHashMap<>());
        return rule;
    }

    @Test
    void arrange_placesEveryAthleteExactlyOnce() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        for (int i = 5; i <= 6; i++) regs.add(reg(athlete((long) i, "B" + i, 2L, "高一2班")));

        when(systemService.getArrangeRule()).thenReturn(defaultRule());
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一年级", "男")).thenReturn(regs);
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(arrangementRepository.findMaxVersionByEventId(100L)).thenReturn(null);
        when(arrangementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 4, null);

        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(6, stats.get("totalAthletes"));
        assertEquals(2, stats.get("totalHeats")); // ceil(6/4) = 2

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
        assertEquals(2, heats.size());

        Set<Long> seen = new HashSet<>();
        int placed = 0;
        for (Map<String, Object> heat : heats) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lanes = (List<Map<String, Object>>) heat.get("lanes");
            for (Map<String, Object> lane : lanes) {
                Object aid = lane.get("athleteId");
                if (aid != null) {
                    placed++;
                    assertTrue(seen.add((Long) aid), "同一运动员被重复分配到多个道次");
                }
            }
        }
        assertEquals(6, placed);
        assertEquals(6, seen.size());
    }

    @Test
    void arrange_emptyRegistrations_throws() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        when(systemService.getArrangeRule()).thenReturn(defaultRule());
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一", "男"))
                .thenReturn(List.of());
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> arrangementService.arrange(100L, "高一", "男", 4, null));
        assertTrue(ex.getMessage().contains("没有符合条件"));
    }

    @Test
    void preview_doesNotPersist() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));

        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一年级", "男")).thenReturn(regs);
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));

        Map<String, Object> preview = arrangementService.preview(100L, "高一年级", "男", 4);
        Map<String, Object> stats = (Map<String, Object>) preview.get("statistics");
        assertEquals(6, stats.get("totalAthletes"));
        assertEquals(2, stats.get("totalHeats"));
        verify(arrangementRepository, never()).saveAll(any());
    }

    @Test
    void getArrangement_emptyReturnsZero() {
        when(eventRepository.findById(100L)).thenReturn(Optional.of(Event.builder().id(100L).name("x").build()));
        when(arrangementRepository.findByEventIdOrderByHeatAscLaneAsc(100L)).thenReturn(List.of());

        Map<String, Object> result = arrangementService.getArrangement(100L);
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(0, stats.get("totalAthletes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
        assertTrue(heats.isEmpty());
    }

    @Test
    void rollback_noPriorVersion_throws() {
        when(arrangementRepository.findMaxVersionByEventId(100L)).thenReturn(1);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> arrangementService.rollback(100L));
        assertTrue(ex.getMessage().contains("没有可回滚"));
    }

    @Test
    void executeArrangement_adapterIncludesExecutionTime() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        when(systemService.getArrangeRule()).thenReturn(defaultRule());
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一年级", "男")).thenReturn(regs);
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(arrangementRepository.findMaxVersionByEventId(100L)).thenReturn(null);
        when(arrangementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("grade", "高一年级");
        config.put("gender", "男");
        config.put("lanes", 4);

        Map<String, Object> result = arrangementService.executeArrangement(100L, config);
        assertTrue(result.containsKey("executionTimeMs"));
    }
}
