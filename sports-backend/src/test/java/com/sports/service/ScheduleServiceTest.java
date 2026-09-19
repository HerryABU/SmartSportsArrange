package com.sports.service;

import com.sports.entity.Athlete;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.entity.Registration;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.VenueRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.repository.RegistrationRepository;
import com.sports.schedule.opt.ScheduleOptimizer;
import com.sports.schedule.verify.ScheduleVerifier;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 赛程编排测试（并发位模型）。
 *
 * <p>覆盖：田赛项目内并发折算轮次、并发位数并行/串行、自定义项目顺序、田赛分组同批并行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleServiceTest {

    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private ArrangementService arrangementService;
    @Mock private SystemService systemService;
    @Mock private VenueRepository venueRepository;
    @Mock private ConflictService conflictService;
    /**
     * 约束求解器 mock：本测试类验证的是**贪心兜底路径**（求解器返回空 → 完整走原有贪心），
     * 因此这里保持默认 stub（Optional 返回空）即可，不引入真实求解耗时。
     */
    @Mock private ScheduleOptimizer scheduleOptimizer;

    /**
     * 校验器 mock：本类验证的是<b>编排主流程</b>，校验逻辑本身由 ScheduleVerifierTest 独立覆盖
     * （那边有对抗性扫描用例）。这里给一个「无违规」的桩即可。
     */
    @Mock private ScheduleVerifier scheduleVerifier;

    @InjectMocks private ScheduleService scheduleService;

    /** 捕获落库的赛程行，供断言与 buildResult 查询 */
    private final List<EventSchedule> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        saved.clear();
        when(scheduleRepository.save(any(EventSchedule.class))).thenAnswer(inv -> {
            EventSchedule s = inv.getArgument(0);
            saved.add(s);
            return s;
        });
        when(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc())
                .thenAnswer(inv -> new ArrayList<>(saved));
        when(scheduleVerifier.verify(any(), any(), anyInt()))
                .thenReturn(new ScheduleVerifier.Result(true, 0, 0, List.of(), List.of(), null, null));
    }

    // ==================== 夹具 ====================

    /** 一天、一个长时段（08:00-18:00），便于观察并行/串行差异 */
    private Map<String, Object> cfg(int trackSlots, int fieldSlots) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("startDate", "2026-09-20");
        c.put("days", 1);
        c.put("gradeOrder", List.of("高一年级"));
        c.put("venues", List.of("田径场", "田赛A区", "田赛B区"));
        c.put("trackSlots", trackSlots);
        c.put("fieldSlots", fieldSlots);
        c.put("defaultDurationMinutes", 600);   // 不封顶，便于断言真实估算值
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

    private Event field(Long id, String name, int concurrency) {
        return Event.builder().id(id).name(name).code("F" + id)
                .track(false).laneCount(0).concurrency(concurrency).category("田赛")
                .genderLimit("男子组").gradeGroup("高一年级").isEnabled(true)
                .sortOrder(id.intValue()).build();
    }

    private void events(Event... list) {
        when(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(list));
    }

    private void regs(Long eventId, int n) {
        List<Registration> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Athlete a = Athlete.builder().id(eventId * 1000 + i)
                    .grade("高一年级").gender("M").build();
            list.add(Registration.builder().athlete(a).status("approved").build());
        }
        when(registrationRepository.findApprovedByEventId(eventId)).thenReturn(list);
    }

    // ==================== 用例 ====================

    /** 田赛项目内并发：20 人 / 并发 4 = 5 轮 × 3 分钟 = 15 分钟 */
    @Test
    void fieldDurationDividedByProjectConcurrency() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        Event f = field(11L, "跳远", 4);
        events(f);
        regs(11L, 20);

        scheduleService.autoSchedule(null);

        assertEquals(1, saved.size());
        assertEquals(15, saved.get(0).getDurationMinutes());
        assertEquals("08:00", saved.get(0).getStartTime());
    }

    /**
     * A6：游泳属特殊径赛（track=true），指定独立场地编码(SWIM)后建立独立并发池，
     * 与主径赛池（田径场）并行 —— 即「同排」，而非独占主径赛场地。
     */
    @Test
    void swimOnNewVenueRunsInParallelWithTrackPool() {
        Map<String, Object> c = cfg(2, 2);
        c.put("venues", List.of(
                Map.of("name", "田径场", "code", "TRACK"),
                Map.of("name", "田赛A区", "code", "FIELD_A"),
                Map.of("name", "田赛B区", "code", "FIELD_B")));
        when(systemService.getMeetSchedule()).thenReturn(c);

        Event t = track(1L, "100米");
        Event swim = Event.builder().id(2L).name("50米蛙泳").code("SWIM_M").track(true).laneCount(8)
                .category("径赛").genderLimit("男子组").gradeGroup("高一年级").isEnabled(true)
                .sortOrder(2).defaultVenueCode("SWIM").build();
        events(t, swim);
        regs(1L, 8);
        regs(2L, 8);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        Set<String> venues = saved.stream().map(EventSchedule::getVenue).collect(Collectors.toSet());
        assertTrue(venues.contains("田径场"), "普通径赛应在主场地 田径场");
        assertTrue(venues.contains("SWIM"), "游泳应在独立场地（编码即名称）与主池并行");
        // 不同池互不影响，均从 08:00 起 → 同排
        assertEquals(saved.get(0).getStartTime(), saved.get(1).getStartTime());
    }

    /** 田赛并发位数 2：两个田赛项目同时段开赛（并行） */
    @Test
    void fieldSlotsAllowParallelProjects() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regs(11L, 2);
        regs(12L, 2);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals("08:00", saved.get(0).getStartTime());
        assertEquals("08:00", saved.get(1).getStartTime(), "并发位=2 时两个田赛应同时开赛");
    }

    /** 径赛并发位数 1（串行）：第二个项目排在第一个之后 */
    @Test
    void trackSlotsOneKeepsSerial() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        events(track(21L, "100米"), track(22L, "200米"));
        regs(21L, 8);
        regs(22L, 8);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals("08:00", saved.get(0).getStartTime());
        // 8 人 / 8 道 = 1 组 × 6 分钟 → 不足最短 10 分钟，取 10 分钟
        assertEquals(10, saved.get(0).getDurationMinutes());
        // B05/U07 起项目间隔取 max(defaultInterval, minInterval)：
        // 本夹具 defaultIntervalMinutes=0，而 minIntervalMinutes 缺省为 5（下限，防项目紧贴），
        // 故实际间隔 5 分钟 → 08:00 + 10 + 5 = 08:15。
        // 旧断言写 08:10（隐式假设间隔 0），是 U07 引入最小间隔下限之前的契约，早已不成立。
        assertEquals("08:15", saved.get(1).getStartTime(),
                "串行位=1 时第二个径赛应在「前项用时 + 最小间隔」之后顺延");
    }

    /** 自定义项目顺序：eventOrder=[2,1] 时先排 2 号项目 */
    @Test
    void customEventOrderWins() {
        Map<String, Object> c = cfg(1, 1);
        c.put("eventOrder", List.of(2L, 1L));
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(1L, "跳远", 1), field(2L, "铅球", 1));
        regs(1L, 1);
        regs(2L, 1);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals(2L, saved.get(0).getEvent().getId(), "应按 eventOrder 先排 2 号项目");
        assertEquals(1L, saved.get(1).getEvent().getId());
    }

    /** 田赛分组：同组项目安排在同一时段并行 */
    @Test
    void fieldGroupScheduledInSameSlot() {
        Map<String, Object> c = cfg(1, 2);
        c.put("fieldGroups", List.of(Map.of("name", "田赛A组", "eventIds", List.of(11L, 12L))));
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regs(11L, 3);
        regs(12L, 3);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        assertEquals("08:00", saved.get(0).getStartTime());
        assertEquals("08:00", saved.get(1).getStartTime(), "同组田赛应在同一时段并行");
        assertEquals(saved.get(0).getTimeSlot(), saved.get(1).getTimeSlot());
    }

    /** 项目自带的「并行捆绑组」字母：同字母的田赛安排在同一时段并行 */
    @Test
    void bundleGroupSchedulesInSameSlot() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        Event a = field(11L, "跳远", 1);
        a.setBundleGroup("A");
        Event b = field(12L, "铅球", 1);
        b.setBundleGroup("A");
        Event c = field(13L, "实心球", 1);   // 未填捆绑 → 由算法自动安排
        events(a, b, c);
        regs(11L, 2);
        regs(12L, 2);
        regs(13L, 2);

        scheduleService.autoSchedule(null);

        assertEquals(3, saved.size());
        EventSchedule sa = find(11L);
        EventSchedule sb = find(12L);
        assertEquals(sa.getStartTime(), sb.getStartTime(), "同字母捆绑组应在同一时间开赛");
        assertEquals(sa.getTimeSlot(), sb.getTimeSlot(), "同字母捆绑组应在同一时段");
    }

    /** 场地支持 [{name, code}] 对象数组（新格式），编排应使用其中的场地名称 */
    @Test
    void venueObjectArraySupported() {
        Map<String, Object> c = cfg(1, 2);
        c.put("venues", List.of(
                Map.of("name", "主田径场", "code", "TRACK"),
                Map.of("name", "田赛1区", "code", "F1"),
                Map.of("name", "田赛2区", "code", "F2")));
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regs(11L, 2);
        regs(12L, 2);

        scheduleService.autoSchedule(null);

        List<String> used = saved.stream().map(EventSchedule::getVenue).toList();
        assertTrue(used.contains("田赛1区"), "应使用对象数组里的场地名称，实际: " + used);
        assertTrue(used.contains("田赛2区"), "应使用对象数组里的场地名称，实际: " + used);
    }

    // ==================== B09/U09：手动保存不丢轮次 ====================

    /** 入参未带 round（旧前端）时，按「项目×年级×开始时刻×场地」沿用既有行的轮次 */
    @Test
    void manualSaveKeepsExistingRoundWhenPayloadOmitsIt() {
        Event e = track(31L, "100米");
        when(eventRepository.findById(31L)).thenReturn(Optional.of(e));
        // 保存前的既有赛程行：预赛
        EventSchedule old = EventSchedule.builder()
                .id(1L).event(e).day(1).grade("高一年级")
                .startTime("08:00").endTime("08:10").venue("田径场")
                .round(ArrangementService.ROUND_PRELIM).build();
        when(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc()).thenReturn(List.of(old));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", 31L);
        item.put("day", 1);
        item.put("grade", "高一年级");
        item.put("startTime", "08:00");
        item.put("venue", "田径场");
        item.put("durationMinutes", 12);   // 人工把时长由 10 改为 12

        scheduleService.save(List.of(item));

        assertEquals(1, saved.size());
        assertEquals(ArrangementService.ROUND_PRELIM, saved.get(0).getRound(),
                "手动保存不得把既有「预赛」轮次清空");
        assertEquals(12, saved.get(0).getDurationMinutes(), "人工调整的时长应生效");
    }

    /** 入参显式带 round（新前端回传）时以入参为准 */
    @Test
    void manualSaveUsesPayloadRoundWhenProvided() {
        Event e = track(32L, "100米");
        when(eventRepository.findById(32L)).thenReturn(Optional.of(e));
        when(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc()).thenReturn(List.of());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", 32L);
        item.put("day", 1);
        item.put("grade", "高一年级");
        item.put("startTime", "09:00");
        item.put("venue", "田径场");
        item.put("round", ArrangementService.ROUND_FINAL);

        scheduleService.save(List.of(item));

        assertEquals(1, saved.size());
        assertEquals(ArrangementService.ROUND_FINAL, saved.get(0).getRound());
    }

    /** 既无入参也无既有行时，按 event.needHeats 推断轮次（需预赛→preliminary） */
    @Test
    void manualSaveInfersRoundFromNeedHeats() {
        Event e = Event.builder().id(33L).name("100米").code("T33").track(true)
                .laneCount(8).needHeats(true).category("径赛").genderLimit("男子组")
                .isEnabled(true).sortOrder(33).build();
        when(eventRepository.findById(33L)).thenReturn(Optional.of(e));
        when(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc()).thenReturn(List.of());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", 33L);
        item.put("day", 1);
        item.put("grade", "高一年级");
        item.put("startTime", "10:00");
        item.put("venue", "田径场");

        scheduleService.save(List.of(item));

        assertEquals(1, saved.size());
        assertEquals(ArrangementService.ROUND_PRELIM, saved.get(0).getRound(),
                "needHeats 项目在无任何线索时应推断为预赛");
    }

    private EventSchedule find(Long eventId) {
        return saved.stream()
                .filter(s -> s.getEvent() != null && eventId.equals(s.getEvent().getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 eventId=" + eventId + " 的赛程行"));
    }

    // ==================== U27/B24：算法正确性与可靠性 ====================

    /**
     * 容量充足时必须给足真实估算用时。
     *
     * <p>旧实现把 defaultDurationMinutes 当上限一刀切：本例 66 人 × 3 分钟 = 198 分钟会被砍成 30 分钟，
     * 排出来的表看着整齐、现场根本跑不完。这里故意把 defaultDurationMinutes 设成 30 来守住这条。</p>
     */
    @Test
    void capacityIsEnoughKeepsRealEstimate() {
        Map<String, Object> c = cfg(1, 2);
        c.put("defaultDurationMinutes", 30);
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(11L, "跳远", 1));
        regs(11L, 66);

        scheduleService.autoSchedule(null);

        assertEquals(1, saved.size());
        assertEquals(198, saved.get(0).getDurationMinutes(),
                "容量充足（08:00-18:00 共 600 分钟）时必须给足真实估算用时");
        assertEquals("08:00", saved.get(0).getStartTime());
        assertEquals("11:18", saved.get(0).getEndTime());
    }

    /**
     * 项目时间是编排的原子单位：容量紧张时**整块**落地（行内 duration 与起止时刻自洽）、
     * 同口径单元等比缩放一致、不丢项目、同场地同时段不重叠。
     */
    @Test
    void wholeBlockPlacementUnderTightCapacity() {
        Map<String, Object> c = cfg(1, 1);   // 并发位 1，且把时段压到 240 分钟 → 120+120+间隔 > 240
        c.put("dayConfigs", List.of(Map.of("day", 1, "date", "2026-09-20", "slots",
                List.of(Map.of("key", "AM", "name", "上午", "start", "08:00", "end", "12:00")))));
        when(systemService.getMeetSchedule()).thenReturn(c);
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regs(11L, 40);
        regs(12L, 40);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size(), "整块装箱后两个项目都必须排下，不得丢项目");
        EventSchedule a = find(11L);
        EventSchedule b = find(12L);
        assertEquals(a.getDurationMinutes(), b.getDurationMinutes(),
                "同口径（各 120 分钟）的单元应得到一致的缩放时长");
        assertTrue(a.getDurationMinutes() > 0 && a.getDurationMinutes() <= 120,
                "压缩后时长应在 (0, 原始估算] 区间内，实际 " + a.getDurationMinutes());
        for (EventSchedule s : saved) {
            assertEquals(s.getDurationMinutes(), toMin(s.getEndTime()) - toMin(s.getStartTime()),
                    "项目必须整块落地：行内时长与起止时刻必须自洽");
        }
        assertNoSameVenueSlotOverlap();
    }

    /** 兼项冲突规避：同一批运动员兼报两项时，应排到间隔 ≥ 冲突缓冲的位置（而不是照旧叠在一起） */
    @Test
    void avoidsAthleteConflictWhenPossible() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        events(field(11L, "跳远", 1), field(12L, "铅球", 1));
        regsShared(2, 11L, 12L);

        scheduleService.autoSchedule(null);

        assertEquals(2, saved.size());
        EventSchedule a = find(11L);
        EventSchedule b = find(12L);
        assertEquals(a.getDay(), b.getDay(), "本用例只有一个时段，两天内比较无意义");
        int gap = Math.max(toMin(b.getStartTime()) - toMin(a.getEndTime()),
                toMin(a.getStartTime()) - toMin(b.getEndTime()));
        assertTrue(gap >= ConflictService.CONFLICT_BUFFER_MIN,
                "兼报两项的运动员必须被排到间隔 ≥ " + ConflictService.CONFLICT_BUFFER_MIN
                        + " 分钟的位置，实际间隔 " + gap + " 分钟");
    }

    /** 同输入两次编排必须完全一致：算法必须可复现，不依赖随机数或当前时间 */
    @Test
    void deterministicForSameInput() {
        when(systemService.getMeetSchedule()).thenReturn(cfg(1, 2));
        events(field(11L, "跳远", 1), field(12L, "铅球", 1), track(21L, "100米"));
        regs(11L, 20);
        regs(12L, 20);
        regs(21L, 8);

        scheduleService.autoSchedule(null);
        List<String> first = signature();
        saved.clear();
        scheduleService.autoSchedule(null);

        assertEquals(first, signature(), "同一输入两次编排结果必须完全一致");
        assertFalse(first.isEmpty(), "签名不应为空（确保确实排出了赛程）");
    }

    /** 落库赛程的规范化签名，用于确定性断言 */
    private List<String> signature() {
        return saved.stream()
                .map(s -> s.getEvent().getId() + "|" + s.getDay() + "|" + s.getStartTime()
                        + "|" + s.getEndTime() + "|" + s.getVenue() + "|" + s.getDurationMinutes())
                .sorted()
                .collect(Collectors.toList());
    }

    /** 同一批运动员同时报名多个项目（用于兼项冲突用例） */
    private void regsShared(int n, Long... eventIds) {
        List<Registration> shared = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            shared.add(Registration.builder()
                    .athlete(Athlete.builder().id(9000L + i).grade("高一年级").gender("M").build())
                    .status("approved").build());
        }
        for (Long id : eventIds) {
            when(registrationRepository.findApprovedByEventId(id)).thenReturn(new ArrayList<>(shared));
        }
    }

    /** 同一场地、同一天、同一时段内，任意两行不得时间重叠（容量约束的硬不变量） */
    private void assertNoSameVenueSlotOverlap() {
        for (int i = 0; i < saved.size(); i++) {
            for (int j = i + 1; j < saved.size(); j++) {
                EventSchedule a = saved.get(i);
                EventSchedule b = saved.get(j);
                if (!Objects.equals(a.getDay(), b.getDay())) continue;
                if (!Objects.equals(a.getVenue(), b.getVenue())) continue;
                if (!Objects.equals(a.getTimeSlot(), b.getTimeSlot())) continue;
                int as = toMin(a.getStartTime());
                int ae = toMin(a.getEndTime());
                int bs = toMin(b.getStartTime());
                int be = toMin(b.getEndTime());
                assertTrue(ae <= bs || be <= as, "同一场地同一时段内不得重叠："
                        + a.getEvent().getName() + " 与 " + b.getEvent().getName());
            }
        }
    }

    private static int toMin(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }
}
