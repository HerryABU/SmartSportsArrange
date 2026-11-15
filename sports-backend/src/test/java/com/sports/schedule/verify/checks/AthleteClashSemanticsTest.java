package com.sports.schedule.verify.checks;

import com.sports.schedule.verify.ScheduleVerifier;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼项冲突语义回归：与 ConflictMath 主检测链保持同一口径——
 * ① 径赛 + 田赛同段 = 可顺延（跑完径赛直接去田赛），不判兼项冲突；
 * ② 跨天隔离（隔夜），不判兼项冲突。
 *
 * <p>这两条在自检器 {@code AthleteClashCheck} 与求解器 {@code ScheduleConstraintProvider}
 * 各有独立实现，曾因只用绝对分钟裸算而双双误报，故一并钉死。</p>
 */
class AthleteClashSemanticsTest {

    // ==================== 自检器 AthleteClashCheck ====================

    @Test
    @DisplayName("自检：径田同段重叠不算兼项冲突（跑完径赛直接去田赛）")
    void verify_trackAndFieldOverlapIsNotClash() {
        Set<Long> athlete = Set.of(7L);
        ScheduleVerifier.Row track = row(1L, "100米", true, 1, 480, 510, athlete);
        ScheduleVerifier.Row field = row(2L, "跳远", false, 1, 490, 560, athlete);

        assertFalse(AthleteClashCheck.athleteClash(track, field),
                "径赛+田赛同段重叠应判为可顺延，不是兼项冲突");
    }

    @Test
    @DisplayName("自检：径田身份互换（田在前、径在后）同样不算冲突")
    void verify_fieldAndTrackOverlapIsNotClash() {
        Set<Long> athlete = Set.of(7L);
        ScheduleVerifier.Row field = row(2L, "跳远", false, 1, 480, 560, athlete);
        ScheduleVerifier.Row track = row(1L, "100米", true, 1, 530, 560, athlete);

        assertFalse(AthleteClashCheck.athleteClash(field, track));
    }

    @Test
    @DisplayName("自检：同池（径径）重叠仍是真兼项冲突（对照组）")
    void verify_samePoolOverlapIsStillClash() {
        Set<Long> athlete = Set.of(7L);
        ScheduleVerifier.Row a = row(1L, "100米", true, 1, 480, 510, athlete);
        ScheduleVerifier.Row b = row(2L, "200米", true, 1, 490, 520, athlete);

        assertTrue(AthleteClashCheck.athleteClash(a, b),
                "同池重叠必须仍判兼项冲突，否则回归会漏掉真正的赶不上");
    }

    @Test
    @DisplayName("自检：跨天紧邻（day1 23:50 + day2 00:00）不报兼项")
    void verify_crossDayAdjacentIsNotClash() {
        Set<Long> athlete = Set.of(7L);
        // day1 23:50-24:00（1430-1440），day2 00:00-00:10（0-10）——绝对分钟只差 10，裸算会误报
        ScheduleVerifier.Row a = row(1L, "100米", true, 1, 1430, 1440, athlete);
        ScheduleVerifier.Row b = row(2L, "200米", true, 2, 0, 10, athlete);

        assertFalse(AthleteClashCheck.athleteClash(a, b),
                "隔夜的两个项目不该判为赶不上");
    }

    @Test
    @DisplayName("自检：track 未知（旧调用）时退回保守口径，同段仍算冲突")
    void verify_unknownTrackFallsBackConservative() {
        Set<Long> athlete = Set.of(7L);
        Map<Long, String> names = new LinkedHashMap<>();
        names.put(7L, "选手7");
        ScheduleVerifier.Row a = new ScheduleVerifier.Row(1L, "100米", "高一", 1, "2026-01-01", "上午",
                480, 510, "跑道", null, new LinkedHashSet<>(athlete), names);
        ScheduleVerifier.Row b = new ScheduleVerifier.Row(2L, "跳远", "高一", 1, "2026-01-01", "上午",
                490, 560, "沙坑", null, new LinkedHashSet<>(athlete), names);

        assertTrue(AthleteClashCheck.athleteClash(a, b),
                "没有 track 信息时不能擅自放行，保守按重叠判");
    }

    // ==================== 求解器 ScheduleConstraintProvider ====================

    @Test
    @DisplayName("求解器：径田同段重叠不罚兼项冲突")
    void solver_trackAndFieldOverlapIsNotClash() {
        ScheduleUnit track = unit("径赛", true, 1, 480, 30);
        ScheduleUnit field = unit("田赛", false, 1, 490, 30);

        assertFalse(ScheduleConstraintProvider.athleteClash(track, field));
    }

    @Test
    @DisplayName("求解器：同池重叠仍罚兼项冲突（对照组）")
    void solver_samePoolOverlapIsStillClash() {
        ScheduleUnit a = unit("径赛A", true, 1, 480, 30);
        ScheduleUnit b = unit("径赛B", true, 1, 490, 30);

        assertTrue(ScheduleConstraintProvider.athleteClash(a, b));
    }

    @Test
    @DisplayName("求解器：跨天紧邻不罚兼项冲突")
    void solver_crossDayAdjacentIsNotClash() {
        // day1 1430 + 30 分 → 1460；day2 0 起 → 1440。绝对分钟只差 10，裸算会误报
        ScheduleUnit a = unit("径赛A", true, 1, 1430, 30);
        ScheduleUnit b = unit("径赛B", true, 2, 0, 30);

        assertFalse(ScheduleConstraintProvider.athleteClash(a, b));
    }

    // ==================== 夹具 ====================

    private static ScheduleVerifier.Row row(long eventId, String name, boolean track, int day,
                                            int start, int end, Set<Long> athletes) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long id : athletes) names.put(id, "选手" + id);
        return new ScheduleVerifier.Row(eventId, name, "高一", day, "2026-01-0" + day, "上午",
                start, end, track ? "跑道" : "沙坑", null, new LinkedHashSet<>(athletes), names, track);
    }

    /** 构造已落位的单元：track=是否径赛，day/start 决定绝对起点，duration=30，共享一名运动员 */
    private static ScheduleUnit unit(String key, boolean track, int day, int startMinute, int duration) {
        long[] athletes = {7L};
        ScheduleUnit u = new ScheduleUnit(key, 1L, key, "高一", track, track ? "径赛" : "田赛",
                null, 5, duration, duration, athletes, new ArrayList<>(List.of(duration)), new ArrayList<>());
        Placement p = new Placement(track ? "径赛" : "田赛", 0, 0, day, "2026-01-0" + day, "上午",
                track ? "跑道" : "沙坑", startMinute, startMinute, 600);
        u.setPlacement(p);
        u.setDuration(duration);
        return u;
    }
}
