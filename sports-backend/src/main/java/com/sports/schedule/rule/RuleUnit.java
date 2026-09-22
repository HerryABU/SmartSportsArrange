package com.sports.schedule.rule;

import com.sports.schedule.opt.solver.Placement;

import java.util.List;
import com.sports.schedule.opt.solver.ScheduleUnit;
import com.sports.service.schedule.ScheduleService;

/**
 * 规则模式的编排单元 DTO（公共、不可变）。
 *
 * <p>与求解层的 {@code ScheduleUnit} 字段对齐（避免 ScheduleService 私有内部类泄漏到规则层），
 * 由服务层适配构造，规则层只读。一个单元 = 项目 × 年级。</p>
 */
public record RuleUnit(
        String key,                 // 全局唯一键（与服务层单元一一对应）
        Long eventId,
        String eventName,
        String grade,
        boolean track,
        String poolLabel,           // 并发池：径赛 / 田赛 / 专用场地池
        String groupKey,            // 田赛分组（null = 普通单元）
        int interval,               // 项目间隔（分钟）
        int rawDuration,            // 真实估算用时
        int minDuration,            // 时长下限（压缩红线）
        long[] athletes,            // 参赛运动员 ID
        List<Placement> candidates  // 本池候选位置（由 placementsOf 预生成）
) {
    public RuleUnit {
        athletes = athletes == null ? new long[0] : athletes.clone();
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
