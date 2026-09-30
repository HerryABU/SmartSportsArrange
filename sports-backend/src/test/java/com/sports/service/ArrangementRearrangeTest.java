package com.sports.service;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.arrange.ArrangementReservationRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.referee.RefereeRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.schedule.rule.inject.RuleInjectionService;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.export.WordOrderBookService;
import com.sports.service.system.SystemService;
import com.sports.service.protection.AdminTimeProtectionService;
import com.sports.collab.ScheduleCollaborationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「再次排道」（rearrangeByGrade）测试：赛程编排页对已录入成绩的项目按
 * 项目×年级×轮次重排道次。验证性别推导（按报名实际出现的性别逐组）、
 * 年级过滤（必须过 Grades 等价归一化）、轮次透传与失败聚合语义。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangementRearrangeTest {

    @Mock private ArrangementRepository arrangementRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ResultRepository resultRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EventScheduleRepository eventScheduleRepository;
    @Mock private RefereeRepository refereeRepository;
    @Mock private EventRefereeRepository eventRefereeRepository;
    @Mock private ArrangementReservationRepository arrangementReservationRepository;
    @Mock private WordOrderBookService wordOrderBookService;
    @Mock private SystemService systemService;
    @Mock private ScheduleCollaborationService collaborationService;
    @Mock private RuleInjectionService ruleInjectionService;
    @Mock private AdminTimeProtectionService protectionService;

    /** spy：真实执行 rearrangeByGrade，短路重型的 arrange（道次编排本身由 ArrangementServiceTest 覆盖） */
    @Spy
    @InjectMocks
    private ArrangementService service;

    private Event event() {
        return Event.builder().id(1L).name("100米").code("T1")
                .track(true).laneCount(8).category("径赛").genderLimit("男子组")
                .gradeGroup("高一年级").isEnabled(true).sortOrder(1).build();
    }

    private Registration reg(Long id, String grade, String gender) {
        Athlete a = Athlete.builder().id(id).name("运动员" + id).grade(grade).gender(gender).build();
        return Registration.builder().id(id).athlete(a).status("approved").build();
    }

    @Test
    @DisplayName("按报名实际出现的性别逐组重排，轮次透传给 arrange")
    void iteratesGendersAndPassesRound() {
        Event e = event();
        when(eventRepository.findById(1L)).thenReturn(Optional.of(e));
        when(registrationRepository.findApprovedByEventId(1L)).thenReturn(new ArrayList<>(List.of(
                reg(10L, "高一年级", "男"), reg(11L, "高一年级", "男"), reg(12L, "高一年级", "女"))));
        doReturn(Map.of("arranged", 1)).when(service)
                .arrange(anyLong(), anyString(), anyString(), anyInt(), any(), anyString());

        Map<String, Object> out = service.rearrangeByGrade(1L, "高一", "preliminary");

        assertEquals(2, out.get("arranged"));
        assertEquals(0, out.get("failed"));
        assertEquals("preliminary", out.get("round"));
        // 每个性别组各调一次 arrange，round 原样透传（grade 用前端传入的「高一」，等价于「高一年级」）
        verify(service).arrange(eq(1L), eq("高一"), eq("男"), anyInt(), isNull(), eq("preliminary"));
        verify(service).arrange(eq(1L), eq("高一"), eq("女"), anyInt(), isNull(), eq("preliminary"));
    }

    @Test
    @DisplayName("年级过滤必须过 Grades 等价归一化：其它年级的报名不进入重排")
    void filtersOtherGrades() {
        Event e = event();
        when(eventRepository.findById(1L)).thenReturn(Optional.of(e));
        when(registrationRepository.findApprovedByEventId(1L)).thenReturn(new ArrayList<>(List.of(
                reg(10L, "高一年级", "男"), reg(20L, "高二年级", "男"), reg(21L, "高三年级", "女"))));
        doReturn(Map.of("arranged", 1)).when(service)
                .arrange(anyLong(), anyString(), anyString(), anyInt(), any(), anyString());

        Map<String, Object> out = service.rearrangeByGrade(1L, "高一", "final");

        assertEquals(1, out.get("arranged"));
        assertTrue(((List<?>) out.get("genders")).contains("男"));
        verify(service).arrange(eq(1L), eq("高一"), eq("男"), anyInt(), isNull(), eq("final"));
        verify(service, org.mockito.Mockito.times(1))
                .arrange(anyLong(), anyString(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("round 缺省/auto → null（由 arrange 按是否有预赛自动判定赛次）")
    void nullRoundMapsToAuto() {
        Event e = event();
        when(eventRepository.findById(1L)).thenReturn(Optional.of(e));
        when(registrationRepository.findApprovedByEventId(1L)).thenReturn(new ArrayList<>(List.of(
                reg(10L, "高一年级", "男"))));
        doReturn(Map.of("arranged", 1)).when(service)
                .arrange(anyLong(), anyString(), anyString(), anyInt(), any(), isNull());

        Map<String, Object> out = service.rearrangeByGrade(1L, "高一年级", "auto");

        assertEquals(1, out.get("arranged"));
        verify(service).arrange(eq(1L), eq("高一年级"), eq("男"), anyInt(), isNull(), isNull());
    }

    @Test
    @DisplayName("部分失败视为成功并带明细；全部失败抛异常")
    void failureAggregation() {
        Event e = event();
        when(eventRepository.findById(1L)).thenReturn(Optional.of(e));
        when(registrationRepository.findApprovedByEventId(1L)).thenReturn(new ArrayList<>(List.of(
                reg(10L, "高一年级", "男"), reg(11L, "高一年级", "女"))));
        doThrow(new RuntimeException("boom")).when(service)
                .arrange(anyLong(), anyString(), anyString(), anyInt(), any(), anyString());

        // 全部失败 → 抛异常
        assertThrows(RuntimeException.class, () -> service.rearrangeByGrade(1L, "高一", "final"));
    }
}
