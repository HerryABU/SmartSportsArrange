package com.sports.schedule.plan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.sports.schedule.plan.PredictivePlanner.PlanUnit;

/**
 * <b>加权代价口径</b>：把「排得好不好」也折算成分数，用于**比较与上报**。
 *
 * <h2>为什么需要它（以及它为什么不是搜索目标）</h2>
 *
 * <p>魔鬼档之外的场景里，四档编排模式（rule / optimize / ai / 混合）的**未排数**
 * 全部落在 1.0 / 1.33 —— 那已经是**贴近下界**，不是「搜索不足」。
 * 未排是个位数小整数，四档落在同一个数上，它对这些实例**没有分辨率**：
 * 持平只能说明「指标不够细」，不能说明「一样好」。</p>
 *
 * <p>所以要出差异必须换口径：把 兼项撞 / 道次撞 / 碎块 / 工期 一并计价。</p>
 *
 * <p>⚠️ 本类**只用于比较与上报**。规划器**搜索**用的代价是
 * {@link PredictivePlanner} 内部那个为速度与可采纳性调过的函数 ——
 * 换比较口径**不应该**顺手改搜索目标，那是另一件需要单独实测的事
 * （改了搜索目标必须重新验证「重启更多不更差」这类不变量）。</p>
 *
 * <p>权重表与 Python 侧 {@code sports_ai/metrics.py::WEIGHTS} **逐位一致**，
 * 任何一边改动都必须同步另一边（{@code PlanCostTest} 会把数字钉住）。</p>
 *
 * @param unplaced      任何可行时段都放不下的单元（硬失败）
 * @param athleteClash  同一运动员被排进同一时段（方案本身是错的）
 * @param capacityOverflow 同一时段同一场地已排时长超出容量的分钟数（方案本身是错的）
 * @param laneClash     同批（同项目 + 同年级）单元被排进同一时段（公平性）。
 *                      <b>注意</b>：在规划层该分量**结构性恒为 0** —— 本层的单元
 *                      key 就是 {@code 项目编码@年级}（见 {@code PredictivePlannerService.unitKey}），
 *                      同一批只会有一个单元。保留该分量是为了与评测口径同构
 *                      （评测层一个项目有多组次，道次撞会真的出现），
 *                      而不是在这里假装能测到它。
 * @param fragBlocks    同组跨天（块不连续）的处数
 * @param daysOver      实际占用的天数超出限定天数（{@code daysLimit>0} 时才可能非零）
 */
public record PlanCost(int unplaced, int athleteClash, int capacityOverflow, int laneClash,
                       int fragBlocks, int daysOver, double weighted) {

    // ---- 单一权值来源（与 sports_ai/metrics.py::WEIGHTS 逐位一致）----
    public static final double W_UNPLACED = 1000.0;
    public static final double W_ATHLETE_CLASH = 500.0;
    public static final double W_CAPACITY_OVERFLOW = 500.0;
    public static final double W_LANE_CLASH = 50.0;
    public static final double W_FRAG_BLOCKS = 10.0;
    public static final double W_DAYS_OVER = 20.0;

    /** 权重表（供前端展示「这笔账怎么算的」与双端一致性测试使用）。 */
    public static Map<String, Double> weights() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("unplaced", W_UNPLACED);
        m.put("athlete_clash", W_ATHLETE_CLASH);
        m.put("capacity_overflow", W_CAPACITY_OVERFLOW);
        m.put("lane_clash", W_LANE_CLASH);
        m.put("frag_blocks", W_FRAG_BLOCKS);
        m.put("days_over", W_DAYS_OVER);
        return m;
    }

    /**
     * 从日程配置的「时间三态」解析**限定天数**。
     *
     * <p>三态与日程配置一致：{@code dayMode=fixed} → 用 {@code days}；
     * {@code unlimited} / {@code minimize} → {@code 0}（<b>不限</b>，谈不上「超限」）；
     * 未给 {@code dayMode} 的旧配置退回「{@code days > 0} 即限定」，保证向后兼容。</p>
     *
     * <p>⚠️ {@code minimize}（尽可能减少）必须映射成 0 而不是「上限 = 某个数」：
     * 它的语义是「尽量压缩」的<b>软目标</b>，把它当上限会让「多占一天」变成违规 ——
     * 与语义正好相反。</p>
     */
    public static int dayLimitOf(String dayMode, int days) {
        if (dayMode != null) {
            String m = dayMode.trim();
            if ("minimize".equalsIgnoreCase(m) || "unlimited".equalsIgnoreCase(m)) {
                return 0;
            }
        }
        return Math.max(0, days);
    }

    /**
     * 计算加权代价。
     *
     * @param units    全部待排单元（含未排的）
     * @param slotOf   单元 key → 槽；缺键即「未排」
     * @param capacity 容量表，键 = {@link PredictivePlanner#capKey(int, String)}
     * @param slotDays 槽 → 天
     * @param daysLimit 限定天数，三态语义与日程配置一致：{@code >0} 限定；{@code 0} 不限；
     *                  {@code <0} 尽可能减少（后两者都谈不上「超限」，恒 0）
     */
    public static PlanCost of(java.util.List<PlanUnit> units, Map<String, Integer> slotOf,
                              Map<String, Integer> capacity, PredictivePlanner.SlotDays slotDays,
                              int daysLimit) {
        int unplaced = 0;
        int overflow = 0;
        Map<String, Integer> load = new LinkedHashMap<>();
        Map<String, Set<String>> athletesSeen = new LinkedHashMap<>();   // slot → athleteId
        Map<String, Set<String>> batchSeen = new LinkedHashMap<>();      // slot → unitKey
        int athleteClash = 0;
        int laneClash = 0;
        Set<Integer> usedDays = new LinkedHashSet<>();
        Map<String, Set<Integer>> byGroup = new LinkedHashMap<>();

        for (PlanUnit u : units) {
            Integer sid = slotOf.get(u.key());
            if (sid == null) {
                unplaced++;
                continue;
            }
            load.merge(PredictivePlanner.capKey(sid, u.venue()), u.duration(), Integer::sum);
            String slotKey = String.valueOf(sid);
            Set<String> seenAthletes = athletesSeen.computeIfAbsent(slotKey, k -> new LinkedHashSet<>());
            for (Long a : u.athletes()) {
                if (!seenAthletes.add(String.valueOf(a))) {
                    athleteClash++;                 // 同一时段同一人出现两次
                }
            }
            Set<String> seenBatch = batchSeen.computeIfAbsent(slotKey, k -> new LinkedHashSet<>());
            if (!seenBatch.add(u.key())) {
                laneClash++;                        // 同批（同项目+同年级）落同一时段
            }
            usedDays.add(slotDays.dayOf(sid));
            if (u.groupKey() != null && !u.groupKey().isBlank()) {
                byGroup.computeIfAbsent(u.groupKey(), k -> new LinkedHashSet<>()).add(slotDays.dayOf(sid));
            }
        }
        for (Map.Entry<String, Integer> e : load.entrySet()) {
            Integer c = capacity.get(e.getKey());
            if (c != null && e.getValue() > c) {
                overflow += e.getValue() - c;
            }
        }
        int frag = 0;
        for (Set<Integer> days : byGroup.values()) {
            frag += Math.max(0, days.size() - 1);
        }
        int daysOver = daysLimit > 0 && !usedDays.isEmpty()
                ? Math.max(0, usedDays.size() - daysLimit) : 0;
        double weighted = W_UNPLACED * unplaced
                + W_ATHLETE_CLASH * athleteClash
                + W_CAPACITY_OVERFLOW * overflow
                + W_LANE_CLASH * laneClash
                + W_FRAG_BLOCKS * frag
                + W_DAYS_OVER * daysOver;
        return new PlanCost(unplaced, athleteClash, overflow, laneClash, frag, daysOver, weighted);
    }

    /** 分项加权贡献（键与 {@link #weights()} 一致），供前端「这笔账怎么算的」展示。 */
    public Map<String, Double> breakdown() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("unplaced", W_UNPLACED * unplaced);
        m.put("athlete_clash", W_ATHLETE_CLASH * athleteClash);
        m.put("capacity_overflow", W_CAPACITY_OVERFLOW * capacityOverflow);
        m.put("lane_clash", W_LANE_CLASH * laneClash);
        m.put("frag_blocks", W_FRAG_BLOCKS * fragBlocks);
        m.put("days_over", W_DAYS_OVER * daysOver);
        return m;
    }
}
