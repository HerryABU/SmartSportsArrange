package com.sports.schedule.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 理论下界评估的验证。
 *
 * <p>下界是「任何可行解都不可能优于它」的数学事实，因此测试必须直接对着**手算的期望值**断言，
 * 而不是对着实现跑出来的数——否则实现算错了、测试跟着错，等于没测。</p>
 */
@DisplayName("理论下界评估器")
class LowerBoundEstimatorTest {

    private final LowerBoundEstimator estimator = new LowerBoundEstimator();

    @Test
    @DisplayName("容量下界：需求 ÷ (并发位 × 单日容量) 向上取整")
    void capacityBoundIsCeilOfDemandOverSupply() {
        // 田赛 2 并发位、单日 420 分钟 → 单日供给 840；需求 2000 分钟 → ceil(2000/840) = 3 天
        List<LowerBoundEstimator.Item> items = List.of(
                new LowerBoundEstimator.Item("田赛", 1000, 0, Set.of()),
                new LowerBoundEstimator.Item("田赛", 1000, 0, Set.of()));

        LowerBoundEstimator.Assessment a = estimator.assess(items, Map.of("田赛", 2), 420, 5, 2000);

        assertEquals(3, a.capacityBoundDays(), "ceil(2000 / (2×420)) = 3");
        assertEquals(3, a.lowerBoundDays());
        assertEquals("场地容量", a.bindingResource().substring(0, 4));
    }

    @Test
    @DisplayName("运动员下界：同一人的项目必须串行，与场地无关")
    void athleteBoundIsAboutSerialCapacityOfOnePerson() {
        // 运动员 7 兼报 3 项共 900 分钟，单日 420 → 他至少需要 ceil(900/420)=3 天
        // 场地给再多并发位也救不了——他本人不能分身
        Set<Long> busy = Set.of(7L);
        List<LowerBoundEstimator.Item> items = List.of(
                new LowerBoundEstimator.Item("径赛", 300, 0, busy),
                new LowerBoundEstimator.Item("田赛", 300, 0, busy),
                new LowerBoundEstimator.Item("田赛", 300, 0, busy));

        LowerBoundEstimator.Assessment a = estimator.assess(items, Map.of("径赛", 99, "田赛", 99), 420, 1, 900);

        assertEquals(3, a.athleteBoundDays(), "ceil(900/420) = 3");
        assertEquals(3, a.lowerBoundDays(), "场地并发位再多也压不到 3 天以下");
        assertTrue(a.bindingResource().startsWith("运动员"), "瓶颈应指向运动员：" + a.bindingResource());
    }

    @Test
    @DisplayName("装箱下界：项间间隔也算进真实占用（忽略间隔会低估下界）")
    void packingBoundCountsIntervals() {
        // 3 项各 400 分钟 + 每项间隔 100 分钟 → 占用 1500；1 并发位、单日 420 → ceil(1500/420)=4 天
        List<LowerBoundEstimator.Item> items = List.of(
                new LowerBoundEstimator.Item("径赛", 400, 100, Set.of()),
                new LowerBoundEstimator.Item("径赛", 400, 100, Set.of()),
                new LowerBoundEstimator.Item("径赛", 400, 100, Set.of()));

        LowerBoundEstimator.Assessment a = estimator.assess(items, Map.of("径赛", 1), 420, 4, 1200);

        assertEquals(4, a.packingBoundDays(), "ceil((1200+300)/420) = 4");
        assertEquals(4, a.lowerBoundDays());
    }

    @Test
    @DisplayName("gap：实际天数贴住下界时 gap = 0（此时再优化只能减少压缩量）")
    void gapIsZeroWhenActualMeetsLowerBound() {
        List<LowerBoundEstimator.Item> items = List.of(
                new LowerBoundEstimator.Item("径赛", 900, 0, Set.of()));

        LowerBoundEstimator.Assessment a = estimator.assess(items, Map.of("径赛", 1), 420, 3, 900);

        assertEquals(3, a.lowerBoundDays());
        assertEquals(3, a.actualDays());
        assertEquals(0.0, a.dayGapPercent(), 0.001);
    }

    @Test
    @DisplayName("gap 与压缩比：可量化「当前方案还有多少改进空间」")
    void gapAndCompressionAreQuantified() {
        List<LowerBoundEstimator.Item> items = List.of(
                new LowerBoundEstimator.Item("径赛", 1000, 0, Set.of()));

        // 实际排了 4 天（下界 3 天）→ gap = (4-3)/3 = 33.3%；实给 800 / 需求 1000 → 压缩比 80%
        LowerBoundEstimator.Assessment a = estimator.assess(items, Map.of("径赛", 1), 420, 4, 800);

        assertEquals(3, a.lowerBoundDays());
        assertEquals(33.3, a.dayGapPercent(), 0.05);
        assertEquals(80.0, a.compressionPercent(), 0.05);
    }

    @Test
    @DisplayName("空输入与未配置时段：返回 0 而不是抛异常")
    void emptyInputIsSafe() {
        LowerBoundEstimator.Assessment a = estimator.assess(List.of(), Map.of(), 0, 0, 0);

        assertEquals(0, a.lowerBoundDays());
        assertEquals(0.0, a.dayGapPercent(), 0.001);
        assertNotNull(a.bindingResource());
    }
}
