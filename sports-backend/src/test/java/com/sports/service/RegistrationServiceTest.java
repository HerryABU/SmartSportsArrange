package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 报名服务约束逻辑测试。
 * 覆盖：成功报名、重复报名拦截、性别不匹配拦截、班级/个人报名上限拦截、审核/取消/统计、
 *       报名表（表格1）导入。
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock private RegistrationRepository registrationRepository;
    @Mock private AthleteRepository athleteRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private SystemConfigRepository systemConfigRepository;
    @Mock private NumberRuleService numberRuleService;

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

    // ==================== 报名表（表格1）导入 ====================

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "signup.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importSignupSheet_offlineCreatesAthletesAndRegistrations() {
        ClassInfo ci = ClassInfo.builder().id(5L).name("高一1班").grade("高一").build();
        Event e100 = Event.builder().id(1L).name("100米").genderLimit("M").build();
        Event e200 = Event.builder().id(2L).name("200米").genderLimit(null).build();

        when(classInfoRepository.findByName("高一1班")).thenReturn(Optional.of(ci));
        when(eventRepository.findByCode("100M")).thenReturn(Optional.of(e100));
        when(eventRepository.findByCode("200M")).thenReturn(Optional.empty());
        when(eventRepository.findByNameAndIsEnabledTrue("200M")).thenReturn(Optional.of(e200));
        when(numberRuleService.generateNumber(any(), eq(ci), anyInt()))
                .thenAnswer(inv -> "N" + inv.getArgument(2));
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = "年级,班级,姓名,性别,学号,项目,是否团体赛数量,成绩\n"
                + "高一年级,高一1班,张三,男,20260001,100M,0,\n"
                + "高一年级,高一1班,李四,女,20260002,200M,0,\n";

        Map<String, Object> result = registrationService.importSignupSheet(csv(body), "offline");
        assertEquals(2, result.get("success"));
        assertEquals(2, result.get("createdAthletes"));
        assertEquals(0, result.get("failed"));
        assertEquals("approved", result.get("status"));

        verify(registrationRepository, times(2)).save(any(Registration.class));
        // 顺带校验 genderLimit 校验：100米仅限 M，张三(男) 通过；无多余失败
    }

    @Test
    @SuppressWarnings("unchecked")
    void importSignupSheet_duplicateRowsAreSkipped() {
        ClassInfo ci = ClassInfo.builder().id(5L).name("高一1班").grade("高一").build();
        Event e100 = Event.builder().id(1L).name("100米").genderLimit(null).build();
        Athlete existing = Athlete.builder().id(50L).name("张三").gender("M")
                .studentId("20260001").number("A001").classInfo(ci).build();

        when(classInfoRepository.findByName("高一1班")).thenReturn(Optional.of(ci));
        when(eventRepository.findByCode("100M")).thenReturn(Optional.of(e100));
        when(athleteRepository.findByStudentId("20260001")).thenReturn(Optional.of(existing));
        when(registrationRepository.existsByAthleteIdAndEventId(50L, 1L)).thenReturn(true);

        String body = "年级,班级,姓名,性别,学号,项目,是否团体赛数量,成绩\n"
                + "高一年级,高一1班,张三,男,20260001,100M,0,\n"
                + "高一年级,高一1班,张三,男,20260001,100M,0,\n";

        Map<String, Object> result = registrationService.importSignupSheet(csv(body), "onsite");
        assertEquals(0, result.get("success"));
        assertEquals(2, result.get("skipped"));
        assertEquals("pending", result.get("status"));
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importSignupSheet_missingEventReportsError() {
        ClassInfo ci = ClassInfo.builder().id(5L).name("高一1班").grade("高一").build();
        when(classInfoRepository.findByName("高一1班")).thenReturn(Optional.of(ci));
        when(eventRepository.findByCode("999M")).thenReturn(Optional.empty());
        when(eventRepository.findByNameAndIsEnabledTrue("999M")).thenReturn(Optional.empty());
        when(eventRepository.findByIsEnabledTrueAndNameContaining("999M")).thenReturn(List.of());

        String body = "年级,班级,姓名,性别,学号,项目,是否团体赛数量,成绩\n"
                + "高一年级,高一1班,张三,男,20260001,999M,0,\n";

        Map<String, Object> result = registrationService.importSignupSheet(csv(body), "offline");
        assertEquals(0, result.get("success"));
        assertEquals(1, result.get("failed"));
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertTrue(String.valueOf(errors.get(0).get("message")).contains("未找到项目"));
    }
}
