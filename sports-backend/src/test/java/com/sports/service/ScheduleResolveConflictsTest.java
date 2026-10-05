package com.sports.service;

import com.sports.collab.ScheduleCollaborationService;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.entity.registration.Registration;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.arrange.ArrangementReservationRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.venue.VenueRepository;
import com.sports.schedule.opt.alns.AlnsImprover;
import com.sports.schedule.opt.fixopt.FixAndOptimizer;
import com.sports.schedule.opt.ga.GeneticAlgorithm;
import com.sports.schedule.opt.lns.LnsImprover;
import com.sports.schedule.opt.mnsa.MultiNeighborhoodAnnealer;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.rule.RuleBasedScheduler;
import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.service.audit.AuditService;
import com.sports.service.schedule.ScheduleService;
import com.sports.service.system.SystemService;
import com.sports.service.protection.AdminTimeProtectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兼项冲突消解（resolveConflicts）的单调性与口径一致性测试。
 *
 * <p>背景缺陷（2026-09-27）：消解的全部重排趟都以「无决赛条目」的中间态落库并评估冲突，
 * 决赛条目要等 restoreFinalScheduleRows 从编排表补回（预赛结束+45min 追加）才计入——
 * 评估口径与交付口径断层，循环内选出的「最优趟」在补回决赛后冲突跳升，即
 * 「越消解冲突反而越多」。修复：① 精修初值/每趟/重跑三处先补回决赛条目再计数；
 * ② resolveConflicts 出口终检，若交付口径劣于入口则回滚入口快照（单调保底）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleResolveConflictsTest {

    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private com.sports.service.arrange.ArrangementService arrangementService;
    @Mock private SystemService systemService;
    @Mock private com.sports.service.arrange.ConflictService conflictService;
    @Mock private VenueRepository venueRepository;
    @Mock private EventRefereeRepository eventRefereeRepository;
    @Mock private ArrangementReservationRepository arrangementReservationRepository;
    @Mock private ScheduleOptimizer scheduleOptimizer;
    @Mock private ScheduleVerifier scheduleVerifier;
    @Mock private com.sports.schedule.analysis.LowerBoundEstimator lowerBoundEstimator;
    @Mock private LnsImprover lnsImprover;
    @Mock private GeneticAlgorithm geneticAlgorithm;
    @Mock private MultiNeighborhoodAnnealer mnsaAnnealer;
    @Mock private AlnsImprover alnsImprover;
    @Mock private FixAndOptimizer fixAndOptimizer;
    @Mock private RuleBasedScheduler ruleBasedScheduler;
    @Mock private ScheduleCollaborationService collaborationService;
    @Mock private AuditService auditService;
    @Mock private AdminTimeProtectionService protectionService;
    /** 组次错开消解：新依赖，缺 @Mock 会注入 null */
    @Mock private com.sports.service.arrange.HeatStaggerService heatStaggerService;

    @InjectMocks private ScheduleService scheduleService;

    /** 捕获落库的赛程行（含消解重排与回滚重建），供断言 */
    private final List<EventSchedule> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        saved.clear();
        when(scheduleRepository.save(any(EventSchedule.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        doAnswer(inv -> {
            saved.clear();
            return null;
        }).when(scheduleRepository).deleteAllSchedules();
        when(scheduleVerifier.verify(any(), any(), anyInt()))
                .thenReturn(new ScheduleVerifier.Result(true, 0, 0, List.of(), List.of(), null, null));
        // 事后检测桩：精修后的最终 result.conflicts 直接来自该桩，空清单即可
        when(conflictService.detectConflicts()).thenReturn(List.of());
        // 决赛条目补回（mock 外层服务；真实幂等行为由 ArrangementServiceTest 覆盖）
        when(arrangementService.restoreFinalScheduleRows()).thenReturn(0);
    }

    // ==================== 夹具 ====================

    private Map<String, Object> cfg() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("startDate", "2026-09-20");
        c.put("days", 1);
        c.put("gradeOrder", List.of("高一年级"));
        c.put("venues", List.of("田径场", "田赛A区"));
        c.put("trackSlots", 1);
        c.put("fieldSlots", 2);
        c.put("defaultDurationMinutes", 600);
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

    private Event field(Long id, String name) {
        return Event.builder().id(id).name(name).code("F" + id)
                .track(false).laneCount(0).category("田赛").genderLimit("男子组")
                .gradeGroup("高一年级").isEnabled(true).sortOrder(id.intValue()).build();
    }

    /** 两个项目共享同一批运动员 → 制造真实的兼项场景 */
    private void sharedRegs(Long eventA, Long eventB, int n) {
        List<Registration> regsA = new ArrayList<>();
        List<Registration> regsB = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Athlete a = Athlete.builder().id(500L + i).name("兼项运动员" + i)
                    .grade("高一年级").gender("M").build();
            regsA.add(Registration.builder().athlete(a).status("approved").build());
            regsB.add(Registration.builder().athlete(a).status("approved").build());
        }
        when(registrationRepository.findApprovedByEventId(eventA)).thenReturn(regsA);
        when(registrationRepository.findApprovedByEventId(eventB)).thenReturn(regsB);
    }

    /** 入口快照行：模拟「用户消解前库里已有」的赛程（含决赛条目的完整交付态） */
    private List<EventSchedule> entryScheduleRows(Event t, Event f) {
        List<EventSchedule> rows = new ArrayList<>();
        rows.add(EventSchedule.builder().event(t).day(1).scheduleDate("2026-09-20")
                .grade("高一年级").timeSlot("AM").startTime("08:00").endTime("08:30")
                .venue("田径场").sortOrder(1).durationMinutes(30).round("preliminary").build());
        rows.add(EventSchedule.builder().event(t).day(1).scheduleDate("2026-09-20")
                .grade("高一年级").timeSlot("AM").startTime("09:15").endTime("09:45")
                .venue("田径场").sortOrder(2).durationMinutes(30).round("final").build());
        rows.add(EventSchedule.builder().event(f).day(1).scheduleDate("2026-09-20")
                .grade("高一年级").timeSlot("AM").startTime("08:00").endTime("08:30")
                .venue("田赛A区").sortOrder(3).durationMinutes(30).round("final").build());
        return rows;
    }

    private List<Arrangement> entryArrangementRows(Event t, Event f) {
        List<Arrangement> rows = new ArrayList<>();
        Athlete a1 = Athlete.builder().id(500L).name("兼项运动员0").grade("高一年级").gender("M").build();
        Athlete a2 = Athlete.builder().id(501L).name("兼项运动员1").grade("高一年级").gender("M").build();
        rows.add(Arrangement.builder().event(t).athlete(a1).grade("高一年级").gender("M")
                .round("final").heat(1).lane(1).build());
        rows.add(Arrangement.builder().event(f).athlete(a1).grade("高一年级").gender("M")
                .round("final").position(1).build());
        rows.add(Arrangement.builder().event(f).athlete(a2).grade("高一年级").gender("M")
                .round("final").position(2).build());
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> guardOf(Map<String, Object> result) {
        Object pf = result.get("algorithmPortfolio");
        assertTrue(pf instanceof Map, "result.algorithmPortfolio 应存在");
        Object guard = ((Map<String, Object>) pf).get("resolveConflictGuard");
        assertTrue(guard instanceof Map, "消解守卫信息应写入 algorithmPortfolio");
        return (Map<String, Object>) guard;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("口径一致性：精修的初值与每趟评估前都必须先补回决赛条目（评估口径=交付口径）")
    void refineEvaluationMustRestoreFinalRowsFirst() {
        when(systemService.getMeetSchedule()).thenReturn(cfg());
        Event t = track(1L, "100米");
        Event f = field(2L, "铅球");
        when(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(t, f));
        sharedRegs(1L, 2L, 8);
        // 恒定 [3,1]：精修循环启动（realBest>0）但每趟都无改进 → stale 收敛退出
        when(conflictService.countConflicts()).thenReturn(new int[]{3, 1});
        when(scheduleRepository.findAll()).thenReturn(List.of());

        scheduleService.resolveConflicts(null);

        // 初值 1 次 + 每趟 ≥1 次 + 重跑/尾部 1 次：至少 3 次（旧实现只有尾部 1 次）
        verify(arrangementService, atLeast(3)).restoreFinalScheduleRows();
        assertFalse(saved.isEmpty(), "重排应产出赛程行");
    }

    @Test
    @DisplayName("单调保底：精修交付口径劣于入口时回滚快照，赛程/编排恢复为消解前内容")
    void rollbackWhenRefinedResultWorseThanEntry() {
        when(systemService.getMeetSchedule()).thenReturn(cfg());
        Event t = track(1L, "100米");
        Event f = field(2L, "铅球");
        when(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(t, f));
        sharedRegs(1L, 2L, 8);
        List<EventSchedule> snapshot = entryScheduleRows(t, f);
        List<Arrangement> snapshotArr = entryArrangementRows(t, f);
        when(scheduleRepository.findAll()).thenReturn(snapshot);
        when(arrangementRepository.findAll()).thenReturn(snapshotArr);

        // 第 1 次调用 = 入口基线 [2,2]（完整交付口径）；其后全部 [9,5]：
        // 模拟「Phase 1 内存口径选出的趟 + 补回决赛条目后」冲突跳升且精修 60 趟无法改善——
        // 修复前的口径断层正是这个形态（评估看不见决赛条目，交付却含）。
        AtomicInteger calls = new AtomicInteger();
        when(conflictService.countConflicts()).thenAnswer(inv -> calls.getAndIncrement() == 0
                ? new int[]{2, 2} : new int[]{9, 5});

        Map<String, Object> result = scheduleService.resolveConflicts(null);

        Map<String, Object> guard = guardOf(result);
        assertEquals(Boolean.TRUE, guard.get("rolledBack"), "终检劣于入口必须回滚");
        assertEquals(2, guard.get("entryTotal"));
        assertEquals(9, guard.get("finalTotal"));
        // 回滚动作：清空重排产物 → 重建入口快照
        verify(arrangementRepository).deleteAll();
        verify(arrangementRepository, atLeast(3)).save(any(Arrangement.class));
        // saved 在回滚 deleteAllSchedules 后被清空，最终只含快照重建的 3 行
        assertEquals(3, saved.size(), "回滚后赛程表应恢复为入口快照的 3 行");
        assertEquals("09:15", saved.get(1).getStartTime(), "决赛条目应按快照原样恢复");
        // 回滚说明必须写进 warnings，让人看见「消解未果、已保留原方案」
        boolean noted = false;
        for (Object w : (List<?>) result.get("warnings")) {
            if (String.valueOf(w).contains("已保留消解前的赛程表")) noted = true;
        }
        assertTrue(noted, "回滚必须有告警说明");
    }

    @Test
    @DisplayName("不误回滚：精修后不劣于入口时保留重排结果，不触碰快照")
    void keepResultWhenFinalNotWorse() {
        when(systemService.getMeetSchedule()).thenReturn(cfg());
        Event t = track(1L, "100米");
        Event f = field(2L, "铅球");
        when(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(t, f));
        sharedRegs(1L, 2L, 8);
        when(scheduleRepository.findAll()).thenReturn(List.of());
        // 恒 [0,0]：Phase 2 精修直接跳过（realBest=0），终检不劣于入口 → 不回滚
        when(conflictService.countConflicts()).thenReturn(new int[]{0, 0});

        Map<String, Object> result = scheduleService.resolveConflicts(null);

        assertEquals(Boolean.FALSE, guardOf(result).get("rolledBack"));
        verify(arrangementRepository, never()).deleteAll();
        assertFalse(saved.isEmpty(), "重排结果应保留");
    }
}
