package com.sports.service.arrange;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 兼项运动员自动统计单测。
 *
 * <p>覆盖「自动统计」最容易写错的三点：只算 approved 报名、只报 1 项的不算兼项、
 * 兼项数分布桶的边界（2/3/4/≥5）与名单排序。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventCooccurrenceServiceTest {

    @Mock private RegistrationRepository registrationRepository;
    @Mock private EventRepository eventRepository;
    @Mock private AthleteRepository athleteRepository;

    @InjectMocks private EventCooccurrenceService service;

    private Event e100, e200, eLongJump, eShotPut, eRelay;

    /** 1 号兼 3 项，2 号兼 4 项，3 号只报 1 项（不算兼项），4 号兼 5 项 */
    @BeforeEach
    void setUp() {
        e100 = Event.builder().id(1L).name("100米").code("T1").category("径赛").build();
        e200 = Event.builder().id(2L).name("200米").code("T2").category("径赛").build();
        eLongJump = Event.builder().id(3L).name("跳远").code("F1").category("田赛").build();
        eShotPut = Event.builder().id(4L).name("铅球").code("F2").category("田赛").build();
        eRelay = Event.builder().id(5L).name("4×100接力").code("T3").category("径赛").build();
        List<Event> events = List.of(e100, e200, eLongJump, eShotPut, eRelay);

        // 1 号：100米 + 200米 + 跳远（兼 3 项 → 3 对）
        // 2 号：100米 + 200米 + 跳远 + 铅球（兼 4 项 → 6 对）
        // 3 号：只报 100米（不兼项）
        // 4 号：全部 5 项（兼 5 项 → 10 对）
        List<Registration> regs = new ArrayList<>(List.of(
                reg(1L, 1L), reg(1L, 2L), reg(1L, 3L),
                reg(2L, 1L), reg(2L, 2L), reg(2L, 3L), reg(2L, 4L),
                reg(3L, 1L),
                reg(4L, 1L), reg(4L, 2L), reg(4L, 4L), reg(4L, 5L), reg(4L, 3L)));

        when(registrationRepository.findByStatus("approved")).thenReturn(regs);
        when(eventRepository.findAll()).thenReturn(events);
        when(athleteRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.of(athlete(id));
        });
    }

    private Athlete athlete(Long id) {
        return Athlete.builder().id(id).name("运动员" + id).number("N" + id)
                .gender("男").grade("高一").classNameInput("高一(3)班").build();
    }

    private Registration reg(Long athleteId, Long eventId) {
        return Registration.builder().id(athleteId * 100 + eventId)
                .athlete(athlete(athleteId))
                .event(eventOf(eventId))
                .status("approved").build();
    }

    private Event eventOf(Long id) {
        return switch (id.intValue()) {
            case 1 -> e100;
            case 2 -> e200;
            case 3 -> eLongJump;
            case 4 -> eShotPut;
            default -> eRelay;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @Test
    @DisplayName("兼项运动员自动统计：人数、占比、最大兼项数正确，未审核报名不计入")
    void countsMultiEventAthletes() {
        Map<String, Object> out = service.analyze();

        assertEquals(13, out.get("totalApproved"), "13 条 approved 报名（待审核那条不计）");
        assertEquals(4, out.get("athleteCount"), "4 名运动员有报名");
        assertEquals(3, out.get("multiEventAthletes"), "1/2/4 号兼项，3 号只报 1 项");
        assertEquals(5, out.get("maxEvents"), "4 号兼 5 项");
        assertEquals(0.75, (Double) out.get("multiRatio"), "3/4");
    }

    @Test
    @DisplayName("兼项项数分布桶边界：2/3/4/≥5 各档人数正确，合计等于全部运动员")
    void distributionBuckets() {
        Map<String, Object> out = service.analyze();
        List<Map<String, Object>> dist = listOf(out.get("distribution"));

        assertEquals(4, dist.size(), "四档：2 项 / 3 项 / 4 项 / 5 项及以上");
        assertEquals("2 项", dist.get(0).get("label"));
        assertEquals(0, ((Number) dist.get(0).get("count")).intValue(), "无人只兼 2 项");
        assertEquals(1, ((Number) dist.get(1).get("count")).intValue(), "3 项：1 号");
        assertEquals(1, ((Number) dist.get(2).get("count")).intValue(), "4 项：2 号");
        assertEquals(1, ((Number) dist.get(3).get("count")).intValue(), "5 项及以上：4 号");

        int total = dist.stream().mapToInt(d -> ((Number) d.get("count")).intValue()).sum();
        assertEquals(3, total, "分布合计 = 兼项运动员数（只报 1 项的 3 号不入各档）");
    }

    @Test
    @DisplayName("兼项运动员名单：按兼项数降序、字段完整、只报 1 项的被排除")
    void athleteRosterSortedAndComplete() {
        Map<String, Object> out = service.analyze();
        List<Map<String, Object>> rows = listOf(out.get("athletes"));

        assertEquals(3, rows.size(), "只报 1 项的 3 号不进名单");
        List<Integer> counts = rows.stream()
                .map(r -> ((Number) r.get("eventCount")).intValue()).toList();
        assertEquals(List.of(5, 4, 3), counts, "兼项数降序：4 号(5) → 2 号(4) → 1 号(3)");
        assertEquals(List.of(10, 6, 3), rows.stream()
                .map(r -> ((Number) r.get("pairCount")).intValue()).toList(), "项目对 = C(n,2)");

        Map<String, Object> top = rows.get(0);
        assertEquals("运动员4", top.get("name"));
        assertEquals("N4", top.get("number"));
        assertEquals("男", top.get("gender"));
        assertEquals("高一", top.get("grade"));
        assertEquals("高一(3)班", top.get("className"), "班级回落到 classNameInput（无绑定 ClassInfo）");
        assertEquals(5, ((List<?>) top.get("eventNames")).size());
        assertEquals(5, ((String) top.get("eventNamesText")).split("、").length);
    }

    @Test
    @DisplayName("高频共现项目对：100米/200米 与 200米/跳远 共同报名人数正确")
    void pairCooccurrence() {
        Map<String, Object> out = service.analyze();
        List<Map<String, Object>> pairs = listOf(out.get("pairs"));

        // 原始 C(3,2)+C(4,2)+C(5,2)=19 对，去重后：{1,2}{1,3}{2,3} 与 {1,4}{2,4}{3,4}{1,5}{2,5}{3,5}{4,5} 共 10 对
        assertEquals(10, pairs.size(), "共现项目对去重后 10 对");

        Map<String, Long> byKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> p : pairs) {
            Map<String, Object> a = (Map<String, Object>) p.get("eventA");
            Map<String, Object> b = (Map<String, Object>) p.get("eventB");
            String key = a.get("name") + "+" + b.get("name");
            byKey.put(key, ((Number) p.get("commonAthletes")).longValue());
        }
        assertTrue(byKey.getOrDefault("100米+200米", 0L) >= 2L, "1/2/4 号都报了这两项");
        assertTrue(byKey.getOrDefault("100米+铅球", 0L) >= 1L, "2/4 号报了这两项");
    }

    @Test
    @DisplayName("limit 参数截断名单但不影响统计口径；0/负数表示不截断")
    void limitTruncatesRosterOnly() {
        Map<String, Object> full = service.analyze();
        Map<String, Object> limited = service.analyze(1);

        assertEquals(3, ((List<?>) full.get("athletes")).size());
        assertEquals(1, ((List<?>) limited.get("athletes")).size(), "只返回兼项最多的 1 人");
        assertEquals(full.get("multiEventAthletes"), limited.get("multiEventAthletes"),
                "截断只影响名单，统计口径不变");

        Map<String, Object> zero = service.analyze(0);
        assertEquals(3, ((List<?>) zero.get("athletes")).size());
    }

    @Test
    @DisplayName("空数据不炸：无报名时比例为 0、分布全 0、名单为空")
    void emptyDataSet() {
        when(registrationRepository.findByStatus("approved")).thenReturn(new ArrayList<>());
        when(eventRepository.findAll()).thenReturn(new ArrayList<>());

        Map<String, Object> out = service.analyze();

        assertEquals(0, out.get("totalApproved"));
        assertEquals(0, out.get("athleteCount"));
        assertEquals(0, out.get("multiEventAthletes"));
        assertEquals(0.0, (Double) out.get("multiRatio"));
        assertTrue(((List<?>) out.get("athletes")).isEmpty());
        List<Map<String, Object>> dist = listOf(out.get("distribution"));
        assertTrue(dist.stream().allMatch(d -> ((Number) d.get("count")).intValue() == 0));
    }
}
