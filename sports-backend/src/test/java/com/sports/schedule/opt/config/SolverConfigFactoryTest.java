package com.sports.schedule.opt.config;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import ai.timefold.solver.core.config.solver.PreviewFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SolverConfigFactory} 构造的求解配置必须启用
 * {@link PreviewFeature#DIVERSIFIED_LATE_ACCEPTANCE} 预览特性。
 *
 * <p>未启用时，Timefold 在求解构建期抛 {@code IllegalStateException}（预览特性未开启），
 * 被上层 {@code solve()} 的 catch 静默吞掉后降级为贪心编排——症状隐蔽、求解质量下降。
 * 本测试守护该修复，确保 {@code DIVERSIFIED_LATE_ACCEPTANCE} 候选算法可正常使用。</p>
 */
@DisplayName("求解器配置工厂")
class SolverConfigFactoryTest {

    @Test
    @DisplayName("构造配置必须启用 DIVERSIFIED_LATE_ACCEPTANCE 预览特性")
    void configEnablesDiversifiedLateAcceptancePreviewFeature() {
        var config = SolverConfigFactory.config(Duration.ofSeconds(30), LocalSearchType.TABU_SEARCH, 1L);
        Set<PreviewFeature> enabled = config.getEnablePreviewFeatureSet();
        assertNotNull(enabled, "预览特性集合不应为空");
        assertTrue(enabled.contains(PreviewFeature.DIVERSIFIED_LATE_ACCEPTANCE),
                "必须启用 DIVERSIFIED_LATE_ACCEPTANCE 预览特性，否则其候选算法会静默降级为贪心");
    }
}
