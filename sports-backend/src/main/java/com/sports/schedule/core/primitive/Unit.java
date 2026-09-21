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
}
