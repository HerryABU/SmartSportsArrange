package com.sports.schedule.core.primitive;

import com.sports.entity.event.Event;

import java.util.HashSet;
import java.util.Set;
import com.sports.service.schedule.ScheduleService;

/**
 * 一个赛程单元 = 项目 × 年级。
 *
 * <p>从 {@code ScheduleService} 抽出为顶层类：它是编排主流程、求解器装配、放置与可行性评估
 * 共用的核心数据载体。字段保持 public 以便编排代码直接读写（与原内部类行为一致）。</p>
 */
public class Unit {

    public Event event;
    public String grade;
    /** 是否径赛 */
    public boolean track;
    /** 项目内并发人数（径赛=道次/每组人数；田赛=工位数） */
    public int concurrency = 1;
    public int duration;
    public int rawDuration;
    /** 项目显式配置的时长上限（0 = 未配置；仅此时才允许按时长封顶） */
    public int explicitMaxDuration;
    public int participants;
    /** 径赛组数 */
    public int heats;
    /** 总轮次（径赛=组数、田赛=批次数） */
    public int rounds;
    /** 径赛自动道次编排成功的性别组数 */
    public int arranged;

    /**
     * 本单元的参赛运动员（已审核报名）。
     *
     * <p>U23/B20：兼项冲突规避的关键输入——放置时据此判断「把该项目排在这个时间点，
     * 会不会和该运动员已排的其它项目撞车」。</p>
     */
    public final Set<Long> athleteIds = new HashSet<>();

    public Unit(Event event, String grade) {
        this.event = event;
        this.grade = grade;
    }

    /**
     * 单个组次（径赛的一组 / 田赛的一批）的用时（分钟）。
     *
     * <p>由「整块时长 ÷ 组次数」推导，是本项目里<b>唯一</b>的组次粒度口径：
     * 道次编排把项目切成 {@code rounds} 个组次依次开赛，跨时段拆分也必须按它对齐——
     * 否则一个组次会被拦腰截成「上午半组 + 下午半组」，现场既没法检录也没法记成绩。
     *
     * <p>下限 1 分钟：{@code rounds} 大于 {@code duration}（人数极少而组数被硬约束撑高）时
     * 不能算出 0，否则按组次切分会出现长度为 0 的段。
     */
    public int perRoundMinutes() {
        return Math.max(1, duration / Math.max(1, rounds));
    }

    /**
     * 本单元能否被组次边界整除地切分：{@code duration} 是否为组次用时的整数倍。
     *
     * <p>用于判定「跨时段拆分后，两段各自是否仍是若干个完整组次」。
     */
    public boolean splittableByRound() {
        int per = perRoundMinutes();
        return rounds <= 1 || duration % per == 0;
    }
}
