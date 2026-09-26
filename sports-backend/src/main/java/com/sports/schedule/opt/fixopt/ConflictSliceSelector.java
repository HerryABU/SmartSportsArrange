package com.sports.schedule.opt.fixopt;

import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.schedule.opt.solver.SchedulePlan;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冲突切片选择器：把当前解的<b>兼项冲突图</b>切成连通分量（切片），
 * 供 Fix-and-Optimize 逐片「冻结其余、精确重排」。
 *
 * <h2>为什么切片是连通分量而不是单个冲突对</h2>
 * 冲突会传递：A 撞 B、B 又共享运动员牵连 C。只放开 A、B 重排，C 的既成事实
 * 会把它们重新压回老位置。按连通分量切片，一次放开「整条冲突链」，
 * 修复求解器才有足够自由度把这串人真正错开——这正是 Fix-and-Optimize
 * 「解多个小规模子问题优于解一个大问题」的切片依据。
 *
 * <p>纯函数、无 Spring 依赖；解无冲突时返回空列表（没有病就不用治）。</p>
 */
public final class ConflictSliceSelector {

    private ConflictSliceSelector() {
    }

    /**
     * 一个待重排的冲突切片。
     *
     * @param name       切片名（日志与统计用）
     * @param units      切片内的单元（全部已排、且处于同一冲突连通分量）
     * @param clashCount 切片内部的兼项冲突边数（衡量病灶严重程度）
     */
    public record Slice(String name, List<ScheduleUnit> units, int clashCount) {
    }

    /**
     * 计算当前解的全部冲突切片，按「冲突边数多者在前」排序——
     * 优先治最重的病，预算有限时最大病灶先得到精确重排。
     */
    public static List<Slice> slices(SchedulePlan plan) {
        List<ScheduleUnit> placed = new ArrayList<>();
        for (ScheduleUnit u : plan.getUnits()) {
            if (u.isPlaced() && u.hasAthletes()) {
                placed.add(u);
            }
        }

        // ① 冲突边表（无向）：共享运动员且时间重叠（含缓冲）
        Map<Integer, List<Integer>> adjacency = new LinkedHashMap<>();
        int edges = 0;
        for (int i = 0; i < placed.size(); i++) {
            ScheduleUnit a = placed.get(i);
            for (int j = i + 1; j < placed.size(); j++) {
                ScheduleUnit b = placed.get(j);
                if (a.sharesAthlete(b) && ScheduleConstraintProvider.athleteClash(a, b)) {
                    adjacency.computeIfAbsent(i, k -> new ArrayList<>()).add(j);
                    adjacency.computeIfAbsent(j, k -> new ArrayList<>()).add(i);
                    edges++;
                }
            }
        }
        if (edges == 0) {
            return List.of();
        }

        // ② 连通分量（BFS），每个分量计内部冲突边数
        boolean[] visited = new boolean[placed.size()];
        List<Slice> slices = new ArrayList<>();
        for (int s = 0; s < placed.size(); s++) {
            if (visited[s] || !adjacency.containsKey(s)) continue;
            List<Integer> component = new ArrayList<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(s);
            visited[s] = true;
            while (!stack.isEmpty()) {
                int cur = stack.pop();
                component.add(cur);
                for (int next : adjacency.get(cur)) {
                    if (!visited[next]) {
                        visited[next] = true;
                        stack.push(next);
                    }
                }
            }
            int internalEdges = 0;
            for (int idx : component) {
                internalEdges += adjacency.get(idx).size();
            }
            internalEdges /= 2;   // 无向边计了两次
            List<ScheduleUnit> units = new ArrayList<>(component.size());
            for (int idx : component) {
                units.add(placed.get(idx));
            }
            slices.add(new Slice("冲突簇#" + (slices.size() + 1), units, internalEdges));
        }

        // ③ 最重的病灶排最前
        slices.sort((a, b) -> {
            int byEdges = Integer.compare(b.clashCount(), a.clashCount());
            return byEdges != 0 ? byEdges : Integer.compare(b.units().size(), a.units().size());
        });
        return slices;
    }
}
