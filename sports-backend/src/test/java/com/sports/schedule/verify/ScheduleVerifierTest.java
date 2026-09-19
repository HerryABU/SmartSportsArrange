package com.sports.schedule.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验器（内置裁判）的对抗性验证。
 *
 * <p>校验器本身也需要被验证，而且验证方式必须比它更「挑剔」——因为一个漏报的校验器比没有校验器
 * 更危险：它会给人虚假的安全感。因此这里不只是「跑一遍看有没有报错」，而是：</p>
 * <ol>
 *   <li><b>防误报</b>：一个干净的赛程必须报 0 条违规（不能草木皆兵）；</li>
 *   <li><b>防漏报</b>：逐类注入一种错误，断言<b>恰好</b>抓到对应类型；</li>
 *   <li><b>证明覆盖</b>：断言实际比对对数 = 人工算出的对数（「没检查却报绿」是最大风险）；</li>
 *   <li><b>无盲区扫描</b>：把每一对项目轮流改成互相重叠，断言<b>每一对</b>都能被抓到——
 *       等于用「找茬」反证校验逻辑没有死角。</li>
 * </ol>
 */
@DisplayName("赛程独立校验器")
class ScheduleVerifierTest {

    private final ScheduleVerifier verifier = new ScheduleVerifier();

    private static final String VENUE = "田径场";

    // ==================== ① 防误报：干净赛程不得报错 ====================

    @Test
    @DisplayName("干净的赛程：0 条违规、hardOk=true")
    void cleanScheduleReportsNothing() {
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子100米", "高一", 1, 480, 510, null, Set.of(1L, 2L)),
                row(2L, "男子跳远", "高一", 1, 515, 555, null, Set.of(3L, 4L)),
                row(3L, "女子铅球", "高一", 1, 560, 600, null, Set.of(1L, 5L)));

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertTrue(r.hardOk(), "干净赛程不应有阻塞级违规，实际：" + r.violations());
        assertEquals(0, r.blockerCount());
        assertEquals(0, r.warningCount());
        assertTrue(r.unassigned().isEmpty());
    }

    // ==================== ② 防漏报：逐类注入 ====================

    @Test
    @DisplayName("同场地时间重叠会被抓到（阻塞级）")
    void detectsVenueOverlap() {
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子100米", "高一", 1, 480, 510, null, Set.of()),
                row(2L, "男子跳远", "高一", 1, 500, 540, null, Set.of()));   // 与上一项重叠 10 分钟

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertEquals(1, count(r, ScheduleViolation.VENUE_OVERLAP));
        assertFalse(r.hardOk());
    }

    @Test
    @DisplayName("兼项赶不上会被抓到（含 15 分钟缓冲）")
    void detectsAthleteClash() {
        // 两个项目间隔只有 10 分钟（< 15 分钟缓冲），该运动员赶不上
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子100米", "高一", 1, 480, 510, null, Set.of(7L)),
                row(2L, "男子跳远", "高一", 1, 520, 560, null, Set.of(7L)));

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertEquals(1, count(r, ScheduleViolation.ATHLETE_CLASH));
        assertFalse(r.hardOk());
    }

    @Test
    @DisplayName("兼项间隔刚好 15 分钟时不算冲突（边界不误报）")
    void athleteClashBoundaryIsRespected() {
        // 15:00 结束、15:15 开始 → 间隔正好等于缓冲，应视为赶得上
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子100米", "高一", 1, 480, 510, null, Set.of(7L)),
                row(2L, "男子跳远", "高一", 1, 525, 560, null, Set.of(7L)));

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertEquals(0, count(r, ScheduleViolation.ATHLETE_CLASH),
                "间隔正好等于缓冲值时不应判为冲突：" + r.violations());
    }

    @Test
    @DisplayName("被静默丢弃的项目会被抓到（阻塞级）")
    void detectsNotAssigned() {
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子100米", "高一", 1, 480, 510, null, Set.of()));
        List<ScheduleVerifier.Expected> expected = List.of(
                new ScheduleVerifier.Expected(ScheduleVerifier.keyOf(1L, "高一"), "男子100米", "高一", 60),
                new ScheduleVerifier.Expected(ScheduleVerifier.keyOf(2L, "男子跳远"), "男子跳远", "高一", 200));

        ScheduleVerifier.Result r = verifier.verify(rows, expected, 840);

        assertEquals(1, count(r, ScheduleViolation.NOT_ASSIGNED));
        assertEquals(1, r.unassigned().size());
        assertFalse(r.hardOk(), "「没排入」必须视为阻塞级——排错看得见，漏排要到比赛当天才发现");
    }

    @Test
    @DisplayName("同组项目未同时开赛会被抓到（告警级，不阻塞）")
    void detectsGroupNotSimultaneous() {
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子铅球", "高一", 1, 480, 540, "A组", Set.of()),
                row(2L, "男子铁饼", "高一", 1, 550, 610, "A组", Set.of()));

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertEquals(1, count(r, ScheduleViolation.GROUP_NOT_SIMULTANEOUS));
        assertTrue(r.hardOk(), "违反赛会惯例属告警级，不应把整份赛程判为不可用");
        assertEquals(1, r.warningCount());
    }

    @Test
    @DisplayName("结束时间不晚于开始时间会被抓到")
    void detectsTimeInconsistent() {
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子100米", "高一", 1, 510, 510, null, Set.of()));

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertEquals(1, count(r, ScheduleViolation.TIME_INCONSISTENT));
        assertFalse(r.hardOk());
    }

    @Test
    @DisplayName("时长被压到下限以下会被抓到（告警级）")
    void detectsDurationBelowFloor() {
        // 真实需要 200 分钟，实际只给 30 分钟（下限应为 70 分钟）
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "男子跳远", "高一", 1, 480, 510, null, Set.of()));
        List<ScheduleVerifier.Expected> expected = List.of(
                new ScheduleVerifier.Expected(ScheduleVerifier.keyOf(1L, "高一"), "男子跳远", "高一", 200));

        ScheduleVerifier.Result r = verifier.verify(rows, expected, 840);

        assertEquals(1, count(r, ScheduleViolation.DURATION_BELOW_FLOOR));
        assertTrue(r.hardOk());
    }

    // ==================== ③ 证明「真的检查了」而不是空转报绿 ====================

    @Test
    @DisplayName("审计计数等于人工计算的对数——证明校验没有空转")
    void auditCountersProveRealCoverage() {
        // 4 行，全在同一场地同一天：场地对 = C(4,2) = 6
        // 运动员 7 出现在 3 行：运动员对 = C(3,2) = 3
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "A", "高一", 1, 480, 510, null, Set.of(7L)),
                row(2L, "B", "高一", 1, 515, 545, null, Set.of(7L)),
                row(3L, "C", "高一", 1, 550, 580, null, Set.of(7L)),
                row(4L, "D", "高一", 1, 585, 615, null, Set.of(9L)));

        ScheduleVerifier.Result r = verifier.verify(rows, expectedOf(rows), 840);

        assertEquals(6, r.audit().comparedVenuePairs(), "场地同天两两比对次数应为 C(4,2)=6");
        assertEquals(3, r.audit().comparedAthletePairs(), "运动员 7 出现 3 次 → C(3,2)=3");
        assertFalse(r.audit().busiestAthletes().isEmpty(), "最忙运动员清单不应为空");
    }

    // ==================== ④ 无盲区扫描：把每一对轮流改成重叠，必须每对都被抓到 ====================

    @Test
    @DisplayName("无盲区扫描：任意一对项目被改成互相重叠，都必须被抓到")
    void noBlindSpotForVenueOverlap() {
        List<ScheduleVerifier.Row> base = List.of(
                row(1L, "A", "高一", 1, 480, 510, null, Set.of()),
                row(2L, "B", "高一", 1, 515, 545, null, Set.of()),
                row(3L, "C", "高一", 1, 550, 580, null, Set.of()),
                row(4L, "D", "高一", 1, 585, 615, null, Set.of()));

        int checked = 0;
        for (int i = 0; i < base.size(); i++) {
            for (int j = i + 1; j < base.size(); j++) {
                // 把第 j 行搬到与第 i 行完全重叠的位置
                ScheduleVerifier.Row a = base.get(i);
                ScheduleVerifier.Row b = base.get(j);
                ScheduleVerifier.Row moved = new ScheduleVerifier.Row(b.eventId(), b.eventName(), b.grade(),
                        a.day(), a.date(), a.slotName(), a.startMinute(), a.endMinute(),
                        b.venue(), b.groupKey(), b.athletes(), b.athleteNames());
                List<ScheduleVerifier.Row> mutated = new ArrayList<>(base);
                mutated.set(j, moved);

                ScheduleVerifier.Result r = verifier.verify(mutated, expectedOf(mutated), 840);

                assertTrue(count(r, ScheduleViolation.VENUE_OVERLAP) >= 1,
                        "把 " + b.eventName() + " 搬到与 " + a.eventName() + " 重叠后必须被抓到，实际："
                                + r.violations());
                checked++;
            }
        }
        assertEquals(6, checked, "4 行应有 6 对组合被逐一扫描");
    }

    @Test
    @DisplayName("无盲区扫描：任意一对兼项被改成赶不上，都必须被抓到")
    void noBlindSpotForAthleteClash() {
        long athlete = 7L;
        List<ScheduleVerifier.Row> base = List.of(
                row(1L, "A", "高一", 1, 480, 510, null, Set.of(athlete)),
                row(2L, "B", "高一", 1, 515, 545, null, Set.of(athlete)),
                row(3L, "C", "高一", 1, 550, 580, null, Set.of(athlete)));

        int checked = 0;
        for (int i = 0; i < base.size(); i++) {
            for (int j = i + 1; j < base.size(); j++) {
                ScheduleVerifier.Row a = base.get(i);
                ScheduleVerifier.Row b = base.get(j);
                // 把第 j 行搬到与第 i 行首尾相接（间隔 0 分钟 < 缓冲 15 分钟）
                ScheduleVerifier.Row moved = new ScheduleVerifier.Row(b.eventId(), b.eventName(), b.grade(),
                        a.day(), a.date(), a.slotName(), a.endMinute(), a.endMinute() + b.durationMinutes(),
                        b.venue(), b.groupKey(), b.athletes(), b.athleteNames());
                List<ScheduleVerifier.Row> mutated = new ArrayList<>(base);
                mutated.set(j, moved);

                ScheduleVerifier.Result r = verifier.verify(mutated, expectedOf(mutated), 840);

                assertTrue(count(r, ScheduleViolation.ATHLETE_CLASH) >= 1,
                        "把 " + b.eventName() + " 排到紧接 " + a.eventName() + " 之后必须被判为赶不上，实际："
                                + r.violations());
                checked++;
            }
        }
        assertEquals(3, checked, "3 行应有 3 对组合被逐一扫描");
    }

    // ==================== ⑤ 质量自评 ====================

    @Test
    @DisplayName("质量自评：压缩比、跨度、利用率都被算出来")
    void qualityIsQuantified() {
        List<ScheduleVerifier.Row> rows = List.of(
                row(1L, "A", "高一", 1, 480, 540, null, Set.of()),   // 60 分钟
                row(2L, "B", "高一", 1, 545, 605, null, Set.of()));  // 60 分钟
        List<ScheduleVerifier.Expected> expected = List.of(
                new ScheduleVerifier.Expected(ScheduleVerifier.keyOf(1L, "高一"), "A", "高一", 120),
                new ScheduleVerifier.Expected(ScheduleVerifier.keyOf(2L, "高一"), "B", "高一", 120));

        ScheduleVerifier.Result r = verifier.verify(rows, expected, 840);

        assertEquals(120, r.quality().totalMinutes());
        assertEquals(240, r.quality().rawMinutes());
        assertEquals(50.0, r.quality().compressionPercent(), 0.05);
        assertEquals(125, r.quality().spanMinutes(), "跨度 = 最晚结束 - 最早开始");
        assertTrue(r.quality().utilizationPercent() > 0);
    }

    // ==================== 夹具 ====================

    private static ScheduleVerifier.Row row(long eventId, String name, String grade, int day,
                                            int start, int end, String groupKey, Set<Long> athletes) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long id : athletes) names.put(id, "选手" + id);
        return new ScheduleVerifier.Row(eventId, name, grade, day, "2026-01-0" + day, "上午",
                start, end, VENUE, groupKey, new LinkedHashSet<>(athletes), names);
    }

    /** 用赛程行反推「编排表应有的单元」，使 cleanSchedule 用例不会被 NOT_ASSIGNED 干扰 */
    private static List<ScheduleVerifier.Expected> expectedOf(List<ScheduleVerifier.Row> rows) {
        List<ScheduleVerifier.Expected> list = new ArrayList<>();
        for (ScheduleVerifier.Row r : rows) {
            list.add(new ScheduleVerifier.Expected(r.key(), r.eventName(), r.grade(), r.durationMinutes()));
        }
        return list;
    }

    private static long count(ScheduleVerifier.Result r, String type) {
        return r.violations().stream().filter(v -> type.equals(v.getType())).count();
    }
}
