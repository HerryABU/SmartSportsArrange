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
 * 编排服务测试。
 * 覆盖：同组不同班硬约束、每人恰好一次、组数计算、预赛编排、预赛淘汰立刻计算、
 * 预览不落库、空编排视图、回滚边界。
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

    /** 编排 Mock 基线（auto 直接决赛路径） */
    private void stubDirectArrange(Event event, List<Registration> regs) {
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(registrationRepository.findApprovedByEventGradeGender(eq(event.getId()), anyString(), anyString()))
                .thenReturn(regs);
        when(arrangementRepository.countPreliminaryByEventId(event.getId())).thenReturn(0L);
        when(arrangementRepository.findMaxVersionByEventId(event.getId())).thenReturn(null);
        when(arrangementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void arrange_hardConstraint_noSameClassInSameHeat() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        // 高一1班 3人 + 高一2班 3人
        for (int i = 1; i <= 3; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        for (int i = 4; i <= 6; i++) regs.add(reg(athlete((long) i, "B" + i, 2L, "高一2班")));
        stubDirectArrange(event, regs);

        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 4, null);
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(6, stats.get("totalAthletes"));
        // 硬约束：组数 = max(ceil(6/4)=2, 最大单班3) = 3
        assertEquals(3, stats.get("totalHeats"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
        for (Map<String, Object> heat : heats) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lanes = (List<Map<String, Object>>) heat.get("lanes");
            Set<Long> classIds = new HashSet<>();
            for (Map<String, Object> lane : lanes) {
                if (lane.get("athleteId") == null) continue;
                // 通过 arrangementId 反查班号不可行，改为直接断言：组内各班互不相同
                String className = (String) lane.get("className");
                assertTrue(classIds.add(classHash(className)), "同一组出现同班：" + className + " → " + lanes);
            }
        }
    }

    private Long classHash(String className) {
        // 类名 → 稳定 Long，用于同组去重断言
        return switch (className) {
            case "高一1班" -> 1L;
            case "高一2班" -> 2L;
            case "高一3班" -> 3L;
            default -> Long.valueOf(className.hashCode());
        };
    }

    @Test
    void arrange_placesEveryAthleteExactlyOnce() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        for (int i = 5; i <= 6; i++) regs.add(reg(athlete((long) i, "B" + i, 2L, "高一2班")));
        stubDirectArrange(event, regs);

        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 4, null);

        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(6, stats.get("totalAthletes"));
        // 硬约束：高一1班 4 人 → 至少 4 组
        assertEquals(4, stats.get("totalHeats"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
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
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一", "男"))
                .thenReturn(List.of());

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
        // 全部同一班 → 组数 = 人数（硬约束下限），每组 1 人
        assertEquals(6, stats.get("totalHeats"));
        verify(arrangementRepository, never()).saveAll(any());
    }

    @Test
    void generatePreliminary_createsPreliminaryRound() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4)
                .needHeats(true).advanceCount(3).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) regs.add(reg(athlete((long) i, "A" + i,
                i % 2 == 1 ? 1L : 2L, i % 2 == 1 ? "高一1班" : "高一2班")));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一年级", "男"))
                .thenReturn(regs);
        when(arrangementRepository.findMaxVersionByEventId(100L)).thenReturn(null);
        when(arrangementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = arrangementService.generatePreliminary(100L, "高一年级", "男");

        assertEquals("preliminary", result.get("round"));
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(5, stats.get("totalAthletes"));
        verify(arrangementRepository).deleteByEventRoundGradeGender(100L, "preliminary", "高一年级", "男");
    }

    @Test
    void computeQualifiers_immediatelyRanksAndGeneratesFinals() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4)
                .needHeats(true).advanceCount(3).build();

        // 预赛编排：5 人分布在 3 组
        List<Arrangement> prelims = new ArrayList<>();
        double[] times = {12.5, 12.8, 13.0, 13.2, 13.5};
        for (int i = 0; i < 5; i++) {
            Athlete a = athlete((long) i + 1, "A" + (i + 1), i % 2 == 1 ? 2L : 1L,
                    i % 2 == 1 ? "高一2班" : "高一1班");
            Arrangement arr = Arrangement.builder()
                    .id((long) i + 1)
                    .event(event)
                    .athlete(a)
                    .grade("高一年级").gender("男")
                    .heat(i / 2 + 1)
                    .lane((i % 2) + 1)
                    .round("preliminary")
                    .build();
            arr.setPrelimTime(String.valueOf(times[i]));
            arr.setPrelimTimeSeconds(times[i]);
            prelims.add(arr);
        }

        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(arrangementRepository.findByEventRoundGradeGender(100L, "preliminary", "高一年级", "男"))
                .thenReturn(prelims);
        when(arrangementRepository.findMaxVersionByEventId(100L)).thenReturn(null);
        when(arrangementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = arrangementService.computeQualifiers(100L, "高一年级", "男", null);

        assertEquals(5, result.get("participants"));
        assertEquals(3, result.get("qualifierCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qualifiers = (List<Map<String, Object>>) result.get("qualifiers");
        assertEquals(3, qualifiers.size());
        // 成绩前三名晋级（12.5 / 12.8 / 13.0）
        assertEquals("A1", qualifiers.get(0).get("athleteName"));
        assertEquals("A3", qualifiers.get(2).get("athleteName"));
        // 决赛编排只排 3 人
        @SuppressWarnings("unchecked")
        Map<String, Object> finals = (Map<String, Object>) result.get("final");
        Map<String, Object> finalStats = (Map<String, Object>) finals.get("statistics");
        assertEquals(3, finalStats.get("totalAthletes"));
        assertEquals("final", finals.get("round"));
        verify(arrangementRepository).deleteByEventRoundGradeGender(100L, "final", "高一年级", "男");
    }

    @Test
    void getArrangement_emptyReturnsZero() {
        when(eventRepository.findById(100L)).thenReturn(Optional.of(Event.builder().id(100L).name("x").build()));
        when(arrangementRepository.findByEventId(100L)).thenReturn(List.of());

        Map<String, Object> result = arrangementService.getArrangement(100L);
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(0, stats.get("totalAthletes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
        assertTrue(heats.isEmpty());
    }

    @Test
    void rollback_noRows_throws() {
        when(eventRepository.findById(100L)).thenReturn(Optional.of(Event.builder().id(100L).name("x").build()));
        when(arrangementRepository.findByEventId(100L)).thenReturn(List.of());
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> arrangementService.rollback(100L));
        assertTrue(ex.getMessage().contains("没有可回滚"));
    }

    @Test
    void executeArrangement_adapterIncludesExecutionTime() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        stubDirectArrange(event, regs);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("grade", "高一年级");
        config.put("gender", "男");
        config.put("lanes", 4);

        Map<String, Object> result = arrangementService.executeArrangement(100L, config);
        assertTrue(result.containsKey("executionTimeMs"));
    }
}
