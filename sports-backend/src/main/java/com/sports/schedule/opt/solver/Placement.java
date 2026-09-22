package com.sports.schedule.opt.solver;

import lombok.Getter;

import java.util.Objects;

/**
 * 一个可放置位置（约束求解的「问题事实」，不是计划变量）。
 *
 * <p>语义 = <b>并发池 × 槽位 × 时段窗口 × 窗口内起点</b>。即：把某个项目整块放进
 * 「径赛/田赛」的某个并发槽位、某个时段窗口内的某个起始分钟。</p>
 *
 * <p>把位置预先枚举成有限集合，是为了让求解器只做「选择」而不做「连续量推算」：
 * 值域大小 = 池数 × 槽位数 × 窗口数 × 起点档位数，实测在百量级，局部搜索绰绰有余。</p>
 */
@Getter
public class Placement {

    /**
     * 全局唯一标识：仅用于去重与日志可读性（形如 {@code 田赛#1#2@675}）。
     *
     * <p><b>刻意不标注 {@code @PlanningId}</b>：该注解的值只允许字母数字/空格/下划线/连字符/撇号，
     * 而这里为了人眼可读用了中文与 {@code #}、{@code @}。{@code @PlanningId} 只在多线程求解、
     * 需要跨线程定位实体时才必需，本场景是单线程求解，用 equals/hashCode 即可满足求解器需要。</p>
     */
    private final String id;

    /** 所属并发池标签：径赛 / 田赛 / 项目专用场地池 */
    private final String poolLabel;
    private final int slotIdx;
    private final int windowIdx;

    /** 第几个比赛日（从 1 起） */
    private final int day;
    private final String date;
    /** 时段名，如「上午」「下午」 */
    private final String slotName;
    /** 场地名（落库用） */
    private final String venue;

    /** 窗口内绝对起点（分钟，自当日 00:00 起算） */
    private final int startMinute;
    /** 该时段窗口的起点与容量（用于判断项目整块能否放得下） */
    private final int windowStartMinute;
    private final int windowCapacity;

    public Placement(String poolLabel, int slotIdx, int windowIdx, int day, String date, String slotName,
                     String venue, int startMinute, int windowStartMinute, int windowCapacity) {
        this.poolLabel = poolLabel;
        this.slotIdx = slotIdx;
        this.windowIdx = windowIdx;
        this.day = day;
        this.date = date;
        this.slotName = slotName;
        this.venue = venue;
        this.startMinute = startMinute;
        this.windowStartMinute = windowStartMinute;
        this.windowCapacity = windowCapacity;
        this.id = poolLabel + '#' + slotIdx + '#' + windowIdx + '@' + startMinute;
    }

    /**
     * 「并发位」标识 = 池 × 槽位 × 窗口。
     *
     * <p>同一个并发位是<b>独占资源</b>：落在同一 bin 的两个项目时间区间不得重叠。
     * 这等价于「该窗口内所有项目时长之和 ≤ 窗口容量」，因此无需再单独写容量约束。</p>
     */
    public String getBinKey() {
        return poolLabel + '#' + slotIdx + '#' + windowIdx;
    }

    /** 该窗口的结束分钟（绝对） */
    public int getWindowEndMinute() {
        return windowStartMinute + windowCapacity;
    }

    /** 放到此处后还能用多少分钟（项目整块时长不得超过它） */
    public int getMaxDuration() {
        return getWindowEndMinute() - startMinute;
    }

    /** 同日同起点的标识 —— 田赛分组「同时开赛」的判定依据 */
    public String getDayStartKey() {
        return day + "@" + startMinute;
    }

    /** 跨天可比较的绝对分钟（兼项冲突跨天判定用） */
    public int getAbsoluteStartMinute() {
        return (day - 1) * 1440 + startMinute;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Placement other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
