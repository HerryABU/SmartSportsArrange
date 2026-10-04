package com.sports.schedule.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sports.schedule.plan.PredictivePlanner.PlanUnit;
import com.sports.schedule.plan.PredictivePlanner.SlotDays;

/**
 * <b>结构性断裂的归因与「给某天多配容量」的建议</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>块连续性修复（把同组跨天的单元并到同一天）在 HEX/REGULAR 这类**装得很紧**的
 * 实例上一次都搬不动 —— 不是因为算子不好，而是**锚点天真的满了**：
 * 同一天的槽位容量被别的单元占满，连容量守恒的交换都找不到能降低断裂的组合。</p>
 *
 * <p>这时候剩下的断裂是<b>结构性</b>的：它是**容量布局问题**，不是搜索问题。
 * 规划器能做的最有价值的事不是继续搜，而是把这件事**翻译成用户能做的决策**：
 * 「第 3 天上午的田径场不够，至少要再给 N 分钟，这个组才能并到一天」。</p>
 *
 * <p>本类就做这一件事：对每个跨天的组，找出它的**锚点天**（成员最多的那天），
 * 算出「把其余天的成员并过去」还缺多少分钟，并按天汇总成一条建议。</p>
 *
 * <h2>⚠️ 建议值的口径（保守下界，不是保证）</h2>
 *
 * <p>同一天可能有多个组各报一个缺口，而新增的容量是**可以被共用**的；
 * 因此按天汇总时取各组的<b>最大</b>缺口，而不是求和 —— 它是
 * 「<b>至少</b>加这么多才有可能用上」的下界。求和会把结论说满，反而误导。</p>
 */
public final class PlanAdvice {

    private PlanAdvice() {
    }

    /** 一个「跨了多天」的组的归因。 */
    public record GroupBreak(String groupKey, int anchorDay, int offDayUnits,
                             String venue, int shortfallMinutes) {
    }

    /** 分析结果：逐组归因 + 按天汇总的建议增量。 */
    public record Result(List<GroupBreak> breaks, Map<Integer, Integer> extraMinutesByDay) {

        /** 是否**真的**存在结构性断裂（空列表 = 没有可归因的断裂）。 */
        public boolean hasBreaks() {
            return !breaks.isEmpty();
        }

        /** 某天建议补充的分钟数（无建议返回 0）。 */
        public int extraMinutesOf(int day) {
            return extraMinutesByDay.getOrDefault(day, 0);
        }
    }

    private static final Result EMPTY = new Result(List.of(), Map.of());

    /**
     * 归因分析。
     *
     * @param units    全部待排单元
     * @param slotOf   单元 key → 槽（缺键 = 未排，不参与块统计）
     * @param capacity 容量表，键 = {@link PredictivePlanner#capKey(int, String)}
     * @param slotDays 槽 → 天
     */
    public static Result analyze(List<PlanUnit> units, Map<String, Integer> slotOf,
                                 Map<String, Integer> capacity, SlotDays slotDays) {
        if (units.isEmpty() || slotOf.isEmpty()) {
            return EMPTY;
        }
        // 组的成员（按天分桶）
        Map<String, List<PlanUnit>> byGroup = new LinkedHashMap<>();
        for (PlanUnit u : units) {
            if (u.groupKey() == null || u.groupKey().isBlank() || !slotOf.containsKey(u.key())) {
                continue;
            }
            byGroup.computeIfAbsent(u.groupKey(), k -> new ArrayList<>()).add(u);
        }
        // 已用量：槽 → 场地 → 分钟
        Map<Integer, Map<String, Integer>> usedBySlotVenue = new LinkedHashMap<>();
        for (PlanUnit u : units) {
            Integer sid = slotOf.get(u.key());
            if (sid == null) {
                continue;
            }
            usedBySlotVenue.computeIfAbsent(sid, k -> new LinkedHashMap<>())
                    .merge(u.venue(), u.duration(), Integer::sum);
        }

        List<GroupBreak> breaks = new ArrayList<>();
        Map<Integer, Integer> extra = new LinkedHashMap<>();
        for (Map.Entry<String, List<PlanUnit>> g : byGroup.entrySet()) {
            Map<Integer, Integer> dayCount = new LinkedHashMap<>();
            for (PlanUnit u : g.getValue()) {
                dayCount.merge(slotDays.dayOf(slotOf.get(u.key())), 1, Integer::sum);
            }
            if (dayCount.size() <= 1) {
                continue;                                   // 没跨天，无需建议
            }
            // 锚点天 = 成员最多的那天；打平时取天数较小者（确定性）
            int anchorDay = dayCount.entrySet().stream()
                    .max(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparing(e -> -e.getKey()))
                    .map(Map.Entry::getKey).orElse(0);

            // 要搬过去的成员（按场地汇总需求）
            Map<String, Integer> demandByVenue = new LinkedHashMap<>();
            for (PlanUnit u : g.getValue()) {
                if (slotDays.dayOf(slotOf.get(u.key())) != anchorDay) {
                    demandByVenue.merge(u.venue(), u.duration(), Integer::sum);
                }
            }
            // 锚点天各场地的空闲容量
            Map<String, Integer> freeByVenue = new LinkedHashMap<>();
            Set<String> venues = new LinkedHashSet<>(demandByVenue.keySet());
            for (String venue : venues) {
                int free = 0;
                for (Map.Entry<String, Integer> e : capacity.entrySet()) {
                    String key = e.getKey();
                    int hash = key.indexOf('#');
                    if (hash <= 0) {
                        continue;
                    }
                    int slot;
                    try {
                        slot = Integer.parseInt(key.substring(0, hash));
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    if (!key.substring(hash + 1).equals(venue)) {
                        continue;
                    }
                    if (slotDays.dayOf(slot) != anchorDay) {
                        continue;
                    }
                    int cap = e.getValue() == null ? 0 : e.getValue();
                    int used = usedBySlotVenue.getOrDefault(slot, Map.of()).getOrDefault(venue, 0);
                    free += Math.max(0, cap - used);
                }
                freeByVenue.put(venue, free);
            }
            // 缺口 = 需求 - 空闲；取各场地里最大的那个（决定「至少加多少」）
            int worst = 0;
            String worstVenue = null;
            for (Map.Entry<String, Integer> d : demandByVenue.entrySet()) {
                int need = d.getValue();
                int free = freeByVenue.getOrDefault(d.getKey(), 0);
                int shortfall = Math.max(0, need - free);
                if (shortfall > worst) {
                    worst = shortfall;
                    worstVenue = d.getKey();
                }
            }
            int offUnits = 0;
            for (PlanUnit u : g.getValue()) {
                if (slotDays.dayOf(slotOf.get(u.key())) != anchorDay) {
                    offUnits++;
                }
            }
            breaks.add(new GroupBreak(g.getKey(), anchorDay, offUnits, worstVenue, worst));
            if (worst > 0) {
                // 按天取**最大**缺口（见类注释：新增容量可共用，求和会把结论说满）
                extra.merge(anchorDay, worst, Math::max);
            }
        }
        breaks.sort(Comparator.comparingInt(GroupBreak::anchorDay)
                .thenComparing(GroupBreak::groupKey));
        Map<Integer, Integer> sorted = new LinkedHashMap<>();
        extra.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new Result(breaks, sorted);
    }
}
