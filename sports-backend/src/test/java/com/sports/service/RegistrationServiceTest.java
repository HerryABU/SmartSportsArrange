package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 报名服务约束逻辑测试。
 * 覆盖：成功报名、重复报名拦截、性别不匹配拦截、班级/个人报名上限拦截、审核/取消/统计。
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock private RegistrationRepository registrationRepository;
    @Mock private AthleteRepository athleteRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SystemConfigRepository systemConfigRepository;

    @InjectMocks private RegistrationService registrationService;

    private Athlete athlete(Long id, String gender, Long classId) {
        ClassInfo ci = ClassInfo.builder().id(classId).name("高一1班").build();
        return Athlete.builder().id(id).name("选手").gender(gender).classInfo(ci).build();
    }

    private Event event(Long id, String genderLimit) {
        return Event.builder().id(id).name("100m").genderLimit(genderLimit).build();
    }

    @Test
    void create_success() {
        Athlete a = athlete(10L, "M", 5L);
        Event e = event(20L, null);
        when(athleteRepository.findById(10L)).thenReturn(Optional.of(a));
        when(eventRepository.findById(20L)).thenReturn(Optional.of(e));
        when(registrationRepository.existsByAthleteIdAndEventId(10L, 20L)).thenReturn(false);
        when(registrationRepository.countByClassAndEvent(5L, 20L)).thenReturn(0L);
        when(registrationRepository.findByAthleteId(10L)).thenReturn(List.of());
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        Registration saved = registrationService.create(10L, 20L);
        assertNotNull(saved);
        assertEquals("pending", saved.getStatus());
        verify(registrationRepository).save(any(Registration.class));
    }

    @Test
    void create_duplicate_throws() {
        Athlete a = athlete(10L, "M", 5L);
        Event e = event(20L, null);
        when(athleteRepository.findById(10L)).thenReturn(Optional.of(a));
        when(eventRepository.findById(20L)).thenReturn(Optional.of(e));
        when(registrationRepository.existsByAthleteIdAndEventId(10L, 20L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registrationService.create(10L, 20L));
        assertTrue(ex.getMessage().contains("已报名"));
    }

    @Test
    void create_genderMismatch_throws() {
        Athlete a = athlete(10L, "女", 5L);
        Event e = event(20L, "男"); // 仅限男生
        when(athleteRepository.findById(10L)).thenReturn(Optional.of(a));
        when(eventRepository.findById(20L)).thenReturn(Optional.of(e));
        when(registrationRepository.existsByAthleteIdAndEventId(10L, 20L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registrationService.create(10L, 20L));
        assertTrue(ex.getMessage().contains("性别不符合"));
    }

    @Test
    void create_classLimitReached_throws() {
        Athlete a = athlete(10L, "M", 5L);
        Event e = event(20L, null);
        when(athleteRepository.findById(10L)).thenReturn(Optional.of(a));
        when(eventRepository.findById(20L)).thenReturn(Optional.of(e));
        when(registrationRepository.existsByAthleteIdAndEventId(10L, 20L)).thenReturn(false);
        when(registrationRepository.countByClassAndEvent(5L, 20L)).thenReturn(3L); // 已达上限

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registrationService.create(10L, 20L));
        assertTrue(ex.getMessage().contains("已达上限"));
    }

    @Test
    void create_athleteLimitReached_throws() {
        Athlete a = athlete(10L, "M", 5L);
        Event e = event(20L, null);
        // 该运动员已有 3 条有效报名
        List<Registration> existing = List.of(
                Registration.builder().status("approved").build(),
                Registration.builder().status("pending").build(),
                Registration.builder().status("approved").build());
        when(athleteRepository.findById(10L)).thenReturn(Optional.of(a));
        when(eventRepository.findById(20L)).thenReturn(Optional.of(e));
        when(registrationRepository.existsByAthleteIdAndEventId(10L, 20L)).thenReturn(false);
        when(registrationRepository.countByClassAndEvent(5L, 20L)).thenReturn(0L);
        when(registrationRepository.findByAthleteId(10L)).thenReturn(existing);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registrationService.create(10L, 20L));
        assertTrue(ex.getMessage().contains("报名项目已达上限"));
    }

    @Test
    void approve_setsApprovedStatus() {
        Registration reg = Registration.builder().id(1L).status("pending").build();
        when(registrationRepository.findById(1L)).thenReturn(Optional.of(reg));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        Registration approved = registrationService.approve(1L, "ok");
        assertEquals("approved", approved.getStatus());
        assertEquals("ok", approved.getAuditRemark());
    }

    @Test
    void cancel_setsWithdrawn() {
        Registration reg = Registration.builder().id(2L).status("approved").build();
        when(registrationRepository.findById(2L)).thenReturn(Optional.of(reg));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        registrationService.cancel(2L);
        assertEquals("withdrawn", reg.getStatus());
    }

    @Test
    void statistics_countsByStatus() {
        List<Registration> all = List.of(
                Registration.builder().status("approved").build(),
                Registration.builder().status("approved").build(),
                Registration.builder().status("pending").build());
        when(registrationRepository.findAll()).thenReturn(all);

        var stats = registrationService.statistics();
        assertEquals(3L, stats.get("total"));
        assertEquals(2L, stats.get("approved"));
        assertEquals(1L, stats.get("pending"));
    }
}
