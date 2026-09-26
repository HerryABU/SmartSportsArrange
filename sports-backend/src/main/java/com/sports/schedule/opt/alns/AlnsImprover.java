package com.sports.schedule.opt.alns;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.sports.schedule.opt.bandit.OperatorBandit;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 自适应大邻域搜索（ALNS）——<b>破坏-修复循环 + UCB1 自适应算子选择</b>。
 *
 * <h2>与既有 LNS（{@code opt.lns}）的区别</h2>
 * 既有 LNS 的破坏是<b>固定轮换</b>的四种邻域（按年级/运动员/窗口/随机），
 * 重建交给 Timefold 完整求解——深但慢。ALNS 在两点上升级：
 * <ol>
 *   <li><b>破坏自适应</b>：四种破坏算子（随机/冲突簇/最忙运动员/窗口）由老虎机按
 *       实际收益选择——文献验证「自适应层条件于算子贡献」远胜固定轮换，
 *       其中冲突簇破坏直接利用兼项冲突的图结构特征；</li>
 *   <li><b>修复轻量化</b>：贪心/随机插入代替完整求解，单轮成本降低一个数量级，
 *       同样预算下能做更多轮「破坏-修复」。</li>
 * </ol>
 * 老虎机收益沿用 ALNS 文献的经典分级：刷新最优 &gt; 改进当前 &gt; 仅合法 &gt; 作废。
 *
 * <p><b>只接受更好</b>：与 LNS 同为「精修」定位，最终只返回严格优于输入的解；
 * 固定种子 + 确定性老虎机 ⇒ 全程可复现。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlnsImprover {

    private static final List<DestroyOperator> DESTROYS = List.of(
            new RandomDestroyOperator(), new ClashClusterDestroyOperator(),
            new BusyAthleteDestroyOperator(), new WindowDestroyOperator());
    private static final List<RepairOperator> REPAIRS = List.of(
            new GreedyRepairOperator(), new RandomRepairOperator());

    private final ScheduleOptimizer optimizer;

    /**
     * 在已有解上做 ALNS 精修。
     *
     * @param seed   上游给出的解（必须已带评分）
     * @param rounds 破坏-修复轮数
     * @return 严格更优的解；无改进或输入不可用时返回空
     */
    public Optional<SchedulePlan> improve(SchedulePlan seed, int rounds) {
        return improve(seed, rounds, ScheduleOptimizer.RANDOM_SEED, null);
    }

    /** 显式随机种子（测试用），并把过程统计写入 info（可为 null） */
    public Optional<SchedulePlan> improve(SchedulePlan seed, int rounds, long seedValue,
                                          Map<String, Object> info) {
        if (seed == null || seed.getUnits() == null || seed.getUnits().isEmpty()
                || seed.getScore() == null || rounds <= 0) {
            return Optional.empty();
        }
        SolutionManager<SchedulePlan, HardMediumSoftScore> scorer = optimizer.solutionManager();
        OperatorBandit destroyBandit = new OperatorBandit(DESTROYS.stream().map(DestroyOperator::name).toList());
        OperatorBandit repairBandit = new OperatorBandit(REPAIRS.stream().map(RepairOperator::name).toList());
        Random rnd = new Random(seedValue);

        SchedulePlan best = seed.deepCopy();
        SchedulePlan current = seed.deepCopy();
        int executed = 0;
        int accepted = 0;

        for (int r = 0; r < rounds; r++) {
            int dArm = destroyBandit.select();
            int rArm = repairBandit.select();
            SchedulePlan work = current.deepCopy();

            // ① 破坏：拔出算子选中的单元
            List<ScheduleUnit> removed = DESTROYS.get(dArm).pick(work, rnd);
            if (removed.isEmpty()) {
                destroyBandit.reward(dArm, 0.0);
                repairBandit.reward(rArm, 0.0);
                continue;
            }
            for (ScheduleUnit u : removed) {
                u.setPlacement(null);
                u.setDuration(null);
            }

            // ② 修复：按算子策略插回
            int inserted = REPAIRS.get(rArm).repair(work, removed, rnd);
            executed++;
            scorer.update(work);

            // ③ 接受判定与老虎机奖励（精修定位：只接受更好）
            HardMediumSoftScore after = work.getScore();
            boolean better = after.compareTo(current.getScore()) > 0;
            boolean newBest = after.compareTo(best.getScore()) > 0;
            if (better) {
                current = work;
                accepted++;
            }
            if (newBest) {
                best = work;
            }
            double reward = newBest ? 1.0 : (better ? 0.6 : 0.1);
            destroyBandit.reward(dArm, reward);
            repairBandit.reward(rArm, inserted == removed.size() ? Math.max(reward, 0.4) : 0.2);
        }

        if (best.getScore().compareTo(seed.getScore()) <= 0) {
            log.info("ALNS 精修: {} 轮（执行 {} 轮、接受 {} 轮）后无改进，当前解已局部稳定", rounds, executed, accepted);
            return Optional.empty();
        }
        log.info("ALNS 精修: {} 轮、执行 {} 轮、接受 {} 轮，score {} → {}",
                rounds, executed, accepted, seed.getScore(), best.getScore());
        if (info != null) {
            info.put("rounds", rounds);
            info.put("executed", executed);
            info.put("accepted", accepted);
            info.put("score", seed.getScore() + " → " + best.getScore());
            info.put("destroyStats", destroyBandit.toMap());
            info.put("repairStats", repairBandit.toMap());
        }
        return Optional.of(best);
    }

    /** 四种破坏算子名（供测试与外部检视） */
    public static List<String> destroyNames() {
        List<String> names = new ArrayList<>();
        for (DestroyOperator d : DESTROYS) names.add(d.name());
        return names;
    }

    /** 统计快照（两个老虎机的最终使用分布） */
    public Map<String, Object> stats(OperatorBandit destroyBandit, OperatorBandit repairBandit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destroy", destroyBandit.toMap());
        m.put("repair", repairBandit.toMap());
        return m;
    }
}
