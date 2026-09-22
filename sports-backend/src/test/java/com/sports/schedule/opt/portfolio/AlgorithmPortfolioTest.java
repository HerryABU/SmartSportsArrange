package com.sports.schedule.opt.portfolio;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 算法选择策略的验证。
 *
 * <p>「什么数据该选什么算法」这件事必须能被独立验证——本类是纯函数、无 Spring 依赖，
 * 所以可以直接对着策略断言，不用启动求解器（求解一次要几秒，而策略判断是毫秒级的）。</p>
 */
@DisplayName("算法组合与选择")
class AlgorithmPortfolioTest {

    @Test
    @DisplayName("容量紧张（紧张度 ≥ 1）时必须选「允许暂时变差」的算法")
    void tightCapacityChoosesSimulatedAnnealing() {
        AlgorithmPortfolio.Features tight =
                new AlgorithmPortfolio.Features(20, 2000, 1000, 2.0, 0.05, 50);

        List<AlgorithmPortfolio.Plan> plans = AlgorithmPortfolio.planFor(tight, 3000);

        assertEquals(LocalSearchType.SIMULATED_ANNEALING, plans.get(0).type(),
                "容量不足时解空间遍布「用压缩换时间」的深坑，只走下坡的算法出不来");
    }

    @Test
    @DisplayName("容量宽裕时选禁忌搜索（记住走过的路，避免循环）")
    void looseCapacityChoosesTabuSearch() {
        AlgorithmPortfolio.Features loose =
                new AlgorithmPortfolio.Features(20, 500, 2000, 0.25, 0.05, 50);

        List<AlgorithmPortfolio.Plan> plans = AlgorithmPortfolio.planFor(loose, 3000);

        assertEquals(LocalSearchType.TABU_SEARCH, plans.get(0).type());
    }

    @Test
    @DisplayName("兼项密集（占比 ≥ 15%）时追加多邻域候选")
    void denseMultiEventAddsDiversifiedNeighborhood() {
        AlgorithmPortfolio.Features dense =
                new AlgorithmPortfolio.Features(20, 500, 2000, 0.25, 0.30, 50);

        List<AlgorithmPortfolio.Plan> plans = AlgorithmPortfolio.planFor(dense, 3000);

        assertEquals(3, plans.size(), "兼项密集应多出一个候选（多邻域）");
        assertTrue(plans.stream().anyMatch(p -> p.type() == LocalSearchType.DIVERSIFIED_LATE_ACCEPTANCE));
    }

    @Test
    @DisplayName("每轮候选的种子互不相同——否则「多起点」退化成同一起点跑三遍")
    void eachCandidateUsesDistinctSeed() {
        AlgorithmPortfolio.Features f =
                new AlgorithmPortfolio.Features(20, 500, 2000, 0.25, 0.30, 50);

        List<AlgorithmPortfolio.Plan> plans = AlgorithmPortfolio.planFor(f, 3000);

        long distinct = plans.stream().map(AlgorithmPortfolio.Plan::seed).distinct().count();
        assertEquals(plans.size(), distinct, "候选种子必须彼此不同，多起点才有意义");
    }

    @Test
    @DisplayName("特征提取：需求/供给/紧张度/兼项占比全部可手算校验")
    void featuresAreExtractedAsExpected() {
        // 需求：100 + 200 = 300
        // 运动员：1 只报 1 项、2 报 2 项 → 兼项人数 1 / 总人数 2 = 0.5
        // 供给：所有候选位置同属一个并发位（同一 binKey）→ 只算一次 210 分钟
        List<Placement> placements = new ArrayList<>();
        for (int start = 480; start <= 690; start += 10) {
            placements.add(new Placement("径赛", 0, 0, 1, "2026-01-01", "上午", "田径场",
                    start, 480, 210));
        }
        List<ScheduleUnit> units = List.of(
                unit("a", 100, new long[]{1L, 2L}, placements),
                unit("b", 200, new long[]{2L}, placements));

        AlgorithmPortfolio.Features f = AlgorithmPortfolio.extract(units, placements);

        assertEquals(2, f.unitCount());
        assertEquals(300, f.demandMinutes());
        assertEquals(210, f.supplyMinutes(), "同一并发位的多个起点只算一次容量");
        assertEquals(1.43, f.tensionRatio(), 0.01);
        assertEquals(0.5, f.multiEventAthleteRatio(), 0.01);
        assertEquals(2, f.athleteCount());
    }

    @Test
    @DisplayName("空输入安全：返回可用的默认候选，不抛异常")
    void emptyInputIsSafe() {
        AlgorithmPortfolio.Features f = AlgorithmPortfolio.extract(List.of(), List.of());

        assertEquals(0, f.unitCount());
        assertEquals(0.0, f.tensionRatio(), 0.001);
        assertEquals(1, AlgorithmPortfolio.planFor(f, 3000).size());
    }

    private static ScheduleUnit unit(String key, int rawDuration, long[] athletes, List<Placement> cands) {
        return new ScheduleUnit(key, 1L, "项目" + key, "高一", true, "径赛", null, 5,
                rawDuration, 10, athletes, List.of(rawDuration), cands);
    }
}
