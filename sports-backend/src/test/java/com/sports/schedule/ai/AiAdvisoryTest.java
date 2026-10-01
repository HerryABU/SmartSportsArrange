package com.sports.schedule.ai;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.portfolio.AlgorithmPortfolio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 建议与算法组合层「AI 优先、规则回退」的验证。
 */
@DisplayName("AI 编排建议与算法组合")
class AiAdvisoryTest {

    @Test
    @DisplayName("置信度：取消概率偏离 0.5 足够远才可信")
    void confidentThreshold() {
        assertTrue(new AiAdvisory(AiAdvisory.Strategy.CANCEL_PATH, 0.9, null).confident());
        assertTrue(new AiAdvisory(AiAdvisory.Strategy.HARD_SOLVE, 0.1, null).confident());
        assertFalse(new AiAdvisory(AiAdvisory.Strategy.CANCEL_PATH, 0.55, null).confident(),
                "接近 0.5 应视为低置信");
    }

    @Test
    @DisplayName("高置信 AI 建议可覆盖规则阈值分支")
    void confidentAiOverridesRuleBranch() {
        AlgorithmPortfolio.Features loose =
                new AlgorithmPortfolio.Features(20, 500, 2000, 0.25, 0.05, 50);
        AiAdvisory cancel = new AiAdvisory(AiAdvisory.Strategy.CANCEL_PATH, 0.9, null);

        List<AlgorithmPortfolio.Plan> plans = AlgorithmPortfolio.planFor(loose, 3000, cancel);

        assertEquals(LocalSearchType.SIMULATED_ANNEALING, plans.get(0).type(),
                "AI 判定需取消路径（容量紧张型），应优先允许暂时变差的算法，即使规则阈值判为宽松");
    }

    @Test
    @DisplayName("低置信 AI 建议回退规则阈值分支")
    void lowConfidenceFallsBackToRule() {
        AlgorithmPortfolio.Features loose =
                new AlgorithmPortfolio.Features(20, 500, 2000, 0.25, 0.05, 50);
        AiAdvisory uncertain = new AiAdvisory(AiAdvisory.Strategy.CANCEL_PATH, 0.55, null);

        List<AlgorithmPortfolio.Plan> plans = AlgorithmPortfolio.planFor(loose, 3000, uncertain);

        assertEquals(LocalSearchType.TABU_SEARCH, plans.get(0).type(),
                "低置信时不采纳 AI，回退到 tensionRatio=0.25 的规则分支（禁忌搜索）");
    }

    @Test
    @DisplayName("null 建议 = 纯规则路径，行为与旧版一致")
    void nullAdvisoryEqualsRule() {
        AlgorithmPortfolio.Features tight =
                new AlgorithmPortfolio.Features(20, 2000, 1000, 2.0, 0.05, 50);
        List<AlgorithmPortfolio.Plan> withAi = AlgorithmPortfolio.planFor(tight, 3000, null);
        List<AlgorithmPortfolio.Plan> noAi = AlgorithmPortfolio.planFor(tight, 3000);

        assertEquals(noAi.size(), withAi.size());
        for (int i = 0; i < noAi.size(); i++) {
            assertEquals(noAi.get(i).type(), withAi.get(i).type());
        }
    }
}
