package com.sports.schedule.rule.inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则脚本执行结果（形态一 · 规则注入）：分数增量（hard/medium/soft）+ 否决标记 + 触发说明。
 *
 * <p>约定：增量用于「注入约束」——{@code hard > 0} 视为硬违规、{@code soft > 0} 为软偏好代价；
 * {@code veto = true} 表示该候选被硬否决（等价于不可行）。</p>
 *
 * <p>纯数据对象，无依赖，便于单测与在多条执行路径（内置伪代码 / JSR-223）间统一。</p>
 */
public final class RuleOutcome {

    private long hard;
    private long medium;
    private long soft;
    private boolean veto;
    private final List<String> fired = new ArrayList<>();
    private String error;

    public static RuleOutcome empty() {
        return new RuleOutcome();
    }

    /** 失败结果：如实携带错误信息，绝不静默（上层据此上报「脚本未生效」）。 */
    public static RuleOutcome error(String message) {
        RuleOutcome o = new RuleOutcome();
        o.error = message;
        return o;
    }

    public RuleOutcome addHard(long v) {
        hard += v;
        return this;
    }

    public RuleOutcome addMedium(long v) {
        medium += v;
        return this;
    }

    public RuleOutcome addSoft(long v) {
        soft += v;
        return this;
    }

    public RuleOutcome markVeto() {
        veto = true;
        return this;
    }

    public RuleOutcome fire(String note) {
        if (note != null && !note.isBlank()) fired.add(note);
        return this;
    }

    public long hard() {
        return hard;
    }

    public long medium() {
        return medium;
    }

    public long soft() {
        return soft;
    }

    public boolean veto() {
        return veto;
    }

    public String error() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }

    /** 触发的规则说明（供结果回传/界面展示）。 */
    public List<String> fired() {
        return Collections.unmodifiableList(fired);
    }

    /** 合并另一个结果（分数累加、否决取并、触发说明合并；错误仅保留首次）。 */
    public RuleOutcome merge(RuleOutcome other) {
        if (other == null) {
            return this;
        }
        hard += other.hard;
        medium += other.medium;
        soft += other.soft;
        veto |= other.veto;
        fired.addAll(other.fired);
        if (error == null) {
            error = other.error;
        }
        return this;
    }

    @Override
    public String toString() {
        return "RuleOutcome{hard=" + hard + ", medium=" + medium + ", soft=" + soft
                + ", veto=" + veto + ", fired=" + fired
                + (error != null ? ", error=" + error : "") + '}';
    }
}
