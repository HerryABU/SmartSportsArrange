package com.sports.schedule.rule;

import com.sports.schedule.rule.grouping.FixedLaneAssignment.Policy;
import com.sports.service.arrange.ConflictService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.sports.schedule.rule.grouping.FixedLaneAssignment;

/**
 * 规则配置解析：缺省向后兼容（不传 mode = 优化模式），非法值取默认。
 */
@DisplayName("RuleScheduleConfig 配置解析")
class RuleScheduleConfigTest {

    @Test
    @DisplayName("null / 空 config → 优化模式（既有行为完全不变）")
    void nullConfigDefaultsToOptimize() {
        assertEquals(RuleScheduleConfig.DEFAULT, RuleScheduleConfig.from(null));
        assertFalse(RuleScheduleConfig.from(null).ruleMode());
        assertFalse(RuleScheduleConfig.from(new HashMap<>()).ruleMode());
    }

    @Test
    @DisplayName("mode=rule（大小写不敏感到规则模式）")
    void ruleModeDetection() {
        assertTrue(RuleScheduleConfig.from(Map.of("mode", "rule")).ruleMode());
        assertTrue(RuleScheduleConfig.from(Map.of("mode", "RULE")).ruleMode());
        assertTrue(RuleScheduleConfig.from(Map.of("mode", "Rule")).ruleMode());
        assertFalse(RuleScheduleConfig.from(Map.of("mode", "optimize")).ruleMode());
        assertFalse(RuleScheduleConfig.from(Map.of("mode", "whatever")).ruleMode());
    }

    @Test
    @DisplayName("规则参数解析：分道策略 / 兼项缓冲 / 录取人数 / 冲突检查开关")
    void ruleParameters() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("mode", "rule");
        cfg.put("ruleLanePolicy", "performance");
        cfg.put("ruleConflictBufferMinutes", 20);
        cfg.put("ruleAdvanceCount", 6);
        cfg.put("ruleConflictCheckEnabled", false);
        RuleScheduleConfig rc = RuleScheduleConfig.from(cfg);
        assertTrue(rc.ruleMode());
        assertEquals(Policy.PERFORMANCE, rc.lanePolicy());
        assertEquals(20, rc.conflictBufferMinutes());
        assertEquals(6, rc.advanceCount());
        assertFalse(rc.conflictCheckEnabled());
    }

    @Test
    @DisplayName("数值容错：字符串数字可解析，垃圾值取默认")
    void numericTolerance() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("mode", "rule");
        cfg.put("ruleConflictBufferMinutes", "25");
        cfg.put("ruleAdvanceCount", "abc");
        RuleScheduleConfig rc = RuleScheduleConfig.from(cfg);
        assertEquals(25, rc.conflictBufferMinutes());
        assertEquals(RuleScheduleConfig.DEFAULT_ADVANCE_COUNT, rc.advanceCount());
    }

    @Test
    @DisplayName("mode=ai → AI 模式：既不是规则模式，也不退化成优化模式（三态可区分）")
    void aiModeDetection() {
        RuleScheduleConfig ai = RuleScheduleConfig.from(Map.of("mode", "ai"));
        assertTrue(ai.aiMode());
        assertFalse(ai.ruleMode(), "AI 模式必须走求解链，不能被当成规则模式跳过求解器");
        assertEquals(RuleScheduleConfig.MODE_AI, ai.mode());
        assertEquals(RuleScheduleConfig.DEFAULT_AI_LANE_STYLE, ai.aiLaneStyle());
        assertEquals(RuleScheduleConfig.DEFAULT_AI_ROUNDS, ai.aiAdversarialRounds());

        assertTrue(RuleScheduleConfig.from(Map.of("mode", "AI")).aiMode(), "大小写不敏感");
        assertTrue(RuleScheduleConfig.from(Map.of("mode", "Ai")).aiMode());
        assertFalse(RuleScheduleConfig.from(Map.of("mode", "rule")).aiMode());
        assertFalse(RuleScheduleConfig.from(Map.of("mode", "optimize")).aiMode());
        assertFalse(RuleScheduleConfig.DEFAULT.aiMode());
        assertFalse(RuleScheduleConfig.from(null).aiMode());
    }

    @Test
    @DisplayName("AI 模式专属参数：自对抗轮数 / 道次款型可显式指定，垃圾值回落默认")
    void aiParameters() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("mode", "ai");
        cfg.put("aiAdversarialRounds", 6);
        cfg.put("aiLaneStyle", "ai");
        RuleScheduleConfig rc = RuleScheduleConfig.from(cfg);
        assertTrue(rc.aiMode());
        assertEquals(6, rc.aiAdversarialRounds());
        assertEquals("ai", rc.aiLaneStyle());

        Map<String, Object> bad = new HashMap<>();
        bad.put("mode", "ai");
        bad.put("aiAdversarialRounds", "abc");
        RuleScheduleConfig r2 = RuleScheduleConfig.from(bad);
        assertEquals(RuleScheduleConfig.DEFAULT_AI_ROUNDS, r2.aiAdversarialRounds());
        // AI 模式必须保留规则模式那套参数（规则模式是 AI 模式排除法之外的另一分支，互不干扰）
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("mode", "ai");
        mixed.put("ruleConflictBufferMinutes", 18);
        mixed.put("ruleAdvanceCount", 5);
        RuleScheduleConfig r3 = RuleScheduleConfig.from(mixed);
        assertEquals(18, r3.conflictBufferMinutes());
        assertEquals(5, r3.advanceCount());
        assertTrue(r3.aiMode());
    }

    @Test
    @DisplayName("兼项缓冲缺省值与 ConflictService 单一真相源对齐（防编排/检测口径漂移）")
    void conflictBufferSingleSource() {
        assertEquals(ConflictService.CONFLICT_BUFFER_MIN, RuleScheduleConfig.DEFAULT_CONFLICT_BUFFER,
                "规则模式缺省缓冲必须等于检测端 CONFLICT_BUFFER_MIN，否则『排时不冲突、检出来冲突』");
        assertEquals(ConflictService.CONFLICT_BUFFER_MIN,
                RuleScheduleConfig.from(null).conflictBufferMinutes(),
                "未显式传 ruleConflictBufferMinutes 时必须回落到同一真相源");
    }
}
