package com.sports.service;

import com.sports.entity.system.SystemConfig;
import com.sports.repository.system.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import com.sports.service.system.SystemService;

/**
 * 系统配置服务测试。
 * 覆盖：积分规则默认兜底、编排规则默认兜底、已保存配置的深度合并覆盖。
 */
@ExtendWith(MockitoExtension.class)
class SystemServiceTest {

    @Mock private SystemConfigRepository systemConfigRepository;
    @Mock private DataSource dataSource;

    @InjectMocks private SystemService systemService;

    @Test
    @SuppressWarnings("unchecked")
    void getScoringRule_returnsDefaultsWhenNoConfig() {
        when(systemConfigRepository.findByConfigKey("scoring_rule")).thenReturn(Optional.empty());
        Map<String, Object> rule = systemService.getScoringRule();
        assertNotNull(rule.get("rank_scores"));
        Map<String, Object> scores = (Map<String, Object>) rule.get("rank_scores");
        assertEquals(9.0, ((Number) scores.get("1")).doubleValue(), 0.001);
        assertEquals(1.0, ((Number) scores.get("8")).doubleValue(), 0.001);
        assertEquals("class", rule.get("team_score_type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getScoringRule_mergesSavedConfig() {
        // 保存的 JSON 只覆盖 rank_scores.1 = 99，其余默认应保留
        SystemConfig cfg = SystemConfig.builder().configKey("scoring_rule")
                .configValue("{\"rank_scores\":{\"1\":99}}").build();
        when(systemConfigRepository.findByConfigKey("scoring_rule")).thenReturn(Optional.of(cfg));

        Map<String, Object> rule = systemService.getScoringRule();
        Map<String, Object> scores = (Map<String, Object>) rule.get("rank_scores");
        assertEquals(99.0, ((Number) scores.get("1")).doubleValue(), 0.001);
        assertEquals(7.0, ((Number) scores.get("2")).doubleValue(), 0.001); // 默认值保留
        assertEquals("class", rule.get("team_score_type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getArrangeRule_returnsDefaults() {
        when(systemConfigRepository.findByConfigKey("arrange_rule")).thenReturn(Optional.empty());
        Map<String, Object> rule = systemService.getArrangeRule();
        assertTrue(rule.containsKey("soft_constraints"));
        assertTrue(rule.containsKey("algorithm_params"));
        Map<String, Object> params = (Map<String, Object>) rule.get("algorithm_params");
        assertEquals(30, params.get("timeout_seconds"));
        // L1 款型持久化：默认款型为 class（班级均衡）
        assertEquals("class", rule.get("l1_rule"));
    }

    /** 已保存的 L1 款型应被读出（款型持久化：记住上次选的款型）。 */
    @Test
    void getArrangeRule_mergesSavedL1Rule() {
        SystemConfig cfg = SystemConfig.builder().configKey("arrange_rule")
                .configValue("{\"l1_rule\":\"snakeSeed\"}").build();
        when(systemConfigRepository.findByConfigKey("arrange_rule")).thenReturn(Optional.of(cfg));
        Map<String, Object> rule = systemService.getArrangeRule();
        assertEquals("snakeSeed", rule.get("l1_rule"));
    }
}
