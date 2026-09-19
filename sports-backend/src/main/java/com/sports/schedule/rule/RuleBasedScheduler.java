package com.sports.schedule.rule;

import com.sports.schedule.opt.Placement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则模式编排器（Rule-Based Scheduler）—— 三级求解梯度的最低层。
 *
 * <p><b>定位</b>：竞品（豪杰/索美）「配置参数 → 生成结果」那套规则驱动的精确实现。
 * 确定性、毫秒级、可解释；同时作为优化模式的<b>初始解生成器</b>与求解失败时的<b>兜底</b>。</p>
 *
 * <p><b>算法（全部 O(n·C)，C=候选位置数，无任何随机）</b>：</p>
 * <ol>
 *   <li>按输入顺序（服务层已按 项目出场序 × 年级序 展开）逐单元处理；</li>
 *   <li>每个单元取候选里「窗口最靠前 → 起点最早 → 槽位号最小」的<b>第一个能整块放下且
 *       无兼项冲突</b>的位置（first-fit，与竞品行为一致）；</li>
 *   <li>若该位置与已排单元的运动员撞车（时间重叠，或间隔小于配置的兼项缓冲），
 *       顺次考察后续候选，取<b>冲突最少</b>者（不粉饰：冲突如实计数上报）；</li>
 *   <li>时长 = min(rawDuration, 位置容量) 且 ≥ minDuration（不压缩，除非窗口物理放不下整块）；</li>
 *   <li>同 groupKey 的田赛分组单元强制「同日同分钟」开赛（取组内最晚可行起点）。</li>
 * </ol>
 *
 * <p><b>纪律</b>：本类不落库、不读库、不知道任何 Repository——纯函数式地「单元列表 → 位置方案」，
 * 与 {@code ScheduleVerifier}/{@code LowerBoundEstimator} 一样可独立单测。</p>
 */
@Slf4j
@Component
public class RuleBasedScheduler {

    /** 单个单元的规则编排结果 */
    public record Assignment(Placement placement, int duration, int residualConflicts) {
    }

    /** 整批编排结果 */
    public record RulePlan(
            Map<String, Assignment> assignments,   // key → 落位（未排下的单元不在此 Map）
            int placedCount,
            int unplacedCount,
            int residualConflicts,
            long elapsedMillis) {
    }

    /**
     * 规则模式主入口：确定性编排。
     *
     * @param units  编排单元（顺序即出场顺序）
     * @param config 规则配置
     * @return 每单元的落位方案（placement + duration）；排不下的单元不在结果里，由调用方如实告警
     */
    public RulePlan plan(List<RuleUnit> units, RuleScheduleConfig config) {
        long t0 = System.currentTimeMillis();
        Map<String, Assignment> out = new LinkedHashMap<>();
        // 并发位占用表：binKey → 已占区间 [start, end)（绝对分钟 = day*1440 + minute）
        Map<String, List<int[]>> binUsed = new HashMap<>();
        // 运动员占用表：athleteId → 已占区间（绝对分钟），用于兼项规避
        Map<Long, List<int[]>> busy = new HashMap<>();
        // 组次开赛时刻登记：groupKey@day → startMinute（同组同时开赛）
        Map<String, Integer> groupStart = new HashMap<>();

        int residual = 0;
        // 第一遍：普通单元；第二遍：分组单元（组内同步需要看到普通单元的占用）
        List<RuleUnit> plain = new ArrayList<>();
        List<RuleUnit> grouped = new ArrayList<>();
        for (RuleUnit u : units) {
            (u.groupKey() == null ? plain : grouped).add(u);
        }
        // 候选改为「时间优先」序：同一时刻先扫完各槽位（实现并发池并行），再顺延时间。
        // 这是与 placementsOf 的「槽位优先」序的关键差别——槽位优先会把同池项目串行堆在同一槽，
        // 时间优先才符合「径赛串行、田赛并行」的规则语义。排序稳定且确定。
        Map<String, List<Placement>> sortedCands = new HashMap<>();
        for (RuleUnit u : units) {
            List<Placement> c = new ArrayList<>(u.candidates());
            c.sort((a, b) -> {
                int byStart = Integer.compare(a.getAbsoluteStartMinute(), b.getAbsoluteStartMinute());
                if (byStart != 0) return byStart;
                return Integer.compare(a.getSlotIdx(), b.getSlotIdx());
            });
            sortedCands.put(u.key(), c);
        }

        for (RuleUnit u : plain) {
            residual += placeOne(u, sortedCands.get(u.key()), config, binUsed, busy, groupStart, out);
        }
        for (RuleUnit u : grouped) {
            residual += placeOne(u, sortedCands.get(u.key()), config, binUsed, busy, groupStart, out);
        }

        int placed = out.size();
        RulePlan plan = new RulePlan(out, placed, units.size() - placed, residual,
                System.currentTimeMillis() - t0);
        log.info("规则编排: {} 个单元, 排入 {} 个, 未排 {} 个, 残余兼项冲突 {} 处, 耗时 {}ms",
                units.size(), plan.placedCount(), plan.unplacedCount(), plan.residualConflicts(),
                plan.elapsedMillis());
        return plan;
    }

    /** 单个单元的确定性落位；返回残余冲突数 */
    private int placeOne(RuleUnit u, List<Placement> candidates, RuleScheduleConfig config,
                         Map<String, List<int[]>> binUsed, Map<Long, List<int[]>> busy,
                         Map<String, Integer> groupStart, Map<String, Assignment> out) {
        // 组次同时开赛约束：同组已定起点 → 只考察与之一致的候选
        Integer forcedStart = null;
        if (u.groupKey() != null) {
            for (Map.Entry<String, Integer> e : groupStart.entrySet()) {
                if (e.getKey().startsWith(u.groupKey() + "@")) {
                    forcedStart = e.getValue();
                    break;
                }
            }
        }

        Placement best = null;
        int bestDur = 0;
        int bestConflicts = Integer.MAX_VALUE;
        for (Placement p : candidates) {
            if (forcedStart != null && p.getStartMinute() != forcedStart) continue;
            int dur = Math.max(u.minDuration(), Math.min(u.rawDuration(), p.getMaxDuration()));
            if (dur > p.getMaxDuration()) continue;                    // 整块放不下
            if (p.getMaxDuration() < u.minDuration()) continue;        // 低于红线
            // 并发位独占 + 项目间隔（与 Cursor.place 语义一致：无已占区间时不留前置间隔）
            if (overlaps(binUsed, p.getBinKey(), p.getAbsoluteStartMinute(),
                    p.getAbsoluteStartMinute() + dur, u.interval())) continue;
            int conflicts = config.conflictCheckEnabled()
                    ? countConflicts(u.athletes(), p.getAbsoluteStartMinute(),
                    p.getAbsoluteStartMinute() + dur, busy, config.conflictBufferMinutes())
                    : 0;
            if (conflicts < bestConflicts) {                           // 候选已按时间/槽位排好序
                best = p;
                bestDur = dur;
                bestConflicts = conflicts;
                if (conflicts == 0) break;                             // first-fit：零冲突即取
            }
        }
        if (best == null) {
            return 0;   // 未排下：如实留给调用方告警（不粉饰）
        }
        reserve(binUsed, best.getBinKey(), best.getAbsoluteStartMinute(), best.getAbsoluteStartMinute() + bestDur);
        for (long aid : u.athletes()) {
            busy.computeIfAbsent(aid, k -> new ArrayList<>())
                    .add(new int[]{best.getAbsoluteStartMinute(), best.getAbsoluteStartMinute() + bestDur});
        }
        if (u.groupKey() != null) {
            groupStart.put(u.groupKey() + "@" + best.getDay(), best.getStartMinute());
        }
        out.put(u.key(), new Assignment(best, bestDur, bestConflicts));
        return bestConflicts;
    }

    /** 区间是否与占用表重叠（buffer=额外界外缓冲分钟） */
    private static boolean overlaps(Map<String, List<int[]>> used, String binKey,
                                    int start, int end, int buffer) {
        List<int[]> spans = used.get(binKey);
        if (spans == null) return false;
        for (int[] s : spans) {
            if (start < s[1] + buffer && s[0] - buffer < end) return true;
        }
        return false;
    }

    private static void reserve(Map<String, List<int[]>> used, String binKey, int start, int end) {
        used.computeIfAbsent(binKey, k -> new ArrayList<>()).add(new int[]{start, end});
    }

    private static int countConflicts(long[] athletes, int start, int end,
                                      Map<Long, List<int[]>> busy, int bufferMinutes) {
        int n = 0;
        for (long aid : athletes) {
            List<int[]> spans = busy.get(aid);
            if (spans == null) continue;
            for (int[] s : spans) {
                if (start < s[1] + bufferMinutes && s[0] - bufferMinutes < end) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }
}
