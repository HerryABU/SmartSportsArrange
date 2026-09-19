package com.sports.schedule.rule;

import com.sports.schedule.rule.FixedLaneAssignment.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
}
