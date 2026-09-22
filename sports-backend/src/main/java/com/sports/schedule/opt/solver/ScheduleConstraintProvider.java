package com.sports.schedule.opt.solver;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.sports.schedule.rule.inject.RuleContext;
import com.sports.schedule.rule.inject.RuleInjectionHolder;
import com.sports.schedule.rule.inject.RuleInjectionService;
import com.sports.schedule.rule.inject.RuleOutcome;

import java.util.LinkedHashMap;
import java.util.Map;
import com.sports.schedule.core.primitive.Cursor;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.arrange.ConflictService;

/**
 * 赛程编排的约束定义（约束流）。
 *
 * <p>三层评分与赛会决策优先级一一对应，且<b>同一条口径只写一次</b>：</p>
 * <table border="1">
 *   <tr><th>层级</th><th>约束</th><th>含义</th></tr>
 *   <tr><td>硬</td><td>必须排入 / 时长合法 / 整块落在窗口内 / 自己的池</td><td>方案合法性</td></tr>
 *   <tr><td>硬</td><td>同并发位不重叠 / 同组同时开赛</td><td>资源与赛会惯例</td></tr>
 *   <tr><td>中</td><td>运动员兼项不撞车</td><td>与冲突检测端同口径（15 分钟缓冲）</td></tr>
 *   <tr><td>软</td><td>保留真实用时</td><td>替代「池级一刀切压缩」</td></tr>
 *   <tr><td>软</td><td>优先前面的比赛日 / 较早时段</td><td>赛程紧凑、现场从容</td></tr>
 * </table>
 *
 * <p><b>注意「同并发位不重叠」已经蕴含容量约束</b>：同一 bin 内所有项目都在窗口内且互不重叠，
 * 其时长之和必然 ≤ 窗口容量，因此无需再写一条冗余的容量约束（写重复约束只会让评分失真、
 * 让「硬分到底差多少」无法解读）。</p>
 *
 * <p><b>约束名一律用 ASCII 标识符，不要改成中文</b>：求解器对 {@code asConstraint(...)} 的名字
 * 有字符集校验（只允许字母数字/空格/下划线/连字符/撇号/括号/句点，且必须以字母数字开头），
 * 中文名会让求解器在启动期直接抛 {@code IllegalArgumentException}，表现为「一求解就失败并
 * 静默降级回贪心」——症状很隐蔽。中文含义见各方法上的 Javadoc。</p>
 */
public class ScheduleConstraintProvider implements ConstraintProvider {

    /**
     * 兼项缓冲（分钟）：与 {@code ConflictService.CONFLICT_BUFFER_MIN} 严格同值。
     *
     * <p>编排端与检测端若不同口径，就会出现「排的时候说不冲突、检测时又说冲突」的怪象，
     * 因此这里刻意重复声明同一个字面量，并在回归测试里断言两者相等。</p>
     */
    public static final int CONFLICT_BUFFER_MIN = 15;

    /** 比赛日顺延的软惩罚（每往后一天）：需显著大于「压缩 1 分钟」的惩罚，保证「不轻易跨天」 */
    private static final int DAY_PENALTY = 200;
    /** 当天时段顺延的软惩罚粒度：每 N 分钟记 1 分 */
    private static final int START_MINUTE_PENALTY_STEP = 5;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory cf) {
        return new Constraint[] {
                mustBePlaced(cf),
                durationMustRespectFloor(cf),
                mustFitIntoWindow(cf),
                mustUseOwnPool(cf),
                mustNotOverlapInBin(cf),
                groupMustStartTogether(cf),
                athleteMustNotClash(cf),
                keepRealDuration(cf),
                preferEarlierDay(cf),
                preferEarlierStart(cf),
                // 规则注入（形态一）：用户规则片段 → 动态约束。未配置脚本时零成本短路。
                ruleInjectionHard(cf),
                ruleInjectionMedium(cf),
                ruleInjectionSoft(cf),
        };
    }

    // ==================== 规则注入（形态一 → 动态约束） ====================

    /**
     * 硬：用户规则片段判定的「否决 / 硬违规」。权重取规则累计 hard（veto 记 1）。
     *
     * <p>约束名必须 ASCII（见类注释）；规则来自 {@link RuleInjectionHolder}（Spring 绑定），
     * 无启用脚本时 {@code active()} 为 false，本约束不产生任何评分与开销。</p>
     */
    private Constraint ruleInjectionHard(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced() && RuleInjectionHolder.active())
                .filter(u -> ruleHard(u) > 0)
                .penalize(HardMediumSoftScore.ONE_HARD, u -> clamp(ruleHard(u)))
                .asConstraint("ruleInjectionHard");
    }

    /** 中：用户规则片段累计的 medium 惩罚。 */
    private Constraint ruleInjectionMedium(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced() && RuleInjectionHolder.active())
                .filter(u -> ruleMedium(u) > 0)
                .penalize(HardMediumSoftScore.ONE_MEDIUM, u -> clamp(ruleMedium(u)))
                .asConstraint("ruleInjectionMedium");
    }

    /** 软：用户规则片段累计的 soft 惩罚。 */
    private Constraint ruleInjectionSoft(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced() && RuleInjectionHolder.active())
                .filter(u -> ruleSoft(u) > 0)
                .penalize(HardMediumSoftScore.ONE_SOFT, u -> clamp(ruleSoft(u)))
                .asConstraint("ruleInjectionSoft");
    }

    private static int clamp(long v) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v));
    }

    static long ruleHard(ScheduleUnit u) {
        RuleOutcome o = ruleOutcomeOf(u);
        return o.veto() ? Math.max(1, o.hard()) : o.hard();
    }

    static long ruleMedium(ScheduleUnit u) {
        return ruleOutcomeOf(u).medium();
    }

    static long ruleSoft(ScheduleUnit u) {
        return ruleOutcomeOf(u).soft();
    }

    /** 评估单元当前落位下的规则注入结果（按「单元|落位」记忆，避免热路径重复求值）。 */
    static RuleOutcome ruleOutcomeOf(ScheduleUnit u) {
        RuleInjectionService svc = RuleInjectionHolder.get();
        if (svc == null || u == null || !u.isPlaced() || u.getPlacement() == null) {
            return RuleOutcome.empty();
        }
        Placement p = u.getPlacement();
        String key = u.getKey() + "|" + p.getDay() + "|" + p.getStartMinute() + "|"
                + p.getPoolLabel() + "|" + u.getDuration();
        return svc.assessCached(key, ruleContextOf(u));
    }

    /**
     * 组装规则上下文：约束流可见的字段（事件/年级/场池/落位）——用户规则片段据此判定。
     *
     * <p>⚠️ {@code event.*} 取自 {@link ScheduleUnit#getEventAttrs()}，其键与
     * {@code ArrangementService#ruleContextOf} 对齐（category/track/team/teamMembers/venueCode/gradeGroup…）。
     * 若此处另起一套字段名，同一份脚本在「编排注入」与「求解约束」两条路径上语义就会不一致——
     * 典型症状是规则在编排时生效、求解时静默失效。</p>
     *
     * <p>求解侧按「单元」粒度评估，因此不提供编排侧的 {@code heat}/{@code lane}/{@code athlete.*}
     * （单元不含单个运动员），需要按运动员/道次判定的规则请用编排侧或改用 {@code placement.*}。</p>
     */
    static RuleContext ruleContextOf(ScheduleUnit u) {
        Map<String, Object> ev = new LinkedHashMap<>();
        if (u.getEventAttrs() != null) {
            ev.putAll(u.getEventAttrs());
        }
        // 兜底：即便未携带附加属性，也保证 id/name/track 可按同一组键取到
        ev.putIfAbsent("id", u.getEventId());
        ev.putIfAbsent("name", u.getEventName());
        ev.putIfAbsent("track", u.isTrack());
        Map<String, Object> pl = new LinkedHashMap<>();
        Placement p = u.getPlacement();
        if (p != null) {
            pl.put("day", p.getDay());
            pl.put("startMinute", p.getStartMinute());
            pl.put("poolLabel", p.getPoolLabel());
        }
        return RuleContext.builder()
                .put("event", ev)
                .put("grade", u.getGrade())
                .put("track", u.isTrack())
                .put("poolLabel", u.getPoolLabel())
                .put("groupKey", u.getGroupKey())
                .put("duration", u.getDuration())
                .put("placement", pl)
                .build();
    }

    /** 硬：每个单元都必须落到某个位置上（排不下就得如实报，而不是悄悄丢失） */
    private Constraint mustBePlaced(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> !u.isPlaced())
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mustBeAssigned");
    }

    /** 硬：时长不得低于可执行下限（求解器若把项目压到无法执行，方案就无意义） */
    private Constraint durationMustRespectFloor(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.getDuration() != null && u.getDuration() < u.getMinDuration())
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("durationRespectsFloor");
    }

    /** 硬：项目时间是编排的原子单位——整块必须落在时段窗口内，不得跨窗口溢出 */
    private Constraint mustFitIntoWindow(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced()
                        && u.getPlacement().getStartMinute() + u.getDuration() > u.getPlacement().getWindowEndMinute())
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mustFitIntoWindow");
    }

    /** 硬：项目只能落在自己的并发池（径赛项目不能占用田赛位，反之亦然） */
    private Constraint mustUseOwnPool(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.getPlacement() != null && !u.getPoolLabel().equals(u.getPlacement().getPoolLabel()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mustUseOwnPool");
    }

    /**
     * 硬：同一并发位（池 × 槽位 × 窗口）内两个项目不得时间重叠，且需保留段前间隔。
     *
     * <p>间隔取两者较小值：不同项目的轮次间隔可能不同，取小值可以保证「谁都不会被压到别人的
     * 尾巴上」，同时不至于因为某个项目间隔大就把整块时间浪费掉。</p>
     */
    private Constraint mustNotOverlapInBin(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced())
                .join(ScheduleUnit.class,
                        Joiners.equal(u -> u.getPlacement() == null ? "" : u.getPlacement().getBinKey()),
                        Joiners.lessThan(ScheduleUnit::getKey))
                .filter((a, b) -> b.isPlaced() && overlaps(a, b))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mustNotOverlapInBin");
    }

    /** 硬：同组同年级的田赛项目必须同时开赛（同一天、同一分钟，各占自己的并发位） */
    private Constraint groupMustStartTogether(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced() && u.getGroupKey() != null)
                .join(ScheduleUnit.class,
                        Joiners.equal(ScheduleUnit::getGroupKey),
                        Joiners.lessThan(ScheduleUnit::getKey))
                .filter((a, b) -> b.isPlaced() && b.getGroupKey() != null
                        && !a.getPlacement().getDayStartKey().equals(b.getPlacement().getDayStartKey()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("groupMustStartTogether");
    }

    /**
     * 中：兼报多项的运动员不得在同一时刻被叫到两处（含 15 分钟赶场缓冲）。
     *
     * <p>放「中」而不放「硬」是刻意设计：容量客观不足时，硬约束会让求解器直接无解，
     * 而现场要的是「给出一个冲突最少的可行方案，再告诉我还差多少」。三层评分正好表达这点。</p>
     */
    private Constraint athleteMustNotClash(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.isPlaced() && u.hasAthletes())
                .join(ScheduleUnit.class, Joiners.lessThan(ScheduleUnit::getKey))
                .filter((a, b) -> b.isPlaced() && b.hasAthletes()
                        && a.sharesAthlete(b) && athleteClash(a, b))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("athleteMustNotClash");
    }

    /**
     * 软：尽量保留项目真实用时（压缩 1 分钟记 1 分）。
     *
     * <p>这是替代「池级统一等比压缩」的关键：求解器可以在紧凑处多压、在宽松处不压，
     * 而统一比例做不到这件事。</p>
     */
    private Constraint keepRealDuration(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(u -> u.getDuration() != null && u.getDuration() < u.getRawDuration())
                .penalize(HardMediumSoftScore.ONE_SOFT, u -> u.getRawDuration() - u.getDuration())
                .asConstraint("keepRealDuration");
    }

    /** 软：优先排在前面的比赛日（跨天代价高，避免赛程无谓拉长） */
    private Constraint preferEarlierDay(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(ScheduleUnit::isPlaced)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        u -> Math.max(0, u.getPlacement().getDay() - 1) * DAY_PENALTY)
                .asConstraint("preferEarlierDay");
    }

    /** 软：当天优先排在较早时段（同一天内更紧凑、更早收工） */
    private Constraint preferEarlierStart(ConstraintFactory cf) {
        return cf.forEachIncludingUnassigned(ScheduleUnit.class)
                .filter(ScheduleUnit::isPlaced)
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        u -> Math.max(0, u.getPlacement().getStartMinute()) / START_MINUTE_PENALTY_STEP)
                .asConstraint("preferEarlierStart");
    }

    // ==================== 判定工具 ====================

    /** 同并发位内是否重叠（含段前间隔，口径与贪心放置的 Cursor 一致） */
    static boolean overlaps(ScheduleUnit a, ScheduleUnit b) {
        if (!a.getPlacement().getBinKey().equals(b.getPlacement().getBinKey())) return false;
        int gap = Math.min(a.getInterval(), b.getInterval());
        int aS = a.getPlacement().getStartMinute();
        int aE = aS + a.getDuration();
        int bS = b.getPlacement().getStartMinute();
        int bE = bS + b.getDuration();
        return aS < bE + gap && bS < aE + gap;
    }

    /** 运动员是否有两个项目赶不上（跨天用绝对分钟比较；口径同 ConflictService） */
    public static boolean athleteClash(ScheduleUnit a, ScheduleUnit b) {
        int aS = a.getPlacement().getAbsoluteStartMinute();
        int aE = aS + a.getDuration();
        int bS = b.getPlacement().getAbsoluteStartMinute();
        int bE = bS + b.getDuration();
        return aS < bE + CONFLICT_BUFFER_MIN && bS < aE + CONFLICT_BUFFER_MIN;
    }
}
