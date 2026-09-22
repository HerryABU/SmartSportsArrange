package com.sports.schedule.opt.portfolio;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法组合与选择（Algorithm Selection）。
 *
 * <h2>为什么不是「找到那个终极算法」</h2>
 * ITC2021（国际时间表竞赛）的赛后分析结论很明确：<b>没有单一算法在所有实例上表现最好</b>。
 * 同一个求解器，面对「场地紧张、时间不够」和「场地宽裕、时间富余」两种数据，
 * 最优策略是相反的：
 * <ul>
 *   <li><b>容量紧张</b>时，解空间里遍布「用压缩换时间」的深坑，只做下坡移动的算法会一头扎进去出不来
 *       —— 必须用**允许暂时变差**的算法（模拟退火、迟接受）才跳得出来；</li>
 *   <li><b>容量宽裕</b>时，重点是精修（把项目排得更紧凑、缓冲更充裕）——**禁忌搜索**
 *       记住刚走过的路、避免在原地打转，收敛更快更稳。</li>
 * </ul>
 *
 * <p>所以本类不实现算法，只做<b>调度决策</b>：提取实例特征 → 选出一组候选算法（组合），
 * 交给求解器逐个跑、取最优（即「波次」：多起点并行探索，保留最好的那个解）。</p>
 *
 * <p>本类是**纯函数**（无 Spring 依赖、无状态），因此策略本身可以脱离求解器单测——
 * 「什么数据该选什么算法」这件事必须能被独立验证。</p>
 */
public final class AlgorithmPortfolio {

    private AlgorithmPortfolio() {
    }

    /**
     * 实例特征——算法选择的输入。
     *
     * @param tensionRatio          紧张度 = 总需求时长 ÷ 总供给时长（&gt;1 表示容量客观不足）
     * @param multiEventAthleteRatio 兼项运动员占比（兼报 ≥2 项的人数 ÷ 有报名的人数）
     */
    public record Features(int unitCount, long demandMinutes, long supplyMinutes,
                           double tensionRatio, double multiEventAthleteRatio, int athleteCount) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("unitCount", unitCount);
            m.put("demandMinutes", demandMinutes);
            m.put("supplyMinutes", supplyMinutes);
            m.put("tensionRatio", tensionRatio);
            m.put("multiEventAthleteRatio", multiEventAthleteRatio);
            m.put("athleteCount", athleteCount);
            return m;
        }
    }

    /** 一个候选求解方案（算法 + 随机种子 + 该方案的时间预算） */
    public record Plan(String name, LocalSearchType type, long seed, long budgetMillis) {
    }

    /**
     * 从求解问题里提取特征。
     *
     * <p>供给按「不同并发位 × 窗口容量」求和：候选位置已经展开成
     * 池 × 槽位 × 窗口 × 起点，所以按 binKey 去重后的窗口容量之和就是真实并行供给。</p>
     */
    public static Features extract(List<ScheduleUnit> units, List<Placement> placements) {
        long demand = 0;
        int count = 0;
        Map<Long, Integer> entriesPerAthlete = new LinkedHashMap<>();
        if (units != null) {
            for (ScheduleUnit u : units) {
                int raw = u.getRawDuration();
                if (raw <= 0) continue;
                count++;
                demand += raw;
                long[] athletes = u.getAthletes();
                if (athletes != null) {
                    for (long a : athletes) entriesPerAthlete.merge(a, 1, Integer::sum);
                }
            }
        }

        long supply = 0;
        if (placements != null) {
            Map<String, Integer> binCapacity = new LinkedHashMap<>();
            for (Placement p : placements) {
                binCapacity.putIfAbsent(p.getBinKey(), p.getWindowCapacity());
            }
            for (Integer cap : binCapacity.values()) {
                if (cap != null && cap > 0) supply += cap;
            }
        }

        int multi = 0;
        for (Integer c : entriesPerAthlete.values()) {
            if (c != null && c >= 2) multi++;
        }
        double multiRatio = entriesPerAthlete.isEmpty() ? 0 : multi * 1.0 / entriesPerAthlete.size();
        double tension = supply > 0 ? demand * 1.0 / supply : 0;

        return new Features(count, demand, supply, round2(tension), round2(multiRatio),
                entriesPerAthlete.size());
    }

    /**
     * 按特征选择算法组合（波次）。
     *
     * <p>返回多个方案而不是一个，是因为「多起点并行探索」本身就能抵消单一起点的系统性偏差：
     * 贪心或单一邻域从一个起点出发陷进去，另一个起点可能根本不经过那个坑。</p>
     */
    public static List<Plan> planFor(Features f, long totalBudgetMillis) {
        long budget = Math.max(800, totalBudgetMillis);
        long seed = 20260918L;

        if (f == null || f.unitCount() == 0) {
            // 无项目可排：预算全部给默认方案（本就是唯一方案）
            return List.of(new Plan("默认（无项目可排）", LocalSearchType.TABU_SEARCH, seed, budget));
        }

        // ① 先把候选方案（算法 + 种子）列出来，暂不分配预算
        List<Plan> drafted = new ArrayList<>();
        if (f.tensionRatio() >= 1.0) {
            // 容量客观不足：局部最优陷阱极深（很容易停在「一半项目被压缩」的解上），
            // 必须允许暂时变差才跳得出来
            drafted.add(new Plan("模拟退火 SA（容量紧张：允许暂时变差，跳出局部最优）",
                    LocalSearchType.SIMULATED_ANNEALING, seed, 0));
            drafted.add(new Plan("迟接受 LateAcceptance（容量紧张：接受与历史最优持平的解）",
                    LocalSearchType.LATE_ACCEPTANCE, seed + 1, 0));
        } else {
            drafted.add(new Plan("禁忌搜索 TabuSearch（容量宽裕：记住走过的路，避免循环）",
                    LocalSearchType.TABU_SEARCH, seed, 0));
            drafted.add(new Plan("迟接受 LateAcceptance（容量宽裕：稳步改进）",
                    LocalSearchType.LATE_ACCEPTANCE, seed + 1, 0));
        }

        if (f.multiEventAthleteRatio() >= 0.15) {
            // 兼项密集：一次移动会牵动多个运动员的连锁约束，单一邻域容易被卡住
            drafted.add(new Plan("多样化迟接受 DiversifiedLA（兼项密集：多邻域探索）",
                    LocalSearchType.DIVERSIFIED_LATE_ACCEPTANCE, seed + 2, 0));
        }

        // ② 再按实际方案数均分预算（取代原先写死的 /3：方案数变化时不会再出现
        // 「某一方案被砍时间、或多出方案共享同一份被低估的预算」的不公平分配）
        long perPlan = Math.max(600, budget / Math.max(1, drafted.size()));
        List<Plan> plans = new ArrayList<>(drafted.size());
        for (Plan p : drafted) {
            plans.add(new Plan(p.name(), p.type(), p.seed(), perPlan));
        }
        return plans;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
