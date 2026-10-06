package com.sports.common.util;

import java.util.Comparator;
import java.util.function.Function;

/**
 * 筛选链（预赛淘汰 / 晋级 / 组内名次）的<b>排序方向</b>与<b>名额口径</b>——纯函数、无状态、可独立单测。
 *
 * <p><b>为什么单独抽出来：</b>这两个口径原本硬编码在 {@code ArrangementService} 里
 * （{@code Comparator.comparing(成绩)} 升序取前 N）。对径赛（时间越短越好）是对的，
 * 但<b>田赛成绩是距离/高度（越大越好）</b>，升序取前 N 会把成绩最差的先"晋级"。
 * 方向必须由项目表配置决定，而不是写死。</p>
 *
 * <p>⚠️ <b>只服务筛选链，与编排无关</b>：编排既不读排名顺序也不读筛选名额。
 * 本类被引入编排链路即属越界（会让"改排名顺序"影响赛程/道次，那不是本节要的行为）。</p>
 */
public final class QualifyPolicy {

    /** 排名顺序：从小到大（成绩越小越靠前）——径赛时间类。 */
    public static final int ORDER_ASC = 0;
    /** 排名顺序：从大到小（成绩越大越靠前）——田赛距离 / 高度类。 */
    public static final int ORDER_DESC = 1;

    private QualifyPolicy() {
    }

    /**
     * 归一化排名顺序：只认 {@link #ORDER_DESC}（=1）为降序，其余（含 null / 其它值）一律升序。
     *
     * <p>兜底选升序是为了<b>不改存量数据的行为</b>——历史口径就是升序，不能让加字段这件事
     * 悄悄改变已有项目的晋级名单。</p>
     */
    public static int normalizeRankOrder(Integer rankOrder) {
        return rankOrder != null && rankOrder == ORDER_DESC ? ORDER_DESC : ORDER_ASC;
    }

    /** 是否降序（从大到小）。 */
    public static boolean isDesc(Integer rankOrder) {
        return normalizeRankOrder(rankOrder) == ORDER_DESC;
    }

    /** 排名顺序的中文口径（日志与接口回显共用）。 */
    public static String describeRankOrder(Integer rankOrder) {
        return isDesc(rankOrder) ? "从大到小" : "从小到大";
    }

    /**
     * 按「成绩值」构造比较器：方向由排名顺序决定，<b>成绩缺失者一律排最后</b>。
     *
     * <p>⚠️ 必须写成 {@code nullsLast(reverseOrder/naturalOrder)}，<b>不能</b>写
     * {@code comparator.reversed()}——后者会把 {@code nullsLast} 一并反转成「null 最前」，
     * 于是<b>没录成绩的人反而被排进晋级区</b>。这个坑集中收敛在本方法一处，
     * 由 {@code QualifyPolicyTest} 钉住。</p>
     */
    public static <T> Comparator<T> byResult(boolean desc, Function<T, Double> value) {
        // 显式 <Double> 类型见证：条件表达式 + 泛型方法的目标类型推断容易含糊，写死更稳
        Comparator<Double> byValue = desc ? Comparator.<Double>reverseOrder() : Comparator.<Double>naturalOrder();
        return Comparator.comparing(value, Comparator.nullsLast(byValue));
    }

    /**
     * 名额来源——必须回显给调用方：否则「填了百分比却按人数走」这类
     * <b>配了不生效</b>的静默失效在页面上看不出来，与「跑了但没改进」无法区分。
     */
    public enum QuotaSource {
        /** 本次请求显式传入的临时名额（接口参数） */
        REQUEST,
        /** 项目表配置的晋级人数 */
        COUNT,
        /** 项目表配置的晋级百分比（按参与人数折算） */
        PERCENT,
        /** 都没配，走内置兜底 8 */
        DEFAULT
    }

    /** 名额解析结果：名额 + 来源。 */
    public record Quota(int count, QuotaSource source) {
    }

    /** 百分比 ↔ 人数 的换算基数：实际人数 or 最大报名人数。 */
    public enum BaseMode {
        /** 实际参与/报名人数（真实口径，筛选时刻才知道） */
        ACTUAL,
        /** 最大报名人数（配置期可得，表格里就能算） */
        MAX
    }

    /**
     * 换算基数解析：按用户选择取「最大报名人数」或「实际人数」。
     *
     * <p>用户口径（原话）：<i>「实际人数 = 最大人数则一样；实际 &lt; 最大报名人数时，让用户选择」</i>。
     * 所以入参是用户的显式选择，本方法只负责在所选基数不可用时<b>如实退回</b>另一个，
     * 并把最终用了哪个基数交给调用方回显（绝不静默换基数）。</p>
     */
    public static int resolveBase(BaseMode mode, int actualCount, Integer maxParticipants) {
        if (mode == BaseMode.MAX && maxParticipants != null && maxParticipants > 0) {
            return maxParticipants;
        }
        return Math.max(0, actualCount);
    }

    /**
     * 解析晋级名额。
     *
     * <p><b>优先级：请求显式指定 &gt; 百分比 &gt; 人数 &gt; 兜底 8。</b></p>
     *
     * <p>为什么<b>百分比优先于人数</b>：{@code advanceCount} 带默认值 8，存量数据人人都是 8。
     * 若人数优先，用户新填的百分比会被这个默认 8 <b>悄悄顶掉</b>——正是"配了不生效"那一类缺陷。
     * 百分比是后加的可选口径，填了就是明确意图，故优先。</p>
     *
     * @param requestCount  接口临时指定的人数（可空）
     * @param advanceCount  项目表配置的晋级人数（可空）
     * @param advancePercent 项目表配置的晋级百分比（可空；30 表示前 30%）
     * @param participants  实际参与人数
     * @param maxParticipants 最大报名人数（按 MAX 基数折算时用）
     * @param baseMode      百分比折算基数（ACTUAL / MAX）
     */
    public static Quota resolveQuota(Integer requestCount, Integer advanceCount, Double advancePercent,
                                     int participants, Integer maxParticipants, BaseMode baseMode) {
        if (requestCount != null && requestCount > 0) {
            return new Quota(requestCount, QuotaSource.REQUEST);
        }
        if (advancePercent != null && advancePercent > 0) {
            int base = resolveBase(baseMode, participants, maxParticipants);
            if (base > 0) {
                int pct = (int) Math.ceil(base * advancePercent / 100.0);
                // 夹在 [1, base]：1 人报名×10% 不应算出 0 人（看起来像"没人晋级"），
                // 120% 这类越界配置也不应"晋级比报名还多"。
                int capped = Math.max(1, Math.min(base, pct));
                // 名额不该超过实际参与人数，否则会"晋级"出不存在的人
                if (participants > 0) {
                    capped = Math.min(capped, participants);
                }
                return new Quota(capped, QuotaSource.PERCENT);
            }
        }
        if (advanceCount != null && advanceCount > 0) {
            return new Quota(advanceCount, QuotaSource.COUNT);
        }
        return new Quota(8, QuotaSource.DEFAULT);
    }

    /** 兼容重载：默认按「实际人数」基数折算。 */
    public static Quota resolveQuota(Integer requestCount, Integer advanceCount, Double advancePercent,
                                     int participants) {
        return resolveQuota(requestCount, advanceCount, advancePercent, participants, null, BaseMode.ACTUAL);
    }

    /** 双向换算结果：人数与百分比成对返回（写入哪一列由调用方决定）。 */
    public record Pair(Integer count, Double percent) {
    }

    /** 被用户编辑（即作为输入）的那一列。 */
    public enum Edited {
        COUNT,
        PERCENT
    }

    /**
     * <b>百分比 ↔ 人数 双向换算的唯一实现</b>：填了一个，另一个按基数算出来。
     *
     * <p>前后端与批量修改都必须走这里——否则三处各写一份四舍五入，同一个项目会算出三个数。</p>
     *
     * <p>取整规则与 {@link #resolveQuota} 一致，保证「表里算出来的名额」与「筛选时真正用的名额」
     * 是同一个数：人数向上取整、夹在 [1, base]；百分比保留 1 位小数。<br>
     * 基数 ≤ 0（既没报名也没有上限）时<b>不猜</b>——percent 返回 null，让调用方如实显示"无法折算"。</p>
     */
    public static Pair convert(int base, Integer count, Double percent, Edited edited) {
        if (base <= 0) {
            return new Pair(count, null);
        }
        if (edited == Edited.COUNT) {
            if (count == null || count <= 0) {
                return new Pair(count, null);
            }
            int capped = Math.min(count, base);
            double pct = Math.round(capped * 1000.0 / base) / 10.0;   // 保留 1 位小数
            return new Pair(count, Math.min(100.0, Math.max(0.1, pct)));
        }
        if (percent == null || percent <= 0) {
            return new Pair(count, percent);
        }
        int pct = (int) Math.ceil(base * percent / 100.0);
        return new Pair(Math.max(1, Math.min(base, pct)), percent);
    }

    /** 名额来源的中文说明（前端/日志直接可用）。 */
    public static String describeQuotaSource(QuotaSource source, Double advancePercent) {
        return switch (source) {
            case REQUEST -> "本次接口指定";
            case COUNT -> "项目配置：晋级人数";
            case PERCENT -> "项目配置：晋级 " + (advancePercent == null ? "?" : trimNum(advancePercent)) + "%（按参与人数折算）";
            case DEFAULT -> "未配置，兜底 8 人";
        };
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
