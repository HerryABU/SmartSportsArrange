package com.sports.schedule.rule;

import java.util.List;
import java.util.Map;
import com.sports.entity.event.Event;
import com.sports.schedule.rule.grouping.FixedLaneAssignment;
import com.sports.service.arrange.ConflictService;

/**
 * 规则模式参数配置（不可变）。
 *
 * <p>竞品（豪杰/索美）的「参数配置 → 生成结果」流程载体：录取人数、赛次参数、
 * 兼项检查阈值、分道策略。从编排请求的 config Map 解析，未知键忽略（向后兼容）。</p>
 *
 * <p>解析规则：</p>
 * <ul>
 *   <li>{@code mode}："rule"（大小写不敏感）→ 规则模式；其余/缺省 → 优化模式（保持既有行为）。</li>
 *   <li>{@code ruleLanePolicy}："registration" | "performance"，缺省 registration（预赛首轮语义）。</li>
 *   <li>{@code ruleConflictBufferMinutes}：兼项检查阈值（分钟），缺省 15（与 ConflictService 对齐）。</li>
 *   <li>{@code ruleAdvanceCount}：每组录取晋级人数，缺省 8（与 Event.advanceCount 默认一致）。</li>
 * </ul>
 */
public record RuleScheduleConfig(
        String mode,
        boolean ruleMode,
        FixedLaneAssignment.Policy lanePolicy,
        int conflictBufferMinutes,
        int advanceCount,
        boolean conflictCheckEnabled) {

    /** 规则模式标识（config.mode 的合法值，大小写不敏感） */
    public static final String MODE_RULE = "rule";
    /** 优化模式标识 */
    public static final String MODE_OPTIMIZE = "optimize";

    /** 与 ConflictService.CONFLICT_BUFFER_MIN 保持一致的缺省缓冲 */
    public static final int DEFAULT_CONFLICT_BUFFER = 15;
    /** 与 Event.advanceCount 缺省一致 */
    public static final int DEFAULT_ADVANCE_COUNT = 8;

    public static final RuleScheduleConfig DEFAULT = new RuleScheduleConfig(
            MODE_OPTIMIZE, false, FixedLaneAssignment.Policy.REGISTRATION,
            DEFAULT_CONFLICT_BUFFER, DEFAULT_ADVANCE_COUNT, true);

    /**
     * 从编排请求 config 解析（不修改原 Map）。
     *
     * @param config 编排请求体（可为 null）
     * @return 已解析的不可变配置；不识别/缺省字段一律取默认值
     */
    public static RuleScheduleConfig from(Map<String, Object> config) {
        if (config == null) return DEFAULT;
        String mode = str(config.get("mode"), MODE_OPTIMIZE).trim().toLowerCase();
        boolean rule = MODE_RULE.equals(mode);
        return new RuleScheduleConfig(
                rule ? MODE_RULE : MODE_OPTIMIZE,
                rule,
                "performance".equalsIgnoreCase(str(config.get("ruleLanePolicy"), ""))
                        ? FixedLaneAssignment.Policy.PERFORMANCE
                        : FixedLaneAssignment.Policy.REGISTRATION,
                intVal(config.get("ruleConflictBufferMinutes"), DEFAULT_CONFLICT_BUFFER),
                intVal(config.get("ruleAdvanceCount"), DEFAULT_ADVANCE_COUNT),
                boolVal(config.get("ruleConflictCheckEnabled"), true));
    }

    /** 规则模式专属键（供前端文档与日志观测） */
    public static final List<String> KNOWN_KEYS = List.of(
            "mode", "ruleLanePolicy", "ruleConflictBufferMinutes",
            "ruleAdvanceCount", "ruleConflictCheckEnabled");

    private static String str(Object v, String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    private static int intVal(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return dflt;
    }

    private static boolean boolVal(Object v, boolean dflt) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return dflt;
    }
}
