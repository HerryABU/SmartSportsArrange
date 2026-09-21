package com.sports.schedule.opt.lns;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.sports.schedule.opt.solver.Placement;

/**
 * 大邻域搜索（LNS）：<b>破坏一部分解 → 只重建这一部分 → 只接受更好的</b>。
 *
 * <h2>它解决的是什么问题</h2>
 * 局部搜索只在「一个项目的落位」这种粒度上移动，一旦排到局部最优，任何单点移动都会立刻变差，
 * 于是卡死。LNS 换粒度：一次拔出<b>一整个年级/一整个时段窗口/某个最忙运动员的全部项目</b>，
 * 在这个子空间里重新优化。
 *
 * <p>关键在「其余部分锁定不动」（{@code @PlanningPin}）：被破坏的只是局部，
 * 所以每次重建很快，能在同样的时间预算里做很多轮——这与「整体重排」的代价完全不同。</p>
 *
 * <p>接受准则用<b>只接受更好</b>（不做等价接受）：因为这里是「精修」阶段，
 * 上游已经由算法组合层给出一个不错的解，此时允许变差只会把好解抖散。
 * 需要「允许暂时变差」的场景已经在算法组合层用模拟退火覆盖了。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LnsImprover {

    /** 每轮重建用的局部搜索算法：SA 在小子空间里更容易跳出局部结构 */
    private static final LocalSearchType ROUND_TYPE = LocalSearchType.SIMULATED_ANNEALING;

    private final ScheduleOptimizer optimizer;

    public record Report(boolean used, int rounds, int acceptedRounds, String initialScore,
                         String finalScore, List<String> trace) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("used", used);
            m.put("rounds", rounds);
            m.put("acceptedRounds", acceptedRounds);
            m.put("initialScore", initialScore);
            m.put("finalScore", finalScore);
            m.put("trace", trace);
            return m;
        }
    }

    /**
     * 在已有解上做 LNS 精修。
     *
     * @param initial        上游（算法组合层）给出的解
     * @param rounds         破坏-重建轮数
     * @param perRoundBudget 每轮的时间预算（总预算 ≈ rounds × 每轮预算）
     * @return 改进后的解；无改进或输入不可用时返回空
     */
    public Optional<SchedulePlan> improve(SchedulePlan initial, int rounds, Duration perRoundBudget) {
        if (initial == null || initial.getUnits() == null || initial.getUnits().isEmpty()
                || initial.getScore() == null || rounds <= 0) {
            return Optional.empty();
        }
        SchedulePlan best = initial;
        int accepted = 0;
        List<String> trace = new ArrayList<>();
        List<ScheduleUnit> all = initial.getUnits();

        for (int r = 0; r < rounds; r++) {
            DestroyResult dr = destroyAndRepair(best, all, r, perRoundBudget);
            if (dr == null || dr.solved() == null || dr.solved().getScore() == null) continue;
            boolean better = dr.solved().getScore().compareTo(best.getScore()) > 0;
            trace.add(String.format("第%d轮 %s：%s（%s）", r + 1, dr.neighborhood(), dr.solved().getScore(),
                    better ? "接受" : "丢弃"));
            if (better) {
                best = dr.solved();
                accepted++;
            }
        }

        if (accepted == 0) {
            log.info("LNS 精修: {} 轮全部未改进（当前解已局部稳定）", rounds);
            return Optional.empty();
        }
        log.info("LNS 精修: {} 轮中接受 {} 轮，score {} → {}",
                rounds, accepted, initial.getScore(), best.getScore());
        return Optional.of(best);
    }

    /** 一轮「破坏 + 重建」 */
    private DestroyResult destroyAndRepair(SchedulePlan current, List<ScheduleUnit> all,
                                           int round, Duration budget) {
        SchedulePlan copy = cloneForRound(current);
        Neighborhood neighborhood = pickNeighborhood(copy.getUnits(), all, round);

        int destroyed = 0;
        for (ScheduleUnit u : copy.getUnits()) {
            // 邻域内 → 清空并解锁；邻域外 → 锁定并保留当前值。
            // 注意「未排入的单元一律解锁」：它们必须有机会被重新安排，
            // 否则一旦被锁定（值本来就是 null）求解器会直接报错。
            boolean inScope = neighborhood.contains(u) || !u.isPlaced();
            if (inScope) {
                u.setPlacement(null);
                u.setDuration(null);
                u.setPinned(false);
                destroyed++;
            } else {
                u.setPinned(true);
            }
        }
        if (destroyed == 0) return null;

        SchedulePlan solved = optimizer
                .solve(copy, budget, ROUND_TYPE, 20260918L + round)
                .orElse(null);
        return new DestroyResult(solved, neighborhood.name() + " 破坏 " + destroyed + " 项");
    }

    /**
     * 邻域轮换：四种策略依次上，避免只用一种邻域，
     * 也避免重复抽到同一批单元（用 round 派生锚点）。
     */
    private Neighborhood pickNeighborhood(List<ScheduleUnit> units, List<ScheduleUnit> all, int round) {
        if (units.isEmpty()) return Neighborhood.randomSubset(all, 3, round);
        ScheduleUnit anchor = units.get(Math.floorMod(round * 7, units.size()));
        switch (round % 4) {
            case 0:
                return Neighborhood.byGrade(anchor);
            case 1:
                return Neighborhood.byAthlete(anchor, units);
            case 2:
                return Neighborhood.byBin(anchor);
            default:
                return Neighborhood.randomSubset(units, Math.max(3, units.size() / 4), round);
        }
    }

    /**
     * 为每一轮克隆一份实体列表——<b>必须深拷贝</b>，否则破坏操作会直接毁掉当前最优解。
     * 位置对象（Placement）是问题事实，可以安全共享引用。
     */
    private static SchedulePlan cloneForRound(SchedulePlan src) {
        SchedulePlan copy = src.deepCopy();
        // 克隆出的实体可能继承了上一轮的锁定标记，这里一律复位，由 destroyAndRepair 重新决定锁谁
        for (ScheduleUnit u : copy.getUnits()) {
            u.setPinned(false);
        }
        return copy;
    }

    private record DestroyResult(SchedulePlan solved, String neighborhood) {
    }
}
