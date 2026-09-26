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

    /**
     * 在已有解上做 Fix-and-Optimize 精修。
     *
     * @param seed        上游给出的解（必须已带评分）
     * @param maxSlices   最多精确重排的切片数（按病灶严重程度取前 N 片）
     * @param sliceBudget 每片重排的求解时间预算
     * @return 严格更优的解；无冲突、无改进或输入不可用时返回空
     */
    public Optional<SchedulePlan> optimize(SchedulePlan seed, int maxSlices, Duration sliceBudget) {
        return optimize(seed, maxSlices, sliceBudget, ScheduleOptimizer.RANDOM_SEED, null);
    }

    /** 显式随机种子（测试用），并把过程统计写入 info（可为 null） */
    public Optional<SchedulePlan> optimize(SchedulePlan seed, int maxSlices, Duration sliceBudget,
                                           long seedValue, Map<String, Object> info) {
        if (seed == null || seed.getUnits() == null || seed.getUnits().isEmpty()
                || seed.getScore() == null || maxSlices <= 0
                || sliceBudget == null || sliceBudget.isNegative() || sliceBudget.isZero()) {
            return Optional.empty();
        }
        List<Slice> slices = ConflictSliceSelector.slices(seed);
        if (slices.isEmpty()) {
            log.info("Fix-and-Optimize: 当前解无兼项冲突，无需手术");
            return Optional.empty();
        }

        int rounds = Math.min(maxSlices, slices.size());
        SchedulePlan best = seed.deepCopy();
        int accepted = 0;
        List<String> trace = new java.util.ArrayList<>();

        for (int i = 0; i < rounds; i++) {
            Slice slice = slices.get(i);
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
                    .solve(work, sliceBudget, LocalSearchType.SIMULATED_ANNEALING, seedValue + i * 101L)
                    .orElse(null);
            if (solved == null || solved.getScore() == null) continue;
            boolean better = solved.getScore().compareTo(best.getScore()) > 0;
            trace.add(slice.name() + "(" + slice.units().size() + "项/" + slice.clashCount() + "冲突)："
                    + solved.getScore() + (better ? " 接受" : " 丢弃"));
            if (better) {
                best = solved;
                accepted++;
            }
        }

        if (accepted == 0) {
            log.info("Fix-and-Optimize: {} 个切片重排后无改进（当前解已局部稳定）", rounds);
            return Optional.empty();
        }
        log.info("Fix-and-Optimize: {} 个切片中接受 {} 个，score {} → {}",
                rounds, accepted, seed.getScore(), best.getScore());
        if (info != null) {
            info.put("slicesTotal", slices.size());
            info.put("slicesTreated", rounds);
            info.put("accepted", accepted);
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
