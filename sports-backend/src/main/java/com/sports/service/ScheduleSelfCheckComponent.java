package com.sports.service;

import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.entity.Registration;
import com.sports.schedule.analysis.LowerBoundEstimator;
import com.sports.schedule.core.SchedulePlacementMath;
import com.sports.schedule.core.Unit;
import com.sports.schedule.core.Window;
import com.sports.schedule.verify.ScheduleVerifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sports.schedule.support.ScheduleSupport.parseMinute;

/**
 * 赛程「自检数据装配 / 理论下界评估」组件（从 {@code ScheduleService} 抽出）。
 *
 * <p>职责：把落库后的真实赛程行转成独立校验视图（{@link ScheduleVerifier.Row}/{@code Expected}），
 * 并对「还能优化多少」给出理论下界评估。全部为无 Spring 依赖的纯装配逻辑，
 * 仅持有 {@code LowerBoundEstimator} 与 {@code ScheduleBuildComponent}（复用其报名查询能力）。</p>
 *
 * <p>自检的价值在于「生产者不能自己证明自己」：求解器与贪心都对编排结果负责，
 * 必须用一套<b>与编排无关的独立实现</b>对最终产物再查一遍。</p>
 */
public class ScheduleSelfCheckComponent {

    private final LowerBoundEstimator lowerBoundEstimator;
    private final ScheduleBuildComponent buildComponent;

    public ScheduleSelfCheckComponent(LowerBoundEstimator lowerBoundEstimator,
                                     ScheduleBuildComponent buildComponent) {
        this.lowerBoundEstimator = lowerBoundEstimator;
        this.buildComponent = buildComponent;
    }

    /**
     * 把落库后的赛程行转成校验视图。
     *
     * <p>刻意取<b>持久化之后的真实数据</b>（场地映射、时长回写都已完成），而不是求解器内存里的解：
     * 从「解」到「赛程表」之间要经过落库、场地映射、时长写回，任何一步出错求解器都看不见，
     * 只有对最终产物复核才查得出来。</p>
     */
    public List<ScheduleVerifier.Row> collectVerifyRows(List<EventSchedule> saved,
                                                       Map<Long, String> event2Group) {
        List<ScheduleVerifier.Row> rows = new ArrayList<>();
        for (EventSchedule s : saved) {
            Event e = s.getEvent();
            if (e == null || e.getId() == null) continue;
            Set<Long> athletes = new LinkedHashSet<>();
            Map<Long, String> names = new LinkedHashMap<>();
            for (Registration reg : buildComponent.approvedRegs(e.getId(), s.getGrade())) {
                if (reg.getAthlete() == null || reg.getAthlete().getId() == null) continue;
                Long aid = reg.getAthlete().getId();
                athletes.add(aid);
                names.put(aid, reg.getAthlete().getName());
            }
            String group = event2Group.get(e.getId());
            String groupKey = group == null ? null
                    : group + "@" + (s.getGrade() == null ? "" : s.getGrade());
            rows.add(new ScheduleVerifier.Row(e.getId(), e.getName(), s.getGrade(),
                    s.getDay() == null ? 0 : s.getDay(), s.getScheduleDate(), s.getTimeSlot(),
                    parseMinute(s.getStartTime()), parseMinute(s.getEndTime()), s.getVenue(),
                    groupKey, athletes, names));
        }
        return rows;
    }

    /** 编排表里「应该有」的单元：用于发现被静默丢弃的项目，并算出真实压缩比 */
    public List<ScheduleVerifier.Expected> collectVerifyExpected(List<Unit> units) {
        List<ScheduleVerifier.Expected> list = new ArrayList<>();
        for (Unit u : units) {
            if (u.participants <= 0 || u.event.getId() == null) continue;
            list.add(new ScheduleVerifier.Expected(
                    ScheduleVerifier.keyOf(u.event.getId(), u.grade),
                    u.event.getName(), u.grade, u.rawDuration));
        }
        return list;
    }

    /**
     * 理论下界评估（U31/B28）。
     *
     * <p>并发位按「径赛 / 田赛」两类聚合：项目级专用池（defaultVenueCode 绑定）较少见，
     * 为一个保守估计去主循环里额外维护 Unit→Pool 映射并不划算——下界本就允许偏松，
     * 偏松只会让 gap 看起来更小，不会把不可行说成可行（方向是安全的）。</p>
     */
    public LowerBoundEstimator.Assessment assessLowerBound(List<Unit> units, List<Window> windows,
                                                          List<EventSchedule> saved,
                                                          int trackSlots, int fieldSlots) {
        Map<String, Integer> slotsByPool = new LinkedHashMap<>();
        slotsByPool.put("径赛", Math.max(1, trackSlots));
        slotsByPool.put("田赛", Math.max(1, fieldSlots));

        List<LowerBoundEstimator.Item> items = new ArrayList<>();
        for (Unit u : units) {
            if (u.participants <= 0) continue;
            items.add(new LowerBoundEstimator.Item(u.track ? "径赛" : "田赛",
                    u.rawDuration, Math.max(1, SchedulePlacementMath.intervalOf(u, 5)), u.athleteIds));
        }

        Set<Integer> days = new HashSet<>();
        for (Window w : windows) days.add(w.day);

        int given = 0;
        for (EventSchedule s : saved) {
            given += Math.max(0, parseMinute(s.getEndTime()) - parseMinute(s.getStartTime()));
        }
        return lowerBoundEstimator.assess(items, slotsByPool, SchedulePlacementMath.dailyCapacityOf(windows),
                days.size(), given);
    }
}
