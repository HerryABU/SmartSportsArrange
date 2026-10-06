package com.sports.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 筛选链口径测试：排名顺序（排序方向）与晋级名额（人数 / 百分比）。
 *
 * <p>背景：这两个口径原先硬编码在 {@code ArrangementService} 里——排序方向写死升序，
 * 于是<b>田赛（成绩是距离/高度，越大越好）会把成绩最差的先"晋级"</b>。
 * 抽成 {@link QualifyPolicy} 后在此钉住语义，避免再次退化成写死。</p>
 */
@DisplayName("筛选链：排名顺序与晋级名额口径")
class QualifyPolicyTest {

    // ==================== 排名顺序 ====================

    @Test
    @DisplayName("排名顺序归一化：只认 1 为降序，其余（含 null）一律升序——不改存量行为")
    void normalizeRankOrder() {
        assertEquals(QualifyPolicy.ORDER_DESC, QualifyPolicy.normalizeRankOrder(1));
        assertEquals(QualifyPolicy.ORDER_ASC, QualifyPolicy.normalizeRankOrder(0));
        // 存量数据没有该字段（null）⇒ 必须落回历史口径「升序」，否则加字段会悄悄改写已有晋级名单
        assertEquals(QualifyPolicy.ORDER_ASC, QualifyPolicy.normalizeRankOrder(null));
        // 越界值按升序兜底，不抛异常
        assertEquals(QualifyPolicy.ORDER_ASC, QualifyPolicy.normalizeRankOrder(2));
        assertEquals(QualifyPolicy.ORDER_ASC, QualifyPolicy.normalizeRankOrder(-1));

        assertTrue(QualifyPolicy.isDesc(1));
        assertFalse(QualifyPolicy.isDesc(0));
        assertFalse(QualifyPolicy.isDesc(null));
        assertEquals("从大到小", QualifyPolicy.describeRankOrder(1));
        assertEquals("从小到大", QualifyPolicy.describeRankOrder(0));
    }

    @Test
    @DisplayName("升序：成绩越小越靠前（径赛时间）")
    void ascendingOrder() {
        Comparator<Integer> c = QualifyPolicy.byResult(false, Integer::doubleValue);
        List<Integer> list = new ArrayList<>(List.of(3, 1, 2));
        list.sort(c);
        assertEquals(List.of(1, 2, 3), list);
    }

    @Test
    @DisplayName("降序：成绩越大越靠前（田赛距离/高度）")
    void descendingOrder() {
        Comparator<Integer> c = QualifyPolicy.byResult(true, Integer::doubleValue);
        List<Integer> list = new ArrayList<>(List.of(3, 1, 2));
        list.sort(c);
        assertEquals(List.of(3, 2, 1), list);
    }

    @Test
    @DisplayName("⚠️ 成绩缺失者两种方向下都排最后——reversed() 会把 nullsLast 反转成「null 最前」")
    void nullAlwaysLast() {
        // 注意：这里必须用 Arrays.asList —— List.of(...) 不允许 null 元素，会直接抛 NPE
        List<Integer> asc = new ArrayList<>(Arrays.asList(2, null, 1));
        asc.sort(QualifyPolicy.byResult(false, v -> v == null ? null : v.doubleValue()));
        assertEquals(Arrays.asList(1, 2, null), asc, "升序时 null 必须在最后");

        List<Integer> desc = new ArrayList<>(Arrays.asList(2, null, 1));
        desc.sort(QualifyPolicy.byResult(true, v -> v == null ? null : v.doubleValue()));
        assertEquals(Arrays.asList(2, 1, null), desc,
                "降序时 null 同样必须在最后——若用 comparator.reversed()，没成绩的人会排进晋级区");
    }

    // ==================== 晋级名额 ====================

    @Test
    @DisplayName("接口临时指定的人数优先级最高")
    void requestCountWins() {
        QualifyPolicy.Quota q = QualifyPolicy.resolveQuota(3, 8, 50.0, 20);
        assertEquals(3, q.count());
        assertEquals(QualifyPolicy.QuotaSource.REQUEST, q.source());
    }

    @Test
    @DisplayName("⚠️ 百分比优先于人数——否则新填的百分比会被默认的 8 悄悄顶掉（配了不生效）")
    void percentWinsOverCount() {
        // advanceCount=8 是实体默认值（存量数据人人都是 8），用户填了 30% ⇒ 必须按 30% 走
        QualifyPolicy.Quota q = QualifyPolicy.resolveQuota(null, 8, 30.0, 20);
        assertEquals(6, q.count(), "20 人取前 30% = 6 人");
        assertEquals(QualifyPolicy.QuotaSource.PERCENT, q.source());
    }

    @Test
    @DisplayName("百分比向上取整、至少 1 人、至多全员")
    void percentRoundingAndClamp() {
        // 7×30% = 2.1 → 向上取整 3
        assertEquals(3, QualifyPolicy.resolveQuota(null, 8, 30.0, 7).count());
        // 1 人×10% = 0.1 → 至少 1 人（否则看起来像"没人晋级"）
        assertEquals(1, QualifyPolicy.resolveQuota(null, 8, 10.0, 1).count());
        // 10 人×150% 越界 → 夹到 10，不允许"晋级比报名还多"
        assertEquals(10, QualifyPolicy.resolveQuota(null, 8, 150.0, 10).count());
    }

    @Test
    @DisplayName("百分比未配 → 回退人数；人数也未配 → 兜底 8")
    void fallbackChain() {
        assertEquals(5, QualifyPolicy.resolveQuota(null, 5, null, 20).count());
        assertEquals(QualifyPolicy.QuotaSource.COUNT, QualifyPolicy.resolveQuota(null, 5, null, 20).source());

        assertEquals(8, QualifyPolicy.resolveQuota(null, null, null, 20).count());
        assertEquals(QualifyPolicy.QuotaSource.DEFAULT, QualifyPolicy.resolveQuota(null, null, null, 20).source());

        // 0 / 负数视为未配置
        assertEquals(8, QualifyPolicy.resolveQuota(null, 0, 0.0, 20).count());
        assertEquals(8, QualifyPolicy.resolveQuota(null, -3, -1.0, 20).count());
        assertEquals(8, QualifyPolicy.resolveQuota(0, null, null, 20).count());
    }

    @Test
    @DisplayName("参与人数为 0 时百分比不可折算 → 回退人数")
    void percentNeedsParticipants() {
        QualifyPolicy.Quota q = QualifyPolicy.resolveQuota(null, 4, 30.0, 0);
        assertEquals(4, q.count());
        assertEquals(QualifyPolicy.QuotaSource.COUNT, q.source());
    }

    @Test
    @DisplayName("名额来源有可读文案——「按哪个口径取人」必须能回显")
    void quotaSourceText() {
        assertTrue(QualifyPolicy.describeQuotaSource(QualifyPolicy.QuotaSource.PERCENT, 30.0).contains("30%"));
        assertTrue(QualifyPolicy.describeQuotaSource(QualifyPolicy.QuotaSource.COUNT, null).contains("人数"));
        assertTrue(QualifyPolicy.describeQuotaSource(QualifyPolicy.QuotaSource.DEFAULT, null).contains("兜底"));
        assertTrue(QualifyPolicy.describeQuotaSource(QualifyPolicy.QuotaSource.REQUEST, null).contains("接口"));
        // 整数百分比不显示小数点（30 而不是 30.0），避免回显文案里出现无意义的 .0
        assertFalse(QualifyPolicy.describeQuotaSource(QualifyPolicy.QuotaSource.PERCENT, 30.0).contains("30.0"));
        assertTrue(QualifyPolicy.describeQuotaSource(QualifyPolicy.QuotaSource.PERCENT, 12.5).contains("12.5"));
    }

    // ==================== 双向换算（填一个、另一个算出） ====================

    @Test
    @DisplayName("填人数 → 算出百分比（保留 1 位小数）")
    void convertCountToPercent() {
        assertEquals(30.0, QualifyPolicy.convert(30, 9, null, QualifyPolicy.Edited.COUNT).percent());
        assertEquals(30.0, QualifyPolicy.convert(20, 6, null, QualifyPolicy.Edited.COUNT).percent());
        // 7 人里取 3 → 42.857…% → 42.9
        assertEquals(42.9, QualifyPolicy.convert(7, 3, null, QualifyPolicy.Edited.COUNT).percent());
        // 人数超过基数时百分比封顶 100（不出现 120% 这种表单）
        assertEquals(100.0, QualifyPolicy.convert(10, 15, null, QualifyPolicy.Edited.COUNT).percent());
    }

    @Test
    @DisplayName("填百分比 → 算出人数（向上取整，与真实筛选口径一致）")
    void convertPercentToCount() {
        assertEquals(9, QualifyPolicy.convert(30, null, 30.0, QualifyPolicy.Edited.PERCENT).count());
        assertEquals(6, QualifyPolicy.convert(20, null, 30.0, QualifyPolicy.Edited.PERCENT).count());
        // 7×30% = 2.1 → 3：向上取整，和 resolveQuota 用同一套规则，否则表里的数与真正用的数会不一致
        assertEquals(3, QualifyPolicy.convert(7, null, 30.0, QualifyPolicy.Edited.PERCENT).count());
        // 夹到 [1, base]
        assertEquals(1, QualifyPolicy.convert(1, null, 10.0, QualifyPolicy.Edited.PERCENT).count());
        assertEquals(10, QualifyPolicy.convert(10, null, 150.0, QualifyPolicy.Edited.PERCENT).count());
    }

    @Test
    @DisplayName("基数不可用（既无报名也无上限）时不猜——百分比留空，让前端如实显示「无法折算」")
    void convertWithoutBase() {
        assertNull(QualifyPolicy.convert(0, 5, null, QualifyPolicy.Edited.COUNT).percent());
        assertNull(QualifyPolicy.convert(-1, 5, null, QualifyPolicy.Edited.COUNT).percent());
    }

    @Test
    @DisplayName("基数选择：MAX 在最大值不可用时如实退回实际人数（不静默换基数）")
    void resolveBaseMode() {
        assertEquals(30, QualifyPolicy.resolveBase(QualifyPolicy.BaseMode.MAX, 20, 30));
        assertEquals(20, QualifyPolicy.resolveBase(QualifyPolicy.BaseMode.ACTUAL, 20, 30));
        // 最大报名人数为空 / 0 → 退回实际
        assertEquals(20, QualifyPolicy.resolveBase(QualifyPolicy.BaseMode.MAX, 20, null));
        assertEquals(20, QualifyPolicy.resolveBase(QualifyPolicy.BaseMode.MAX, 20, 0));
    }

    @Test
    @DisplayName("按最大报名人数折算：名额仍不得多于实际参与人数")
    void quotaByMaxBaseNeverExceedsParticipants() {
        // 实际 20 人、上限 30、取 30% → 30×30% = 9（不超 20，正常）
        assertEquals(9, QualifyPolicy.resolveQuota(null, null, 30.0, 20, 30,
                QualifyPolicy.BaseMode.MAX).count());
        // 取 80% → 30×80% = 24，但实际只有 20 人参与 → 夹到 20（不能「晋级」出不存在的人）
        assertEquals(20, QualifyPolicy.resolveQuota(null, null, 80.0, 20, 30,
                QualifyPolicy.BaseMode.MAX).count());
        // 同样的百分比按实际人数折算则为 16
        assertEquals(16, QualifyPolicy.resolveQuota(null, null, 80.0, 20, 30,
                QualifyPolicy.BaseMode.ACTUAL).count());
    }
}
