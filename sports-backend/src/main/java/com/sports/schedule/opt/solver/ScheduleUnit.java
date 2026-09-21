package com.sports.schedule.opt.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import com.sports.entity.event.Event;

/**
 * 待排的赛程单元（约束求解的「计划实体」）= 项目 × 年级。
 *
 * <p>两个计划变量<b>联合决定</b>一个单元的落地形态：</p>
 * <ul>
 *   <li>{@link #placement} —— 放在哪个并发位的哪个起点（整块落位，不做「就剩余空间缩短」）；</li>
 *   <li>{@link #duration} —— 该项目分到多少分钟（在 {@link #durationChoices} 里选）。</li>
 * </ul>
 *
 * <p><b>为什么把「时长」也交给求解器</b>：容量不足时，旧实现只能对整个池<b>统一等比压缩</b>
 * （一个比例套所有项目），于是出现「明明还有项目排得宽松、却被一刀切压掉一半」。
 * 现在由求解器逐单元权衡：软约束是「尽量保留真实用时」，硬约束是「不得超过所在位置余量」，
 * 因此容量宽松的项目能保住真实时长，只有真正拥挤的才被压——这才是「按需压缩」。</p>
 */
@PlanningEntity
@Getter
public class ScheduleUnit {

    /*
     * ⚠️ 本类所有字段都**不能是 final**。Timefold 求解前要生成「计划克隆（planning clone）」：
     * 它用无参构造器新建实例再逐个回填字段，final 字段无法回填，会在求解启动期抛
     * IllegalStateException: To create a planning clone, the class (...) must have a no-arg constructor.
     */

    @PlanningId
    private String key;

    private Long eventId;
    private String eventName;
    private String grade;
    private boolean track;
    /** 只能落在哪个池（径赛/田赛/专用池） */
    private String poolLabel;

    /**
     * 田赛分组键（同组 + 同年级）。
     *
     * <p>非空表示这些项目必须<b>同时开赛</b>（落在同一天、同一分钟，但各自占用不同并发位）。
     * 这是赛会惯例：同组投掷类项目同时进行，便于集中裁判与器材。null = 无分组。</p>
     */
    private String groupKey;

    /** 项目内相邻轮次的最小间隔（分钟），与贪心放置的「段前间隔」口径一致 */
    private int interval;

    /** 真实估算用时（未被压缩时的时长） */
    private int rawDuration;
    /** 该项目的时长下限（低于此值现场已不可执行） */
    private int minDuration;

    /** 参赛运动员 id（升序，供双指针求交；用于兼项冲突判定） */
    private long[] athletes;

    /** 候选时长（降序，含 rawDuration），实体的私有值域 */
    private List<Integer> durationChoices;

    /**
     * 候选位置（本实体私有值域）：只包含<b>自己所属并发池里、且放得下它时长下限</b>的位置。
     *
     * <p>做成实体私有值域而不是全局值域，是为了从源头消灭无效搜索：全局值域会让局部搜索
     * 反复尝试「把径赛项目放进田赛位」，白白消耗评分计算；限定池内候选后，
     * 求解器的每一步 move 都落在语义合法的范围里。</p>
     */
    private List<Placement> candidatePlacements;

    /**
     * 无参构造器：Timefold 生成计划克隆时必须使用（见类顶部的字段说明）。
     *
     * <p>保留它同时也是「求解器友好的领域对象」的通用要求：领域对象应是可被框架按字段重建的
     * 普通 POJO，而不是只能靠业务构造器一次性装配的不可变值对象。</p>
     */
    public ScheduleUnit() {
    }

    @Setter
    @PlanningVariable(valueRangeProviderRefs = "placementRange", allowsUnassigned = true)
    private Placement placement;

    @Setter
    @PlanningVariable(valueRangeProviderRefs = "durationRange")
    private Integer duration;

    /**
     * 锁定标记：被锁定的实体在本次求解中<b>不参与任何移动</b>。
     *
     * <p>大邻域搜索（LNS）靠它实现「只重排被破坏的那一块，其余原地不动」——
     * 这正是 LNS 与「整体重排」的根本区别：破坏一小部分解、只重建这一部分，
     * 因此能在有限时间内做很多次局部探索，而不是每次推倒重来。</p>
     */
    @Setter
    @Getter
    @PlanningPin
    private boolean pinned;

    /**
     * 事件的附加属性（供「规则注入」上下文读取，如 category / team / teamMembers / venueCode / gradeGroup…）。
     *
     * <p><b>为什么要有它</b>：规则片段的字段是按「事件属性」描述的（如
     * {@code when event.category == "径赛" then soft += 30}）。编排路径直接持有 Event，字段天然齐备；
     * 而求解路径只有单元，若不放行这些属性，同一条规则在编排阶段命中、到了求解阶段却
     * <b>静默永不命中</b>——同一份脚本在两条路径上语义不一致，属实质性缺陷。</p>
     *
     * <p><b>为什么是 Map 而不是直接持有 {@link com.sports.entity.event.Event}</b>：求解域刻意只保留值对象，
     * 不把 JPA 实体（含懒加载代理）带进 Timefold 的解空间；用 Map 还能让后续 DSL 新增字段
     * 不必改本类签名。</p>
     */
    private Map<String, Object> eventAttrs;

    public ScheduleUnit(String key, Long eventId, String eventName, String grade, boolean track,
                        String poolLabel, String groupKey, int interval, int rawDuration, int minDuration,
                        long[] athletes, List<Integer> durationChoices, List<Placement> candidatePlacements) {
        this(key, eventId, eventName, grade, track, poolLabel, groupKey, interval, rawDuration, minDuration,
                athletes, durationChoices, candidatePlacements, null);
    }

    /**
     * 完整构造：额外携带事件的规则注入属性（见 {@link #eventAttrs}）。
     */
    public ScheduleUnit(String key, Long eventId, String eventName, String grade, boolean track,
                        String poolLabel, String groupKey, int interval, int rawDuration, int minDuration,
                        long[] athletes, List<Integer> durationChoices, List<Placement> candidatePlacements,
                        Map<String, Object> eventAttrs) {
        this.key = key;
        this.eventId = eventId;
        this.eventName = eventName;
        this.grade = grade;
        this.track = track;
        this.poolLabel = poolLabel;
        this.groupKey = groupKey;
        this.interval = interval;
        this.rawDuration = rawDuration;
        this.minDuration = minDuration;
        this.athletes = athletes;
        this.durationChoices = durationChoices;
        this.candidatePlacements = candidatePlacements;
        this.eventAttrs = eventAttrs;
    }

    /**
     * 本单元的候选位置（实体私有值域）。
     *
     * @see #candidatePlacements
     */
    @ValueRangeProvider(id = "placementRange")
    public List<Placement> placementRange() {
        return candidatePlacements;
    }

    /**
     * 本单元的候选时长（实体私有值域）。
     *
     * <p>放在实体上而非解上：每个项目的真实估算不同，「100%、90%…40% 下限」这组档位
     * 必须按各自 rawDuration 折算，用全局值域会枚举出大量无意义组合。</p>
     */
    @ValueRangeProvider(id = "durationRange")
    public List<Integer> durationRange() {
        return durationChoices;
    }

    /**
     * 深拷贝本单元（值相同的全新实例）。
     *
     * <p>供遗传算法（交叉/变异）、大邻域搜索（每轮破坏前克隆）使用——必须拷贝，
     * 否则破坏/变异会直接毁掉当前最优解。位置对象（Placement）与运动员数组、
     * 候选列表在构造后只读，可安全共享引用。</p>
     */
    public ScheduleUnit copy() {
        ScheduleUnit c = new ScheduleUnit(key, eventId, eventName, grade, track, poolLabel, groupKey,
                interval, rawDuration, minDuration, athletes, durationChoices, candidatePlacements, eventAttrs);
        c.setPlacement(placement);
        c.setDuration(duration);
        c.setPinned(pinned);
        return c;
    }

    public boolean isPlaced() {
        return placement != null && duration != null;
    }

    /** 绝对结束分钟（跨天可比较）；未排入时为 null */
    public Integer getEndMinute() {
        return isPlaced() ? placement.getStartMinute() + duration : null;
    }

    public boolean hasAthletes() {
        return athletes != null && athletes.length > 0;
    }

    /**
     * 两人是否兼报了同一项目（升序数组双指针求交，O(n+m)）。
     *
     * <p>兼项冲突是约束流里调用最频繁的判定，用有序 long[] 而不是 Set，
     * 既避免装箱也避免哈希开销。</p>
     */
    public boolean sharesAthlete(ScheduleUnit other) {
        long[] a = this.athletes;
        long[] b = other.athletes;
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) return true;
            if (a[i] < b[j]) i++;
            else j++;
        }
        return false;
    }

    @Override
    public String toString() {
        return eventName + "(" + (grade == null ? "不分年级" : grade) + ")";
    }
}
