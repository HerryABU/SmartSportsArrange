package com.sports.schedule.verify.checks;

/**
 * 校验过程的可变计数容器：记录真实比对的「场地对 / 运动员对」次数。
 *
 * <p>供审计判定抗「空白通过」——若行数很多而比对数为 0，看报告的人应据此<b>质疑</b>
 * 结论，而不是相信一个空白的「通过」。</p>
 */
public final class VerifyCounters {

    public long venuePairs;
    public long athletePairs;

    public VerifyCounters() {
    }
}
