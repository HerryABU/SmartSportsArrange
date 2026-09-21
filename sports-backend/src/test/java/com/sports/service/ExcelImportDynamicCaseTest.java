package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import com.sports.support.ExcelTestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Excel 导入「动态用例」测试——用例不再硬编码二进制样例，而是用 {@link ExcelTestDataFactory}
 * 现场生成 .xlsx（与生产模板同结构），再走真实的 {@code ExcelService.importWithMapping} 链路。
 *
 * <p>覆盖本轮新增三张表：全名单表(roster)、报名表(signup)、运动项目表(eventsimple)，
 * 并顺带验证「趣味运动会落在跑道场地 → occupiesTrack」「个人项目严禁填组号」等业务规则。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Excel 导入动态用例")
class ExcelImportDynamicCaseTest {

    @Mock private AthleteRepository athleteRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ResultRepository resultRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private EventRefereeRepository eventRefereeRepository;
    @Mock private RefereeRepository refereeRepository;
    @Mock private VenueRepository venueRepository;
    /** ExcelService 新增依赖（年级表导入）——漏了会注入 null，年级行处理直接 NPE。 */
    @Mock private GradeService gradeService;

    @InjectMocks private ExcelService excelService;

    // 与各模板列序严格一致的固定列映射（前端也是按此下发）
    private static final Map<String, String> ROSTER_MAP = Map.of(
            "0", "grade", "1", "className", "2", "name", "3", "studentId", "4", "gender");
    private static final Map<String, String> SIGNUP_MAP = Map.of(
            "0", "grade", "1", "className", "2", "name", "3", "studentId",
            "4", "gender", "5", "eventCode", "6", "teamTag");
    private static final Map<String, String> EVENTSIMPLE_MAP = Map.of(
            "0", "eventCode", "1", "eventName", "2", "teamMembers", "3", "concurrency",
            "4", "category", "5", "defaultVenueCode", "6", "perBatchMinutes");

    private static MockMultipartFile file(byte[] bytes, String name) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static Map<String, Object> mapping(String type, Map<String, String> columnMap) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("hasHeader", true);
        m.put("columnMap", columnMap);
        return m;
    }

    // ==================== 全名单表（roster） ====================

    @Test
    @DisplayName("全名单表：班级缺失自动创建 + 运动员新建/性别归一")
    void rosterAutoCreateClass() {
        when(classInfoRepository.findByGradeAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(classInfoRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(classInfoRepository.existsByCode(anyString())).thenReturn(false);
        when(classInfoRepository.save(any(ClassInfo.class))).thenAnswer(inv -> {
            ClassInfo c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });
        when(athleteRepository.findByStudentId(anyString())).thenReturn(Optional.empty());
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] xlsx = ExcelTestDataFactory.roster(new String[][]{
                {"高一年级", "高一1班", "张三", "2024001", "男"}});
        Map<String, Object> result = excelService.importWithMapping(
                file(xlsx, "全名单表.xlsx"), mapping("roster", ROSTER_MAP));

        assertEquals(1, result.get("success"));
        assertEquals(0, result.get("failed"));

        ArgumentCaptor<Athlete> cap = ArgumentCaptor.forClass(Athlete.class);
        verify(athleteRepository).save(cap.capture());
        Athlete saved = cap.getValue();
        assertEquals("张三", saved.getName());
        assertEquals("M", saved.getGender(), "男 → M");
        assertEquals("2024001", saved.getStudentId());
        assertNotNull(saved.getClassInfo(), "班级缺失时应自动创建并关联");
        assertEquals("高一1班", saved.getClassInfo().getName());
        verify(classInfoRepository).save(any(ClassInfo.class));
    }

    @Test
    @DisplayName("全名单表：按学号 upsert（已存在则更新，不重复建档）")
    void rosterUpsertExisting() {
        ClassInfo ci = ClassInfo.builder().id(5L).name("高一1班").grade("高一年级").build();
        Athlete existing = Athlete.builder().id(9L).name("旧名").studentId("2024001").build();
        when(classInfoRepository.findByGradeAndName("高一年级", "高一1班")).thenReturn(Optional.of(ci));
        when(athleteRepository.findByStudentId("2024001")).thenReturn(Optional.of(existing));
        when(athleteRepository.save(any(Athlete.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] xlsx = ExcelTestDataFactory.roster(new String[][]{
                {"高一年级", "高一1班", "张三", "2024001", "男"}});
        Map<String, Object> result = excelService.importWithMapping(
                file(xlsx, "全名单表.xlsx"), mapping("roster", ROSTER_MAP));

        assertEquals(1, result.get("success"));
        verify(athleteRepository).save(existing);       // 同一实例被更新
        assertEquals("张三", existing.getName(), "应更新为新姓名");
        assertSame(ci, existing.getClassInfo());
        verify(classInfoRepository, never()).save(any(ClassInfo.class));  // 班级已存在，不新建
    }

    // ==================== 报名表（signup） ====================

    @Test
    @DisplayName("报名表：团体/接力 组号 → teamTag，置 approved/offline")
    void signupTeamTag() {
        Event relay = Event.builder().id(10L).code("4X100M").name("4×100米接力")
                .team(true).teamMembers(4).build();
        Athlete a = Athlete.builder().id(7L).name("张三").studentId("2024001").build();
        when(eventRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(eventRepository.findByNameAndIsEnabledTrue(eq("4×100米接力"))).thenReturn(Optional.of(relay));
        when(athleteRepository.findByStudentId("2024001")).thenReturn(Optional.of(a));
        when(registrationRepository.existsByAthleteIdAndEventId(7L, 10L)).thenReturn(false);
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] xlsx = ExcelTestDataFactory.signup(new String[][]{
                {"高一年级", "高一1班", "张三", "2024001", "男", "4×100米接力", "A"}});
        Map<String, Object> result = excelService.importWithMapping(
                file(xlsx, "报名表.xlsx"), mapping("signup", SIGNUP_MAP));

        assertEquals(1, result.get("success"));
        ArgumentCaptor<Registration> cap = ArgumentCaptor.forClass(Registration.class);
        verify(registrationRepository).save(cap.capture());
        Registration reg = cap.getValue();
        assertEquals("A", reg.getTeamTag(), "组号应写入 teamTag");
        assertTrue(reg.getTeam(), "接力项目应标记团体");
        assertEquals("approved", reg.getStatus());
        assertEquals("offline", reg.getSource());
        assertSame(relay, reg.getEvent());
        assertSame(a, reg.getAthlete());
    }

    @Test
    @DisplayName("报名表：个人项目填组号 → 报错且不落库")
    void signupIndividualRejectsTeamTag() {
        Event individual = Event.builder().id(11L).code("100M").name("100米")
                .team(false).teamMembers(1).build();
        Athlete a = Athlete.builder().id(7L).name("张三").studentId("2024001").build();
        when(eventRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(eventRepository.findByNameAndIsEnabledTrue(eq("100米"))).thenReturn(Optional.of(individual));
        when(athleteRepository.findByStudentId("2024001")).thenReturn(Optional.of(a));

        byte[] xlsx = ExcelTestDataFactory.signup(new String[][]{
                {"高一年级", "高一1班", "张三", "2024001", "男", "100米", "A"}});
        Map<String, Object> result = excelService.importWithMapping(
                file(xlsx, "报名表.xlsx"), mapping("signup", SIGNUP_MAP));

        assertEquals(0, result.get("success"));
        assertEquals(1, result.get("failed"), "个人项目填组号应失败");
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    // ==================== 运动项目表（eventsimple） ====================

    @Test
    @DisplayName("运动项目表：趣味运动会落在跑道场地 → funSports + occupiesTrack（与径赛错开）")
    void eventsimpleFunOnTrackVenue() {
        Venue track = Venue.builder().code("TRACK").name("主跑道").type("track").build();
        when(eventRepository.existsByCode("TUG")).thenReturn(false);
        when(venueRepository.findByCode("TRACK")).thenReturn(Optional.of(track));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] xlsx = ExcelTestDataFactory.eventsimple(new String[][]{
                {"TUG", "拔河", "15", "1", "趣味运动会", "TRACK", "300"}});
        Map<String, Object> result = excelService.importWithMapping(
                file(xlsx, "运动项目表.xlsx"), mapping("eventsimple", EVENTSIMPLE_MAP));

        assertEquals(1, result.get("success"));
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(cap.capture());
        Event ev = cap.getValue();
        assertTrue(ev.getFunSports(), "趣味运动会应标记 funSports");
        assertTrue(ev.getOccupiesTrack(), "落在跑道场地应置 occupiesTrack 以便与径赛错开");
        assertTrue(ev.getTeam(), "每组人数 15 > 1 应标记团体");
        assertEquals(15, ev.getTeamMembers().intValue());
        assertEquals("TRACK", ev.getDefaultVenueCode());
    }

    @Test
    @DisplayName("运动项目表：径赛按每批组数置道次，且非跑道场地不置 occupiesTrack")
    void eventsimpleTrackLaneCount() {
        when(eventRepository.existsByCode("100M")).thenReturn(false);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] xlsx = ExcelTestDataFactory.eventsimple(new String[][]{
                {"100M", "100米", "1", "6", "径赛", "TRACK", "20"}});
        Map<String, Object> result = excelService.importWithMapping(
                file(xlsx, "运动项目表.xlsx"), mapping("eventsimple", EVENTSIMPLE_MAP));

        assertEquals(1, result.get("success"));
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(cap.capture());
        Event ev = cap.getValue();
        assertTrue(ev.getTrack());
        assertEquals(6, ev.getLaneCount().intValue(), "径赛道次 = 每批组数");
        assertFalse(ev.getFunSports());
        assertFalse(ev.getOccupiesTrack(), "径赛本身不置 occupiesTrack");
    }
}
