package com.sports.schedule.opt.fixopt;

import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import com.sports.schedule.opt.fixopt.ConflictSliceSelector.Slice;
import com.sports.schedule.opt.solver.ScheduleOptimizer;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fix-and-Optimize——<b>冻结解的大部分，只对冲突切片做精确重排</b>。
 *
 * <h2>它在精修链里的位置</h2>
 * MNSA（单步移动）与 ALNS（破坏-修复）都是「轻手法」；Fix-and-Optimize 是精修链的
 * 「重手法」：把冲突连通分量切片放开，其余全部用 {@code @PlanningPin} 冻结，
 * 交给 Timefold 在小预算里<b>精确</b>重排这一片。切片小 ⇒ 每次重排都是毫秒级小问题，
 * 学界结论「解多个小规模子问题优于解一个大问题」在此直接落地；
 * 与 Benders 思想同源——不整体推倒重来，而是告诉求解器「病在哪、只治哪」。
 *
 * <h2>与既有 LNS 的差异</h2>
 * LNS 的破坏面按年级/运动员/窗口轮换，重建从「清空」开始；
 * 本类只放开<b>有病灶的切片</b>（含其全部当前值作为求解起点），针对性更强：
 * 无冲突的解直接返回空（没有病就不动手术）。
 *
 * <p><b>只接受更好</b>：每片重排后与当前最优比较，劣化即丢弃；
 * 固定种子 ⇒ 全程可复现。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixAndOptimizer {

    private final ScheduleOptimizer optimizer;

    /** 整个 Fix-and-Optimize 的墙钟上限：逐轮升级预算的失控保护（极端数据下不会无限耗时） */
    private static final long MAX_WALL_MILLIS = 12_000L;

    /**
     * 在已有解上做 Fix-and-Optimize 精修（单轮，兼容旧语义）。
     *
     * @param maxSlices   最多精确重排的切片数（按病灶严重程度取前 N 片）
     * @param sliceBudget 每片重排的求解时间预算
     * @return 严格更优的解；无冲突、无改进或输入不可用时返回空
     */
    public Optional<SchedulePlan> optimize(SchedulePlan seed, int maxSlices, Duration sliceBudget) {
        return optimize(seed, maxSlices, sliceBudget, ScheduleOptimizer.RANDOM_SEED, null);
    }

    /** 显式随机种子（测试用），并把过程统计写入 info（可为 null）。单轮语义。 */
    public Optional<SchedulePlan> optimize(SchedulePlan seed, int maxSlices, Duration sliceBudget,
                                           long seedValue, Map<String, Object> info) {
        return optimizeMultiPass(seed, 1, maxSlices, sliceBudget, seedValue, info);
    }

    /**
     * <b>多轮升级精确重排</b>（2026-09-27）：每轮放开至多 {@code slicesPerPass} 个冲突切片
     * （其余全部冻结），切片的求解预算逐轮翻倍（封顶 ×4）——第一轮轻预算没修透的顽固切片，
     * 第二轮用更大预算重试。切片全部消除或整轮无接受（平台期）即停，另有墙钟上限兜底。
     *
     * <p>旧实现只治「最严重的 N 个切片」一轮就收工：冲突切片多于 N 时，剩余病灶原样残留——
     * 这正是「编排后仍有兼项硬冲突」的直接来源之一。</p>
     *
     * @param maxPasses     最大轮数（0 = 关闭）
     * @param slicesPerPass 每轮最多治疗的切片数（按病灶严重程度取前 N）
     * @param sliceBudget   第一轮的每片重排时间预算（后续轮 ×2、×4）
     * @return 严格更优的解；无冲突、无改进或输入不可用时返回空
     */
    public Optional<SchedulePlan> optimizeMultiPass(SchedulePlan seed, int maxPasses, int slicesPerPass,
                                                    Duration sliceBudget, long seedValue,
                                                    Map<String, Object> info) {
        if (seed == null || seed.getUnits() == null || seed.getUnits().isEmpty()
                || seed.getScore() == null || maxPasses <= 0 || slicesPerPass <= 0
                || sliceBudget == null || sliceBudget.isNegative() || sliceBudget.isZero()) {
            return Optional.empty();
        }
        List<Slice> todo = ConflictSliceSelector.slices(seed);
        if (todo.isEmpty()) {
            log.info("Fix-and-Optimize: 当前解无兼项冲突，无需手术");
            return Optional.empty();
        }
        int slicesTotal = todo.size();
        long deadline = System.currentTimeMillis() + Math.min(MAX_WALL_MILLIS, sliceBudget.toMillis() * 20L);

        SchedulePlan best = seed.deepCopy();
        int totalTreated = 0;
        int totalAccepted = 0;
        int passesUsed = 0;
        List<String> trace = new java.util.ArrayList<>();

        for (int pass = 1; pass <= maxPasses && !todo.isEmpty(); pass++) {
            // 升级预算：第 1 轮 ×1、第 2 轮 ×2、第 3 轮起 ×4（封顶）
            long budgetMs = sliceBudget.toMillis() * Math.min(1L << (pass - 1), 4L);
            int acceptedThisPass = 0;
            int n = Math.min(slicesPerPass, todo.size());
            for (int i = 0; i < n; i++) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("Fix-and-Optimize: 达到墙钟上限（{}ms），提前收工", MAX_WALL_MILLIS);
                    break;
                }
                Slice slice = todo.get(i);
                SchedulePlan work = best.deepCopy();

                // 冻结切片之外的一切；切片单元保持当前值作为求解起点。
                // 未排单元一律解锁：它们没有可保持的值（锁 null 值会报错），且「补排」本身就是改进机会。
                Set<String> sliceKeys = new HashSet<>();
                for (ScheduleUnit u : slice.units()) {
                    sliceKeys.add(u.getKey());
                }
                for (ScheduleUnit u : work.getUnits()) {
                    u.setPinned(!sliceKeys.contains(u.getKey()) && u.isPlaced());
                }

                SchedulePlan solved = optimizer
                        .solve(work, Duration.ofMillis(budgetMs), LocalSearchType.SIMULATED_ANNEALING,
                                seedValue + pass * 1019L + i * 101L)
                        .orElse(null);
                totalTreated++;
                if (solved == null || solved.getScore() == null) continue;
                boolean better = solved.getScore().compareTo(best.getScore()) > 0;
                trace.add("P" + pass + " " + slice.name() + "(" + slice.units().size() + "项/"
                        + slice.clashCount() + "冲突)@" + budgetMs + "ms：" + solved.getScore()
                        + (better ? " 接受" : " 丢弃"));
                if (better) {
                    best = solved;
                    acceptedThisPass++;
                    totalAccepted++;
                }
            }
            passesUsed = pass;
            if (acceptedThisPass == 0) {
                // 平台期：本轮预算下没有任何切片能再改进，继续加预算收益有限，收工
                log.info("Fix-and-Optimize: 第 {} 轮无接受（当前解已局部稳定），停止", pass);
                break;
            }
            // best 已更新 → 重新计算剩余冲突切片；全部消除即提前收工
            todo = ConflictSliceSelector.slices(best);
        }

        if (totalAccepted == 0) {
            log.info("Fix-and-Optimize: {} 轮共治疗 {} 个切片后无改进（当前解已局部稳定）",
                    passesUsed, totalTreated);
            return Optional.empty();
        }
        log.info("Fix-and-Optimize: {} 轮升级重排，治疗 {}/{} 个切片，接受 {} 个，score {} → {}",
                passesUsed, totalTreated, slicesTotal, totalAccepted, seed.getScore(), best.getScore());
        if (info != null) {
            info.put("slicesTotal", slicesTotal);
            info.put("slicesTreated", totalTreated);
            info.put("passes", passesUsed);
            info.put("accepted", totalAccepted);
            info.put("score", seed.getScore() + " → " + best.getScore());
            info.put("trace", trace);
        }
        return Optional.of(best);
    }

    /** 统计快照（供外部检视；不暴露可变结构） */
    public Map<String, Object> stats(List<Slice> slices) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Slice s : slices) {
            m.put(s.name(), s.units().size() + " 项 / " + s.clashCount() + " 冲突");
        }
        return m;
    }
}
