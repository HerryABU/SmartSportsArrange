package com.sports.service;

import com.sports.collab.ScheduleCollaborationService;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.entity.registration.Registration;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.arrange.ArrangementReservationRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.referee.RefereeRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.schedule.rule.inject.RuleContext;
import com.sports.schedule.rule.inject.RuleInjectionService;
import com.sports.schedule.rule.inject.RuleOutcome;
import com.sports.schedule.rule.style.L1Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.export.WordOrderBookService;
import com.sports.service.system.SystemService;

/**
 * 编排服务测试。
 * 覆盖：同组不同班硬约束、每人恰好一次、组数计算、预赛编排、预赛淘汰立刻计算、
 * 预览不落库、空编排视图、回滚边界。
 */
@ExtendWith(MockitoExtension.class)
class ArrangementServiceTest {

    @Mock private ArrangementRepository arrangementRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ResultRepository resultRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EventScheduleRepository eventScheduleRepository;
    // 服务后续新增的依赖：漏 @Mock 会让 @InjectMocks 注入 null，调用处直接 NPE
    // （曾导致本测试类 6 个用例长期报 "eventRefereeRepository is null"）。
    @Mock private RefereeRepository refereeRepository;
    @Mock private EventRefereeRepository eventRefereeRepository;
    @Mock private ArrangementReservationRepository arrangementReservationRepository;
    @Mock private WordOrderBookService wordOrderBookService;
    @Mock private SystemService systemService;
    @Mock private ScheduleCollaborationService collaborationService;
    @Mock private RuleInjectionService ruleInjectionService;

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
        when(registrationRepository.findApprovedByEventGradeGender(eq(event.getId()), anyString(), anyString(), anyString()))
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

    /** L1「蛇形排布」模式：按年级/班级排序 S 形分散，且有意放开「同组不同班」硬约束（自检仍有效）。 */
    @Test
    void arrange_snakeMode_usesGradeClassSnakeAndRelaxesSameClassRule() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        for (int i = 4; i <= 6; i++) regs.add(reg(athlete((long) i, "B" + i, 2L, "高一2班")));
        stubDirectArrange(event, regs);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("l1Rule", "snake");
        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 4, rule);

        assertEquals("snake", result.get("l1Rule"));
        assertEquals("snake", result.get("groupingMode"));
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(6, stats.get("totalAthletes"));
        // 蛇形模式：heats = ceil(6/4)=2（班级均衡模式会因最大单班 3 人抬到 3）
        assertEquals(2, stats.get("totalHeats"));

        // 自检必须有效：蛇形模式有意跳过「同组不同班」校验，不应报违规
        @SuppressWarnings("unchecked")
        Map<String, Object> selfCheck = (Map<String, Object>) result.get("selfCheck");
        assertEquals(Boolean.TRUE, selfCheck.get("valid"));
        assertTrue(((List<?>) selfCheck.get("violations")).isEmpty());

        // 每人恰好一次
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
        Set<Long> seen = new HashSet<>();
        int placed = 0;
        for (Map<String, Object> heat : heats) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lanes = (List<Map<String, Object>>) heat.get("lanes");
            for (Map<String, Object> lane : lanes) {
                if (lane.get("athleteId") != null) {
                    placed++;
                    assertTrue(seen.add((Long) lane.get("athleteId")), "同一运动员被重复分配");
                }
            }
        }
        assertEquals(6, placed);
        assertEquals(6, seen.size());
    }

    /** 缺省（ruleConfig=null）→ 班级均衡模式，结果如实标注 groupingMode=class。 */
    @Test
    void arrange_defaultMode_reportsClassGrouping() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        stubDirectArrange(event, regs);

        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 4, null);
        assertEquals("class", result.get("groupingMode"));
    }

    /** L1「自定义规则」款型目录：至少含 class/snake 两款且字段齐全（前端据此渲染「选择哪一款」）。 */
    @Test
    void l1RuleCatalog_containsVariants() {
        List<Map<String, String>> cat = L1Rule.catalog();
        assertTrue(cat.size() >= 2);
        assertTrue(cat.stream().anyMatch(m -> "class".equals(m.get("id"))));
        assertTrue(cat.stream().anyMatch(m -> "snake".equals(m.get("id"))));
        for (Map<String, String> m : cat) {
            assertNotNull(m.get("label"));
            assertNotNull(m.get("description"));
        }
        // 未知款型回退默认；大小写不敏感；含种子蛇形
        assertEquals(L1Rule.CLASS, L1Rule.of("不存在"));
        assertEquals(L1Rule.SNAKE, L1Rule.of("SNAKE"));
        assertEquals(L1Rule.SNAKE_SEEDED, L1Rule.of("snakeSeed"));
        assertTrue(L1Rule.SNAKE.isSnake());
        assertTrue(L1Rule.SNAKE_SEEDED.isSnake());
        assertFalse(L1Rule.CLASS.isSnake());
    }

    /** 向后兼容：旧布尔 snakeGrouping=true 仍解析为 snake 款型。 */
    @Test
    void arrange_legacySnakeFlagStillWorks() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        stubDirectArrange(event, regs);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("snakeGrouping", true);
        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 4, rule);
        assertEquals("snake", result.get("l1Rule"));
    }

    /**
     * L1 规则注入（形态一）**改写落位**：对某组命中规则惩罚 → 蛇形改投无惩罚的组（能力允许时完全避开）。
     * 用一条人工锁定项把组数撑到 2，从而具备可选的落位空间。
     */
    @Test
    void arrange_ruleInjection_steersSnakeHeatChoice() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(3).build();
        List<Registration> regs = new ArrayList<>();
        regs.add(reg(athlete(1L, "A1", 1L, "高一1班")));
        regs.add(reg(athlete(2L, "A2", 1L, "高一1班")));
        stubDirectArrange(event, regs);
        // 人工锁定项（第 2 组）→ 组数 = max(ceil(3/3)=1, 锁定最大组号 2) = 2
        Arrangement locked = Arrangement.builder().id(99L).event(event)
                .athlete(athlete(3L, "A3", 1L, "高一1班"))
                .heat(2).lane(1).isManual(true).build();
        when(arrangementRepository.findManualByEventRoundGradeGender(100L, "final", "高一年级", "男"))
                .thenReturn(List.of(locked));

        // 对「第 1 组」注入 soft 惩罚 → 蛇形目标为第 1 组的 A1 应改投第 2 组
        when(ruleInjectionService.assess(any())).thenAnswer(inv -> {
            RuleContext c = inv.getArgument(0);
            Object heat = c.get("heat");
            return (heat != null && ((Number) heat).intValue() == 1)
                    ? RuleOutcome.empty().addSoft(100) : RuleOutcome.empty();
        });

        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 3,
                Map.of("l1Rule", "snake"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ri = (Map<String, Object>) result.get("ruleInjection");
        assertNotNull(ri);
        assertEquals(0L, ((Number) ri.get("soft")).longValue(),
                "规则注入应把运动员改投无惩罚的组（否则会命中第 1 组的 soft=100）");
        assertEquals(0L, ((Number) ri.get("hard")).longValue());
    }

    /** L1「种子蛇形」款型：按成绩/种子名次排序后 S 形分散，组间种子强度均衡、组内道次唯一。 */
    @Test
    void arrange_snakeSeeded_ordersBySeedRank() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(2).build();
        List<Registration> regs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) regs.add(reg(athlete((long) i, "A" + i, 1L, "高一1班")));
        stubDirectArrange(event, regs);
        // 成绩：id3=11.0(最快) id1=12.0 id2=13.0 id4=14.0 → 种子序 A3,A1,A2,A4
        when(resultRepository.findValidByEventId(100L)).thenReturn(List.of(
                Result.builder().event(event).athlete(regs.get(0).getAthlete()).timeSeconds(12.0).status("valid").build(),
                Result.builder().event(event).athlete(regs.get(1).getAthlete()).timeSeconds(13.0).status("valid").build(),
                Result.builder().event(event).athlete(regs.get(2).getAthlete()).timeSeconds(11.0).status("valid").build(),
                Result.builder().event(event).athlete(regs.get(3).getAthlete()).timeSeconds(14.0).status("valid").build()));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("l1Rule", "snakeSeed");
        Map<String, Object> result = arrangementService.arrange(100L, "高一年级", "男", 2, rule);

        assertEquals("snakeSeed", result.get("l1Rule"));
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(4, stats.get("totalAthletes"));
        assertEquals(2, stats.get("totalHeats"));   // ceil(4/2)

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heats = (List<Map<String, Object>>) result.get("heats");
        Set<Long> seen = new HashSet<>();
        for (Map<String, Object> heat : heats) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lanes = (List<Map<String, Object>>) heat.get("lanes");
            Set<Integer> laneNos = new HashSet<>();
            for (Map<String, Object> lane : lanes) {
                if (lane.get("athleteId") != null) {
                    seen.add((Long) lane.get("athleteId"));
                    assertTrue(laneNos.add(((Number) lane.get("lane")).intValue()), "组内道次重复");
                }
            }
        }
        assertEquals(4, seen.size());
    }

    @Test
    void arrange_emptyRegistrations_throws() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4).build();
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一", "高一", "男"))
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

        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一年级", "高一", "男")).thenReturn(regs);
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
        when(registrationRepository.findApprovedByEventGradeGender(100L, "高一年级", "高一", "男"))
                .thenReturn(regs);
        when(arrangementRepository.findMaxVersionByEventId(100L)).thenReturn(null);
        when(arrangementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = arrangementService.generatePreliminary(100L, "高一年级", "男");

        assertEquals("preliminary", result.get("round"));
        Map<String, Object> stats = (Map<String, Object>) result.get("statistics");
        assertEquals(5, stats.get("totalAthletes"));
        // U12/B18：重新编排只清「非人工锁定」行，人工锁定项必须保留
        verify(arrangementRepository)
                .deleteNonManualByEventRoundGradeGender(100L, "preliminary", "高一年级", "男");
        verify(arrangementRepository)
                .findManualByEventRoundGradeGender(100L, "preliminary", "高一年级", "男");
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
        // B01/U01：二次编排要把决赛作为独立赛程条目补进赛程表，需有预赛条目作为排槽基准
        EventSchedule prelimRow = EventSchedule.builder()
                .id(9L).event(event).day(1).scheduleDate("2026-09-20").grade("高一年级")
                .timeSlot("上午").startTime("08:00").endTime("08:10").venue("田径场")
                .round(ArrangementService.ROUND_PRELIM).build();
        when(eventScheduleRepository.findByEventIdAndGrade(100L, "高一年级"))
                .thenReturn(List.of(prelimRow));
        when(eventScheduleRepository.save(any(EventSchedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(systemService.getMeetSchedule()).thenReturn(new LinkedHashMap<>());

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

        // U12/B18 + P0：决赛道次重建时只删非锁定行、并先取出锁定行用于占位
        verify(arrangementRepository)
                .deleteNonManualByEventRoundGradeGender(100L, "final", "高一年级", "男");
        verify(arrangementRepository)
                .findManualByEventRoundGradeGender(100L, "final", "高一年级", "男");

        // B01/U01：决赛赛程条目必须落库（round=final），否则赛程表与道次表脱节
        ArgumentCaptor<EventSchedule> cap = ArgumentCaptor.forClass(EventSchedule.class);
        verify(eventScheduleRepository).save(cap.capture());
        assertEquals(ArrangementService.ROUND_FINAL, cap.getValue().getRound());
        assertEquals("2026-09-20", cap.getValue().getScheduleDate());
        // 排槽基准 = 预赛结束 08:10 + 默认最小间隔 45 分钟 → 08:55
        assertEquals("08:55", cap.getValue().getStartTime());
    }

    /**
     * B01/U01 回归：二次编排必须幂等——重复调用决赛时间不得漂移。
     *
     * <p>旧实现先取 rows 快照、再删除本性别旧决赛条目，但算顺延基准时仍遍历整份
     * 快照（含刚被删掉的那些行，其 endTime 依旧可读），于是本性别上一轮的决赛结束
     * 时刻会把自己的新起点一再往后顶：08:55 → 09:50 → …，赛程表越滚越晚。</p>
     */
    @Test
    void computeQualifiers_isIdempotent_finalStartDoesNotDrift() {
        Event event = Event.builder().id(100L).name("100m").defaultLanes(4)
                .needHeats(true).advanceCount(3).build();

        List<Arrangement> prelims = new ArrayList<>();
        double[] times = {12.5, 12.8, 13.0, 13.2, 13.5};
        for (int i = 0; i < 5; i++) {
            Arrangement arr = Arrangement.builder()
                    .id((long) i + 1).event(event)
                    .athlete(athlete((long) i + 1, "A" + (i + 1), i % 2 == 1 ? 2L : 1L,
                            i % 2 == 1 ? "高一2班" : "高一1班"))
                    .grade("高一年级").gender("男")
                    .heat(i / 2 + 1).lane((i % 2) + 1).round("preliminary")
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
        when(systemService.getMeetSchedule()).thenReturn(new LinkedHashMap<>());

        // 有状态的赛程仓储：让 save/delete/查询 真正互相可见，才能暴露「删了但仍在快照里」
        List<EventSchedule> store = new ArrayList<>();
        store.add(EventSchedule.builder()
                .id(9L).event(event).day(1).scheduleDate("2026-09-20").grade("高一年级")
                .timeSlot("上午").startTime("08:00").endTime("08:10").venue("田径场")
                .round(ArrangementService.ROUND_PRELIM).build());
        long[] seq = {100L};
        when(eventScheduleRepository.findByEventIdAndGrade(100L, "高一年级"))
                .thenAnswer(inv -> new ArrayList<>(store));
        when(eventScheduleRepository.save(any(EventSchedule.class))).thenAnswer(inv -> {
            EventSchedule s = inv.getArgument(0);
            if (s.getId() == null) s.setId(++seq[0]);
            store.removeIf(x -> x.getId() != null && x.getId().equals(s.getId()));
            store.add(s);
            return s;
        });
        doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(eventScheduleRepository).delete(any(EventSchedule.class));

        arrangementService.computeQualifiers(100L, "高一年级", "男", null);
        String first = finalStartTime(store);
        arrangementService.computeQualifiers(100L, "高一年级", "男", null);
        String second = finalStartTime(store);

        assertEquals("08:55", first, "首次决赛应排在预赛 08:10 + 45 分间隔 = 08:55");
        assertEquals(first, second, "重复二次编排必须幂等，决赛开始时间不得漂移");
        // 赛程表里该 项目×年级 只应有一条决赛条目（另一性别未编排）
        assertEquals(1, store.stream()
                .filter(s -> ArrangementService.ROUND_FINAL.equals(s.getRound())).count());
    }

    private String finalStartTime(List<EventSchedule> store) {
        return store.stream()
                .filter(s -> ArrangementService.ROUND_FINAL.equals(s.getRound()))
                .map(EventSchedule::getStartTime)
                .findFirst().orElse(null);
    }

    @Test
    void getArrangement_emptyReturnsZero() {        when(eventRepository.findById(100L)).thenReturn(Optional.of(Event.builder().id(100L).name("x").build()));
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
