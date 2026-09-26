package com.sports.schedule.opt.mnsa;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.sports.schedule.opt.bandit.OperatorBandit;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
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
 * 多邻域模拟退火（MNSA）——<b>六种邻域移动 + UCB1 自适应切换 + SA 接受准则</b>。
 *
 * <h2>它补的是哪块短板</h2>
 * 组合层的 Timefold 局部搜索在单一邻域结构上收敛，GA 与 LNS 之后仍可能留有
 * 「单点移动够不着、整体重排又太贵」的残余冲突。MNSA 用<b>单步移动 + 快速评分</b>
 * （{@code SolutionManager.update}，不跑求解器）在几十上百步内持续扰动：
 * 六种移动覆盖「换位 / 迁移 / 时长」三个维度，「拔除冲突 / 补排空缺」两个极端，
 * UCB1 老虎机按各移动的实际收益自适应分配使用频率——这正是文献里
 * 「自适应层条件于当前解的反馈，而非固定轮换」的落地。
 *
 * <h2>与既有阶段的关系</h2>
 * 输入是上游（组合层 → GA → LNS）的解；SA 接受准则允许中途暂时变差以跳出局部结构，
 * 但<b>最终只返回严格优于输入的解</b>（best-ever 追踪），与 LNS「绝不抖散好解」的
 * 对外承诺保持一致。固定种子 + 确定性老虎机 + 按步推进的温度曲线 ⇒ 全程可复现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiNeighborhoodAnnealer {

    private static final List<MnsaMove> MOVES = MnsaMoves.standard();

    private final ScheduleOptimizer optimizer;

    /**
     * 在已有解上做 MNSA 精修。
     *
     * @param seed       上游给出的解（必须已带评分）
     * @param iterations 移动步数（每步 = 选邻域 → 移动 → 评分 → 接受判定）
     * @return 严格更优的解；无改进或输入不可用时返回空
     */
    public Optional<SchedulePlan> anneal(SchedulePlan seed, int iterations) {
        return anneal(seed, iterations, ScheduleOptimizer.RANDOM_SEED, null);
    }

    /** 显式随机种子（测试用），并把过程统计写入 info（可为 null） */
    public Optional<SchedulePlan> anneal(SchedulePlan seed, int iterations, long seedValue,
                                         Map<String, Object> info) {
        if (seed == null || seed.getUnits() == null || seed.getUnits().isEmpty()
                || seed.getScore() == null || iterations <= 0) {
            return Optional.empty();
        }
        SolutionManager<SchedulePlan, HardMediumSoftScore> scorer = optimizer.solutionManager();
        OperatorBandit bandit = new OperatorBandit(MOVES.stream().map(MnsaMove::name).toList());
        SaAcceptor acceptor = SaAcceptor.geometric(iterations);
        Random rnd = new Random(seedValue);

        SchedulePlan best = seed.deepCopy();
        SchedulePlan current = seed.deepCopy();
        int applied = 0;
        int accepted = 0;
        int newBests = 0;

        for (int i = 0; i < iterations; i++) {
            int arm = bandit.select();
            MnsaMove move = MOVES.get(arm);
            SchedulePlan work = current.deepCopy();
            if (!move.apply(work, rnd)) {
                bandit.reward(arm, 0.0);
                continue;
            }
            applied++;
            scorer.update(work);
            HardMediumSoftScore before = current.getScore();
            HardMediumSoftScore after = work.getScore();
            boolean newBest = after.compareTo(best.getScore()) > 0;
            boolean ok = acceptor.accept(before, after, rnd);
            if (ok) {
                current = work;
                accepted++;
            }
            if (newBest) {
                best = work;
                newBests++;
            }
            // 老虎机收益（ALNS 文献的经典分级）：刷新最优 > 变好 > 接受 > 作废
            double reward = newBest ? 1.0 : (ok ? (after.compareTo(before) > 0 ? 0.6 : 0.4) : 0.1);
            bandit.reward(arm, reward);
        }

        if (best.getScore().compareTo(seed.getScore()) <= 0) {
            log.info("MNSA 精修: {} 步（实际施加 {} 步、接受 {} 步）后无改进，当前解已足够好",
                    iterations, applied, accepted);
            return Optional.empty();
        }
        log.info("MNSA 精修: {} 步、施加 {} 步、接受 {} 步、刷新最优 {} 次，score {} → {}",
                iterations, applied, accepted, newBests, seed.getScore(), best.getScore());
        if (info != null) {
            info.put("iterations", iterations);
            info.put("applied", applied);
            info.put("accepted", accepted);
            info.put("newBests", newBests);
            info.put("score", seed.getScore() + " → " + best.getScore());
            info.put("moveStats", bandit.toMap());
        }
        return Optional.of(best);
    }

    /** 过程追踪（供调用方拼装解释信息）：每种移动的最终使用统计 */
    public List<String> describe(OperatorBandit bandit) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < bandit.names().size(); i++) {
            lines.add(bandit.names().get(i) + "×" + bandit.pullCounts().get(i));
        }
        return lines;
    }

    /** 统计快照（不暴露内部可变结构） */
    public Map<String, Object> stats(OperatorBandit bandit) {
        return new LinkedHashMap<>(bandit.toMap());
    }
}
