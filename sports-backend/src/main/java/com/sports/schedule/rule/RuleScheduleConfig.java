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
        boolean aiMode,
        FixedLaneAssignment.Policy lanePolicy,
        int conflictBufferMinutes,
        int advanceCount,
        boolean conflictCheckEnabled,
        int aiAdversarialRounds,
        String aiLaneStyle) {

    /** 规则模式标识（config.mode 的合法值，大小写不敏感） */
    public static final String MODE_RULE = "rule";
    /** AI 模式标识（ONNX 全本地推理：算法选择器 + 冲突簇 GNN + 推理时自对抗 + AI 派遣道次） */
    public static final String MODE_AI = "ai";
    /** 优化模式标识 */
    public static final String MODE_OPTIMIZE = "optimize";

    /** 缺省缓冲：直接引用 ConflictService 单一真相源，避免两处 15 漂移导致编排/检测口径分裂 */
    public static final int DEFAULT_CONFLICT_BUFFER = ConflictService.CONFLICT_BUFFER_MIN;
    /** 与 Event.advanceCount 缺省一致 */
    public static final int DEFAULT_ADVANCE_COUNT = 8;
    /** AI 模式缺省：推理时自对抗轮数（0/负数 = 关闭自对抗，只保留 AI 建议与 AI 派遣） */
    public static final int DEFAULT_AI_ROUNDS = 3;
    /** AI 模式缺省款型：由模型输出派遣优先级（无法命中时 ArrangementService 自动回退成绩种子） */
    public static final String DEFAULT_AI_LANE_STYLE = "ai";

    public static final RuleScheduleConfig DEFAULT = new RuleScheduleConfig(
            MODE_OPTIMIZE, false, false, FixedLaneAssignment.Policy.REGISTRATION,
            DEFAULT_CONFLICT_BUFFER, DEFAULT_ADVANCE_COUNT, true,
            DEFAULT_AI_ROUNDS, DEFAULT_AI_LANE_STYLE);

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
        boolean ai = MODE_AI.equals(mode);
        return new RuleScheduleConfig(
                rule ? MODE_RULE : (ai ? MODE_AI : MODE_OPTIMIZE),
                rule,
                ai,
                "performance".equalsIgnoreCase(str(config.get("ruleLanePolicy"), ""))
                        ? FixedLaneAssignment.Policy.PERFORMANCE
                        : FixedLaneAssignment.Policy.REGISTRATION,
                intVal(config.get("ruleConflictBufferMinutes"), DEFAULT_CONFLICT_BUFFER),
                intVal(config.get("ruleAdvanceCount"), DEFAULT_ADVANCE_COUNT),
                boolVal(config.get("ruleConflictCheckEnabled"), true),
                intVal(config.get("aiAdversarialRounds"), DEFAULT_AI_ROUNDS),
                str(config.get("aiLaneStyle"), DEFAULT_AI_LANE_STYLE));
    }

    /** 规则模式专属键（供前端文档与日志观测） */
    public static final List<String> KNOWN_KEYS = List.of(
            "mode", "ruleLanePolicy", "ruleConflictBufferMinutes",
            "ruleAdvanceCount", "ruleConflictCheckEnabled",
            "aiAdversarialRounds", "aiLaneStyle");

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
