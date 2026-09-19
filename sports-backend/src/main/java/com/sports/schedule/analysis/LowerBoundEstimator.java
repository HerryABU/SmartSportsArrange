package com.sports.schedule.analysis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 理论下界评估器——把「感觉优化了」变成「知道离最优还有多远」。
 *
 * <h2>为什么 NP 难问题必须算下界</h2>
 * 没有最优解可比对时，唯一能锚定方向的参照物是<b>下界</b>：任何可行解都不可能优于它。
 * 有了下界才能回答「还有多少改进空间」；没有下界，就只能说「这版比上版好」——
 * 那不是评估，是感觉。
 *
 * <h2>三个独立下界，取最大（因为必须同时满足）</h2>
 * <ol>
 *   <li><b>容量下界</b>：某池总需求 ÷（该池并发位 × 单日容量）→ 该池至少需要几天。
 *       它衡量「场地/工位」这条资源线。</li>
 *   <li><b>运动员下界</b>：某运动员兼报项目的总时长 ÷ 单日容量 → 他至少需要几天。
 *       <b>与场地无关</b>——同一个人不可能同时上场，他的项目天然必须串行。
 *       这条下界常常比容量下界更紧，也最容易被忽略（只盯着场地算，忘了人也会饱和）。</li>
 *   <li><b>装箱下界</b>：项目不可切、项间要留间隔，所以真实占用 = Σ时长 + Σ间隔，
 *       再按「每箱一个时段窗口」折算需要的箱数。忽略间隔会低估下界。</li>
 * </ol>
 *
 * <h2>绑定资源（binding resource）</h2>
 * 三个下界里最大的那个就是<b>瓶颈</b>：它决定了「再优化也不可能少于几天」。
 * 报告里明确写出瓶颈是谁——这直接告诉用户「该加场地还是该削项目」，而不是笼统地说「排不下」。
 */
@Slf4j
@Component
public class LowerBoundEstimator {

    /** 分析用的最小输入单元 */
    public record Item(String poolKey, int rawDurationMinutes, int intervalMinutes, Set<Long> athleteIds) {
    }

    /**
     * 下界评估结论。
     *
     * @param lowerBoundDays    综合下界（任何可行解都不可能少于这个天数）
     * @param actualDays        当前方案实际用到的天数
     * @param dayGapPercent     相对下界的差距（0 = 已贴着下界，无法再压缩）
     * @param bindingResource   瓶颈资源描述（决定下界的那一项）
     */
    public record Assessment(int lowerBoundDays, int actualDays, double dayGapPercent,
                             int capacityBoundDays, int athleteBoundDays, int packingBoundDays,
                             String bindingResource, double utilizationPercent, double compressionPercent,
                             List<Map<String, Object>> bottlenecks) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lowerBoundDays", lowerBoundDays);
            m.put("actualDays", actualDays);
            m.put("dayGapPercent", dayGapPercent);
            m.put("capacityBoundDays", capacityBoundDays);
            m.put("athleteBoundDays", athleteBoundDays);
            m.put("packingBoundDays", packingBoundDays);
            m.put("bindingResource", bindingResource);
            m.put("utilizationPercent", utilizationPercent);
            m.put("compressionPercent", compressionPercent);
            m.put("bottlenecks", bottlenecks);
            m.put("note", "下界 = 任何可行解都不可能优于此；dayGapPercent = 0 表示已贴住下界，"
                    + "继续优化只能减少压缩量，不可能减少天数");
            return m;
        }
    }

    /**
     * 计算下界与差距。
     *
     * @param items               待排单元（用<b>真实估算时长</b>，不是被压缩后的时长——
     *                            下界要衡量「这项需求本身要多少资源」）
     * @param slotsByPool         池 → 并发位数
     * @param dailyCapacityMinutes 单日可用分钟（所有时段之和）
     * @param actualDays          当前方案实际用到的天数
     * @param totalGivenMinutes   当前方案实际给出的总时长（用于算利用率与压缩比）
     */
    public Assessment assess(List<Item> items, Map<String, Integer> slotsByPool,
                             int dailyCapacityMinutes, int actualDays, int totalGivenMinutes) {
        List<Map<String, Object>> bottlenecks = new ArrayList<>();
        if (items == null || items.isEmpty() || dailyCapacityMinutes <= 0) {
            return new Assessment(0, Math.max(0, actualDays), 0, 0, 0, 0,
                    "无（没有待排项目或时段未配置）", 0, 0, bottlenecks);
        }

        // ① 按池汇总需求（原始时长，不含间隔）
        Map<String, Long> demandByPool = new LinkedHashMap<>();
        Map<String, Long> intervalByPool = new LinkedHashMap<>();
        long totalRaw = 0;
        for (Item it : items) {
            long dur = Math.max(0, it.rawDurationMinutes());
            demandByPool.merge(it.poolKey(), dur, Long::sum);
            intervalByPool.merge(it.poolKey(), (long) Math.max(0, it.intervalMinutes()), Long::sum);
            totalRaw += dur;
        }

        // 容量下界
        int capacityBound = 0;
        String capacityBinder = "-";
        for (Map.Entry<String, Long> e : demandByPool.entrySet()) {
            int slots = Math.max(1, slotsByPool.getOrDefault(e.getKey(), 1));
            int days = ceilDiv(e.getValue(), (long) slots * dailyCapacityMinutes);
            if (days > capacityBound) {
                capacityBound = days;
                capacityBinder = String.format("%s（%d 并发位，需求 %d 分钟）", e.getKey(), slots, e.getValue());
            }
        }

        // ② 运动员下界：同一人的项目必须串行 —— 与场地资源无关的硬下界
        Map<Long, Long> demandByAthlete = new LinkedHashMap<>();
        Map<Long, Integer> countByAthlete = new LinkedHashMap<>();
        for (Item it : items) {
            if (it.athleteIds() == null) continue;
            long dur = Math.max(0, it.rawDurationMinutes());
            for (Long a : it.athleteIds()) {
                demandByAthlete.merge(a, dur, Long::sum);
                countByAthlete.merge(a, 1, Integer::sum);
            }
        }
        int athleteBound = 0;
        long busiest = -1;
        for (Map.Entry<Long, Long> e : demandByAthlete.entrySet()) {
            int days = ceilDiv(e.getValue(), dailyCapacityMinutes);
            if (days > athleteBound) {
                athleteBound = days;
                busiest = e.getKey();
            }
        }

        // ③ 装箱下界：不可切 + 项间间隔
        int packingBound = 0;
        String packingBinder = "-";
        for (Map.Entry<String, Long> e : demandByPool.entrySet()) {
            int slots = Math.max(1, slotsByPool.getOrDefault(e.getKey(), 1));
            long bins = ceilDiv(e.getValue() + intervalByPool.getOrDefault(e.getKey(), 0L),
                    dailyCapacityMinutes);
            int days = ceilDiv(bins, slots);
            if (days > packingBound) {
                packingBound = days;
                packingBinder = String.format("%s（需 %d 个时段箱）", e.getKey(), bins);
            }
        }

        int lb = Math.max(Math.max(capacityBound, athleteBound), packingBound);
        String binding;
        if (lb == athleteBound && athleteBound > 0) {
            binding = "运动员（" + (busiest < 0 ? "-" : "运动员#" + busiest)
                    + " 兼项总时长决定，与场地无关）";
        } else if (lb == capacityBound) {
            binding = "场地容量（" + capacityBinder + "）";
        } else {
            binding = "装箱（" + packingBinder + "）";
        }

        double gap = lb > 0 ? round1((actualDays - lb) * 100.0 / lb) : 0;
        double utilization = 0;
        int totalSlots = 0;
        for (Map.Entry<String, Long> e : demandByPool.entrySet()) {
            totalSlots += Math.max(1, slotsByPool.getOrDefault(e.getKey(), 1));
        }
        long supply = (long) Math.max(1, actualDays) * dailyCapacityMinutes * Math.max(1, totalSlots);
        if (supply > 0) utilization = round1(totalGivenMinutes * 100.0 / supply);
        double compression = totalRaw > 0 ? round1(totalGivenMinutes * 100.0 / totalRaw) : 0;

        // 瓶颈清单：最忙的运动员（最可能拖长赛程的人）
        demandByAthlete.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "athlete");
                    m.put("id", e.getKey());
                    m.put("rawMinutes", e.getValue());
                    m.put("entries", countByAthlete.getOrDefault(e.getKey(), 0));
                    m.put("minDays", ceilDiv(e.getValue(), dailyCapacityMinutes));
                    bottlenecks.add(m);
                });
        // 最紧的池
        demandByPool.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    int slots = Math.max(1, slotsByPool.getOrDefault(e.getKey(), 1));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "pool");
                    m.put("id", e.getKey());
                    m.put("rawMinutes", e.getValue());
                    m.put("slots", slots);
                    m.put("minDays", ceilDiv(e.getValue(), (long) slots * dailyCapacityMinutes));
                    bottlenecks.add(m);
                });

        log.info("下界评估: 综合下界 {} 天 / 实际 {} 天（gap {}%），瓶颈={}",
                lb, actualDays, gap, binding);
        return new Assessment(lb, actualDays, gap, capacityBound, athleteBound, packingBound,
                binding, utilization, compression, bottlenecks);
    }

    private static int ceilDiv(long a, long b) {
        if (b <= 0) return 0;
        return (int) ((a + b - 1) / b);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
