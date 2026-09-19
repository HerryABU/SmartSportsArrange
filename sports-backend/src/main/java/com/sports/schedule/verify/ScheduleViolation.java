package com.sports.schedule.verify;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条赛程违规——自检结果的最小单元。
 *
 * <p>刻意做成<b>结构化</b>而不是一句人话：编排人员需要的不是「有没有问题」这个布尔值，
 * 而是「哪个项目、和谁、什么时间、什么类型、有多严重、怎么改」。有了这五个字段，
 * 前端才能把违规<b>高亮定位</b>到具体行、并给出可操作的建议，而不是丢一个「校验失败」。</p>
 */
@Getter
public class ScheduleViolation {

    /** 阻塞级：违反硬约束，赛程不可执行，必须处理（现场会直接卡死的那种） */
    public static final String LEVEL_BLOCKER = "blocker";
    /** 告警级：赛程可执行，但违反赛会惯例或存在执行风险，建议人工确认 */
    public static final String LEVEL_WARNING = "warning";

    // ==================== 类型码（机器可读，供前端图标/高亮映射） ====================

    /** 时间自洽性错误：结束不晚于开始 */
    public static final String TIME_INCONSISTENT = "TIME_INCONSISTENT";
    /** 同一场地同一时段被两个项目同时占用 */
    public static final String VENUE_OVERLAP = "VENUE_OVERLAP";
    /** 同一运动员被排到赶不上的两个项目（含赶场缓冲） */
    public static final String ATHLETE_CLASH = "ATHLETE_CLASH";
    /** 同组项目未同时开赛（田赛集中裁判/器材的惯例被破坏） */
    public static final String GROUP_NOT_SIMULTANEOUS = "GROUP_NOT_SIMULTANEOUS";
    /** 编排表里应有的项目在赛程表中找不到（被静默丢弃） */
    public static final String NOT_ASSIGNED = "NOT_ASSIGNED";
    /** 项目时长被压到低于可执行下限 */
    public static final String DURATION_BELOW_FLOOR = "DURATION_BELOW_FLOOR";

    private final String type;
    private final String level;
    /** 主体（出问题的项目） */
    private final String subject;
    /** 相关方（撞上的另一个项目 / 运动员），可为 null */
    private final String related;
    /** 发生时间（第几天 + 时段 + 起止） */
    private final String when;
    private final String detail;
    private final String suggestion;

    public ScheduleViolation(String type, String level, String subject, String related,
                             String when, String detail, String suggestion) {
        this.type = type;
        this.level = level;
        this.subject = subject;
        this.related = related;
        this.when = when;
        this.detail = detail;
        this.suggestion = suggestion;
    }

    public boolean isBlocker() {
        return LEVEL_BLOCKER.equals(level);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("level", level);
        m.put("subject", subject);
        m.put("related", related);
        m.put("when", when);
        m.put("detail", detail);
        m.put("suggestion", suggestion);
        return m;
    }

    @Override
    public String toString() {
        return "[" + level + "] " + type + " " + subject
                + (related == null ? "" : " × " + related)
                + (when == null ? "" : " @" + when)
                + "：" + detail;
    }
}
