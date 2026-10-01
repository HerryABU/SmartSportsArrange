package com.sports.schedule.analysis;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 赛程可解性诊断：回答「排得下吗？排不下的是什么？该怎么办？」。
 *
 * <p>与「求解器」的分工：求解器负责在可行域里搜索<b>更优</b>的解；本服务负责在动手之前
 * 先把<b>可行性的边界</b>算清楚，并在排不下时把「不可解冲突」结构化地交给上层程序
 * （班主任拿到「谁该退哪一项」，教务处拿到「还差几天 / 还差几条赛道」）。</p>
 *
 * <h3>三类「排不下」必须分开</h3>
 * <ol>
 *   <li><b>容量缺口</b>：某池总需求 &gt; 总供给 → 加天数 / 加并发位 / 减项目，与算法无关；</li>
 *   <li><b>超大单元</b>：单个单元的整块时长超过任一时段容量 → 必须<b>拆批</b>，
 *       否则它在数据结构上就放不进任何位置（真实规模下的主要根因）；</li>
 *   <li><b>团下界</b>：冲突图最大团是「两两互相冲突、必须错开到不同时段」的单元集合，
 *       团长 &gt; 可用时段数即结构性不可解——加场地无效，只能拆组次或取消报名。</li>
 * </ol>
 *
 * <h3>与 Python 训练侧的关系</h3>
 * <p>报告 schema 与 {@code sports-ai/sports_ai/solve/report.py} 对齐（{@value #SCHEMA}），
 * 同一份结构既能由 Python 离线压测产出，也能由本服务在线产出，
 * 便于把「地狱级测试暴露的问题」与「生产环境遇到的问题」放在同一张表上比对。</p>
 */
@Service
@Slf4j
public class ScheduleFeasibilityService {

    /** 报告 schema 版本（与 Python 侧一致，供消费方做兼容判断）。 */
    public static final String SCHEMA = "sports-ai/infeasibility-report@1";

    /** 诊断用的局部搜索轮数（诊断要快，不做重型精修；真正的精修交给编排主链）。 */
    private static final int DEFAULT_ROUNDS = 4;

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 诊断一份编排计划的可解性。
     *
     * @param units         待排单元（{@link ScheduleUnit}，含报名运动员与真实用时）
     * @param allPlacements 候选位置全集（各并发池的时段栅格）
     * @return 结构化报告（schema 见 {@link #SCHEMA}）
     */
    public Map<String, Object> diagnose(List<ScheduleUnit> units, List<Placement> allPlacements) {
        List<HeatTask> tasks = expandHeats(units);
        List<Bin> bins = buildBins(allPlacements);
        Map<String, Object> bounds = analyzeBounds(units, bins);
        SolveResult res = solve(tasks, bins, DEFAULT_ROUNDS);
        Map<String, Object> report = buildReport(units, bins, bounds, res);
        log.info("可解性诊断：{}/{} 个组次可排（{}），未排 {} 组次；最少 {} 天；团下界 {} vs 时段 {}；超大单元 {}",
                res.placedTasks, res.tasksTotal, percent(res.placedTasks, res.tasksTotal),
                res.tasksTotal - res.placedTasks, bounds.get("minDaysByCapacity"),
                bounds.get("cliqueLowerBound"), bounds.get("availablePeriods"),
                bounds.get("oversizedCount"));
        return report;
    }

    // ------------------------------------------------------------------
    // ① 拆批
    // ------------------------------------------------------------------

    /**
     * 把单元展开成组次任务。
     *
     * <p>单元的真实用时（{@code rawDuration}）是「全部时长」，真实规模下可达数百分钟，
     * 远超单个时段容量；不拆批就会「整块放不下」。拆批粒度取 {@code minDuration}
     * （现场已不可再短的单批下限），批次数 = ⌈全部时长 / 单批下限⌉。</p>
     *
     * <p>运动员按名单顺序<b>不重不漏</b>地均分到各批——这一点很关键：同一单元的不同批次
     * 之间不存在兼项冲突，于是把一个单元摊到多个时段反而<b>降低</b>了与其他项目的冲突面。</p>
     */
    List<HeatTask> expandHeats(List<ScheduleUnit> units) {
        List<HeatTask> out = new ArrayList<>();
        int uid = 0;
        for (int i = 0; i < units.size(); i++) {
            ScheduleUnit u = units.get(i);
            long[] ath = u.getAthletes() == null ? new long[0] : u.getAthletes();
            int round = (u.getEventName() != null && u.getEventName().contains("决赛")) ? 1 : 0;
            long eventId = u.getEventId() == null ? -1L : u.getEventId();
            int raw = Math.max(1, u.getRawDuration());
            int batchMin = Math.max(1, u.getMinDuration());
            if (ath.length == 0) {
                out.add(new HeatTask(uid++, i, u.getKey(), eventId, u.getPoolLabel(), u.getGrade(),
                        nullToEmpty(u.getEventName()), 0, 1, raw, ath, round));
                continue;
            }
            int n = Math.max(1, (raw + batchMin - 1) / batchMin);
            int per = Math.max(1, raw / n);
            int base = ath.length / n;
            int rem = ath.length % n;
            int pos = 0;
            for (int b = 0; b < n; b++) {
                int len = base + (b < rem ? 1 : 0);
                if (len <= 0) continue;
                long[] sub = Arrays.copyOfRange(ath, pos, pos + len);
                pos += len;
                out.add(new HeatTask(uid++, i, u.getKey(), eventId, u.getPoolLabel(), u.getGrade(),
                        nullToEmpty(u.getEventName()), b, n, per, sub, round));
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // ② 并发位
    // ------------------------------------------------------------------

    /** 时段键：跨天唯一（day × 1000 + 窗口序号），同一时段的所有并发位在时间上重叠。 */
    private static long periodKey(Placement p) {
        return (long) p.getDay() * 1000L + p.getWindowIdx();
    }

    List<Bin> buildBins(List<Placement> placements) {
        Map<String, Placement> seen = new LinkedHashMap<>();
        for (Placement p : placements) {
            seen.putIfAbsent(p.getBinKey(), p);
        }
        TreeMap<Long, Integer> periodIdx = new TreeMap<>();
        for (Placement p : seen.values()) {
            periodIdx.putIfAbsent(periodKey(p), 0);
        }
        int idx = 0;
        for (Map.Entry<Long, Integer> e : periodIdx.entrySet()) {
            e.setValue(idx++);
        }
        List<Bin> bins = new ArrayList<>();
        for (Placement p : seen.values()) {
            bins.add(new Bin(p.getBinKey(), p.getDay(), p.getWindowIdx(), p.getPoolLabel(),
                    p.getSlotIdx(), p.getWindowCapacity(), periodIdx.get(periodKey(p))));
        }
        bins.sort(Comparator.comparingInt(Bin::periodIdx)
                .thenComparing(Bin::pool).thenComparingInt(Bin::lane));
        return bins;
    }

    // ------------------------------------------------------------------
    // ③ 下界分析
    // ------------------------------------------------------------------

    Map<String, Object> analyzeBounds(List<ScheduleUnit> units, List<Bin> bins) {
        Set<String> pools = new LinkedHashSet<>();
        for (Bin b : bins) pools.add(b.pool());

        Map<String, Object> perPool = new LinkedHashMap<>();
        int minDays = 1;
        int availablePeriods = (int) bins.stream().map(Bin::periodIdx).distinct().count();

        for (String pool : pools) {
            int demand = 0;
            for (ScheduleUnit u : units) {
                if (pool.equals(u.getPoolLabel())) demand += Math.max(0, u.getRawDuration());
            }
            Map<String, Integer> binCap = new LinkedHashMap<>();
            for (Bin b : bins) {
                if (pool.equals(b.pool())) binCap.putIfAbsent(b.key(), b.capacity());
            }
            int supply = binCap.values().stream().mapToInt(Integer::intValue).sum();
            int lanes = (int) bins.stream().filter(b -> pool.equals(b.pool()))
                    .map(Bin::lane).distinct().count();
            // 每天供给 = 并发位 × 单日时段容量之和。
            // ⚠️ 只取 day=1：时段键是跨天唯一的，直接聚合会把多天容量累加进来，导致「最少天数」被严重低估。
            Map<Integer, Integer> dayCaps = new LinkedHashMap<>();
            for (Bin b : bins) {
                if (pool.equals(b.pool()) && b.day() == 1) dayCaps.putIfAbsent(b.window(), b.capacity());
            }
            int perDay = Math.max(1, lanes) * dayCaps.values().stream().mapToInt(Integer::intValue).sum();
            int need = perDay > 0 ? (demand + perDay - 1) / perDay : 0;
            minDays = Math.max(minDays, need);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("demand", demand);
            info.put("supply", supply);
            info.put("shortfall", Math.max(0, demand - supply));
            info.put("lanes", lanes);
            info.put("minDays", need);
            info.put("feasibleByCapacity", demand <= supply);
            perPool.put(pool, info);
        }

        // 超大单元：全部时长超过「该池单时段最大容量」→ 不拆批就放不进去
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (ScheduleUnit u : units) {
            int cap = bins.stream().filter(b -> b.pool().equals(u.getPoolLabel()))
                    .mapToInt(Bin::capacity).max().orElse(0);
            if (cap > 0 && u.getRawDuration() > cap) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("unit", u.getKey());
                o.put("event", u.getEventName());
                o.put("duration", u.getRawDuration());
                o.put("maxWindowCapacity", cap);
                o.put("minSplits", (u.getRawDuration() + cap - 1) / cap);
                oversized.add(o);
            }
        }

        int clique = cliqueLowerBound(units);

        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("pools", perPool);
        bounds.put("availablePeriods", availablePeriods);
        bounds.put("minDaysByCapacity", minDays);
        bounds.put("oversizedUnits", oversized);
        bounds.put("oversizedCount", oversized.size());
        bounds.put("cliqueLowerBound", clique);
        bounds.put("cliqueFeasible", clique <= availablePeriods);
        bounds.put("capacityFeasible", perPool.values().stream()
                .allMatch(v -> Boolean.TRUE.equals(((Map<?, ?>) v).get("feasibleByCapacity"))));
        return bounds;
    }

    /**
     * 冲突图最大团的<b>下界</b>（贪心团：以每个顶点为种子，尽量扩充）。
     *
     * <p>用下界而非精确值，是因为真实学校的单元数可能上百，精确最大团（Bron–Kerbosch）
     * 最坏情况代价过高；而本指标只需判断「是否已超过可用时段数」，
     * 下界一旦超过就足以定性为结构性不可解，不会误报「可解」。</p>
     */
    int cliqueLowerBound(List<ScheduleUnit> units) {
        int n = units.size();
        if (n < 2) return n;
        boolean[][] adj = new boolean[n][n];
        Map<Long, List<Integer>> byAthlete = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long[] a = units.get(i).getAthletes();
            if (a == null) continue;
            for (long x : a) {
                byAthlete.computeIfAbsent(x, k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> idxs : byAthlete.values()) {
            for (int x = 0; x < idxs.size(); x++) {
                for (int y = x + 1; y < idxs.size(); y++) {
                    int a = idxs.get(x);
                    int b = idxs.get(y);
                    adj[a][b] = true;
                    adj[b][a] = true;
                }
            }
        }
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (adj[i][j]) degree[i]++;
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt((Integer v) -> -degree[v]));

        int best = 1;
        for (int seed : order) {
            List<Integer> clique = new ArrayList<>();
            clique.add(seed);
            for (int v = 0; v < n; v++) {
                if (v == seed) continue;
                boolean ok = true;
                for (int c : clique) {
                    if (!adj[c][v]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) clique.add(v);
            }
            best = Math.max(best, clique.size());
        }
        return best;
    }

    // ------------------------------------------------------------------
    // ④ 尽量可解：分批装箱 + 局部搜索
    // ------------------------------------------------------------------

    SolveResult solve(List<HeatTask> tasks, List<Bin> bins, int rounds) {
        SolveResult res = new SolveResult();
        res.tasksTotal = tasks.size();
        res.binsTotal = bins.size();
        res.totalAthleteSlots = tasks.stream().mapToInt(t -> t.athletes().length).sum();
        if (tasks.isEmpty() || bins.isEmpty()) {
            res.unplaced = new ArrayList<>(tasks);
            res.unplacedAthleteSlots = res.totalAthleteSlots;
            return res;
        }

        State forward = new State(tasks, bins);
        greedy(forward, tasks, false);
        State backward = new State(tasks, bins);
        greedy(backward, tasks, true);
        State st = backward.currentCost()[0] < forward.currentCost()[0]
                || (backward.currentCost()[0] == forward.currentCost()[0]
                    && compare(backward.currentCost(), forward.currentCost()) < 0)
                ? backward : forward;
        res.roundsRun = localSearch(st, tasks, rounds);

        for (HeatTask t : tasks) {
            String bk = st.assign.get(t.uid());
            if (bk == null) res.unplaced.add(t);
            else res.assigns.add(new Assigned(t, st.binOf.get(bk)));
        }
        res.placedTasks = res.assigns.size();
        res.unplacedAthleteSlots = res.unplaced.stream().mapToInt(t -> t.athletes().length).sum();
        res.conflictMultiplicity = st.periodConf.values().stream().mapToInt(Integer::intValue).sum();
        res.cost = st.currentCost();
        log.debug("可解性诊断求解：正排 {} / 倒排 {} → 采用 {}",
                Arrays.toString(forward.currentCost()), Arrays.toString(backward.currentCost()),
                Arrays.toString(st.currentCost()));
        return res;
    }

    /** 贪心构造：正排选最早可行位；倒排（先决赛占尾段）选最晚可行位。 */
    private void greedy(State st, List<HeatTask> tasks, boolean backward) {
        List<HeatTask> order = new ArrayList<>(tasks);
        order.sort(Comparator
                .comparingInt((HeatTask t) -> backward ? -t.roundOrder() : t.roundOrder())
                .thenComparingInt((HeatTask t) -> -t.duration())
                .thenComparingInt((HeatTask t) -> -t.athletes().length)
                .thenComparingInt(HeatTask::uid));
        for (HeatTask t : order) {
            List<Bin> cands = st.candidates(t, false);
            if (cands.isEmpty()) continue;
            Bin best = null;
            int[] bestKey = null;
            for (Bin b : cands) {
                int[] key = {st.conflictDelta(t, b), backward ? -b.periodIdx() : b.periodIdx(),
                        -st.load.getOrDefault(b.key(), 0)};
                if (bestKey == null || compare(key, bestKey) < 0) {
                    bestKey = key;
                    best = b;
                }
            }
            if (best != null) st.apply(t, best);
        }
    }

    /** 单任务重定位爬山：代价为字典序 (未排组次, 未排人次, 兼项重叠)。 */
    private int localSearch(State st, List<HeatTask> tasks, int rounds) {
        int used = 0;
        List<HeatTask> order = new ArrayList<>(tasks);
        for (int r = 0; r < rounds; r++) {
            boolean improved = false;
            for (HeatTask t : order) {
                String curKey = st.assign.get(t.uid());
                Bin cur = curKey == null ? null : st.binOf.get(curKey);
                Bin best = cur;
                int[] bestCost = st.costAfter(t, cur);
                for (Bin b : st.candidates(t, true)) {
                    if (cur != null && b.key().equals(cur.key())) continue;
                    int[] c = st.costAfter(t, b);
                    if (compare(c, bestCost) < 0) {
                        bestCost = c;
                        best = b;
                    }
                }
                if (best != cur) {
                    st.apply(t, best);
                    improved = true;
                }
            }
            used = r + 1;
            if (!improved) break;
        }
        return used;
    }

    // ------------------------------------------------------------------
    // ⑤ 报告
    // ------------------------------------------------------------------

    Map<String, Object> buildReport(List<ScheduleUnit> units, List<Bin> bins,
                                    Map<String, Object> bounds, SolveResult res) {
        Set<Long> athletes = new LinkedHashSet<>();
        Set<String> unitKeys = new LinkedHashSet<>();
        for (ScheduleUnit u : units) {
            unitKeys.add(u.getKey());
            long[] a = u.getAthletes();
            if (a != null) {
                for (long x : a) athletes.add(x);
            }
        }
        Map<String, Object> poolLanes = new LinkedHashMap<>();
        Map<?, ?> pools = (Map<?, ?>) bounds.get("pools");
        for (Map.Entry<?, ?> e : pools.entrySet()) {
            poolLanes.put(String.valueOf(e.getKey()), ((Map<?, ?>) e.getValue()).get("lanes"));
        }

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("athletes", athletes.size());
        scene.put("units", unitKeys.size());
        scene.put("heatTasks", res.tasksTotal);
        scene.put("days", bins.stream().map(Bin::day).distinct().count());
        scene.put("periods", bins.stream().map(Bin::periodIdx).distinct().count());
        scene.put("poolLanes", poolLanes);

        int unplaced = res.tasksTotal - res.placedTasks;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tasks", res.tasksTotal);
        summary.put("placed", res.placedTasks);
        summary.put("unplaced", unplaced);
        summary.put("placedRatio", res.tasksTotal == 0 ? 1.0 : round4((double) res.placedTasks / res.tasksTotal));
        summary.put("athleteSlotsTotal", res.totalAthleteSlots);
        summary.put("athleteSlotsPlaced", res.totalAthleteSlots - res.unplacedAthleteSlots);
        summary.put("athleteSlotsUnplaced", res.unplacedAthleteSlots);
        summary.put("conflictMultiplicity", res.conflictMultiplicity);
        summary.put("conflictAthletes", athleteClashRows(res).size());
        summary.put("unplacedUnits", res.unplaced.stream().map(HeatTask::unitKey).distinct().toList());

        // 未排组次（含原因）
        List<Map<String, Object>> unplacedTasks = new ArrayList<>();
        for (HeatTask t : res.unplaced) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("unit", t.unitKey());
            row.put("batch", t.batch());
            row.put("ofBatches", t.nBatches());
            row.put("pool", t.pool());
            row.put("grade", t.grade());
            row.put("duration", t.duration());
            row.put("athleteCount", t.athletes().length);
            row.put("round", t.roundOrder() == 1 ? "final" : "prelim");
            row.put("reason", unplacedReason(t.pool(), bounds));
            unplacedTasks.add(row);
        }

        // ---------- 分类冲突 ----------
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<?, ?> e : pools.entrySet()) {
            Map<?, ?> info = (Map<?, ?>) e.getValue();
            int shortfall = number(info.get("shortfall"));
            if (shortfall > 0) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("type", "capacity_shortfall");
                c.put("pool", e.getKey());
                c.put("demandMinutes", info.get("demand"));
                c.put("supplyMinutes", info.get("supply"));
                c.put("shortfallMinutes", shortfall);
                c.put("minDaysRequired", info.get("minDays"));
                c.put("message", e.getKey() + "容量不足，缺 " + shortfall + " 分钟");
                conflicts.add(c);
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> oversized = (List<Map<String, Object>>) bounds.get("oversizedUnits");
        for (Map<String, Object> o : oversized) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "oversized_unit");
            c.put("unit", o.get("unit"));
            c.put("durationMinutes", o.get("duration"));
            c.put("maxWindowCapacity", o.get("maxWindowCapacity"));
            c.put("minSplits", o.get("minSplits"));
            c.put("message", "单元「" + o.get("unit") + "」全部时长 " + o.get("duration")
                    + " 分钟超过单时段容量 " + o.get("maxWindowCapacity") + " 分钟，必须拆批");
            conflicts.add(c);
        }
        if (Boolean.FALSE.equals(bounds.get("cliqueFeasible"))) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "clique_exceeds_periods");
            c.put("cliqueSize", bounds.get("cliqueLowerBound"));
            c.put("availablePeriods", bounds.get("availablePeriods"));
            c.put("message", "冲突图最大团 " + bounds.get("cliqueLowerBound") + " > 可用时段数 "
                    + bounds.get("availablePeriods") + "，结构性不可解（加场地无效，需拆组次或取消报名）");
            conflicts.add(c);
        }
        List<Map<String, Object>> clashRows = athleteClashRows(res);
        conflicts.addAll(clashRows.subList(0, Math.min(15, clashRows.size())));
        if (clashRows.size() > 15) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "athlete_clash_summary");
            c.put("totalAthletes", clashRows.size());
            c.put("message", "另有 " + (clashRows.size() - 15) + " 名运动员存在兼项重叠");
            conflicts.add(c);
        }

        // ---------- 建议动作 ----------
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map.Entry<?, ?> e : pools.entrySet()) {
            Map<?, ?> info = (Map<?, ?>) e.getValue();
            if (number(info.get("shortfall")) > 0) {
                Map<String, Object> a1 = new LinkedHashMap<>();
                a1.put("action", "extend_days");
                a1.put("scope", e.getKey());
                a1.put("needDays", info.get("minDays"));
                a1.put("gain", e.getKey() + "容量补齐");
                actions.add(a1);

                Map<String, Object> a2 = new LinkedHashMap<>();
                a2.put("action", "add_lanes");
                a2.put("scope", e.getKey());
                a2.put("currentLanes", info.get("lanes"));
                a2.put("reason", "或为" + e.getKey() + "增加并发位/场地，可等价于延长天数");
                actions.add(a2);
            }
        }
        for (Map<String, Object> c : clashRows.subList(0, Math.min(10, clashRows.size()))) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("action", "cancel_entry");
            a.put("athlete", c.get("athlete"));
            a.put("candidateUnits", c.get("units"));
            a.put("reason", "该运动员兼项在其可用时段内无法错开，建议取消其中一项报名（交由班主任确认）");
            actions.add(a);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", SCHEMA);
        report.put("feasible", unplaced == 0 && res.conflictMultiplicity == 0);
        report.put("scene", scene);
        report.put("summary", summary);
        report.put("bounds", bounds);
        report.put("unplacedTasks", unplacedTasks);
        report.put("conflicts", conflicts);
        report.put("actions", actions);
        return report;
    }

    /** 残留兼项冲突按运动员聚合（给班主任的可执行清单）。 */
    private List<Map<String, Object>> athleteClashRows(SolveResult res) {
        Map<Integer, Map<Long, Integer>> periodAth = new HashMap<>();
        for (Assigned a : res.assigns) {
            Map<Long, Integer> c = periodAth.computeIfAbsent(a.bin().periodIdx(), k -> new HashMap<>());
            for (long x : a.task().athletes()) {
                c.merge(x, 1, Integer::sum);
            }
        }
        Map<Long, List<Map<String, Object>>> byAthlete = new LinkedHashMap<>();
        for (Assigned a : res.assigns) {
            Map<Long, Integer> c = periodAth.getOrDefault(a.bin().periodIdx(), Map.of());
            for (long x : a.task().athletes()) {
                if (c.getOrDefault(x, 0) > 1) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("period", a.bin().periodIdx());
                    row.put("day", a.bin().day());
                    row.put("unit", a.task().unitKey());
                    byAthlete.computeIfAbsent(x, k -> new ArrayList<>()).add(row);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> e : byAthlete.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "athlete_clash");
            c.put("athlete", e.getKey());
            c.put("clashCount", e.getValue().size());
            c.put("units", e.getValue().stream().map(r -> (String) r.get("unit")).distinct().toList());
            c.put("detail", e.getValue());
            c.put("message", "运动员 " + e.getKey() + " 在 " + e.getValue().size() + " 个时段同时段出现于多个项目");
            out.add(c);
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> -(int) m.get("clashCount")));
        return out;
    }

    private static String unplacedReason(String pool, Map<String, Object> bounds) {
        Map<?, ?> pools = (Map<?, ?>) bounds.get("pools");
        Object info = pools.get(pool);
        if (info instanceof Map<?, ?> m && number(m.get("shortfall")) > 0) {
            return "pool_capacity_shortfall";
        }
        return "conflict_or_fragmentation";
    }

    private static int number(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    public static String percent(int part, int total) {
        return total == 0 ? "100.0%" : String.format("%.1f%%", 100.0 * part / total);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /** 一个组次任务（拆批后的最小排期单位）。 */
    record HeatTask(int uid, int unitIdx, String unitKey, long eventId, String pool, String grade,
                    String eventName, int batch, int nBatches, int duration, long[] athletes,
                    int roundOrder) {
    }

    /** 一个独占的并发位。 */
    record Bin(String key, int day, int window, String pool, int lane, int capacity, int periodIdx) {
    }

    /** 一次分配。 */
    record Assigned(HeatTask task, Bin bin) {
    }

    /** 求解结果。 */
    static final class SolveResult {
        final List<Assigned> assigns = new ArrayList<>();
        List<HeatTask> unplaced = new ArrayList<>();
        int binsTotal;
        int tasksTotal;
        int placedTasks;
        int unplacedAthleteSlots;
        int totalAthleteSlots;
        int conflictMultiplicity;
        int roundsRun;
        int[] cost = {0, 0, 0};
    }

    /** 增量状态：分配 / 负载 / 时段运动员计数 / 偏序索引。 */
    static final class State {
        final List<HeatTask> tasks;
        final Map<String, Bin> binOf = new LinkedHashMap<>();
        final Map<String, List<Bin>> byPool = new LinkedHashMap<>();
        final Map<Integer, String> assign = new HashMap<>();
        final Map<String, Integer> load = new HashMap<>();
        final Map<Integer, Map<Long, Integer>> periodAth = new HashMap<>();
        final Map<Integer, Integer> periodConf = new HashMap<>();
        final Set<String> related = new LinkedHashSet<>();
        final Map<String, TreeMap<Integer, Integer>> prelimAt = new HashMap<>();
        final Map<String, TreeMap<Integer, Integer>> finalAt = new HashMap<>();
        int unplacedTasks;
        int unplacedAthletes;

        State(List<HeatTask> tasks, List<Bin> bins) {
            this.tasks = tasks;
            for (Bin b : bins) {
                binOf.put(b.key(), b);
                byPool.computeIfAbsent(b.pool(), k -> new ArrayList<>()).add(b);
                load.put(b.key(), 0);
            }
            for (HeatTask t : tasks) assign.put(t.uid(), null);
            // 偏序只对「同项目同年级、同时存在预赛与决赛」的单元生效
            Set<String> pre = new LinkedHashSet<>();
            Set<String> fin = new LinkedHashSet<>();
            for (HeatTask t : tasks) {
                String key = relKey(t);
                if (t.roundOrder() == 0) pre.add(key);
                else fin.add(key);
            }
            related.addAll(pre);
            related.retainAll(fin);
            unplacedTasks = tasks.size();
            unplacedAthletes = tasks.stream().mapToInt(t -> t.athletes().length).sum();
        }

        private static String relKey(HeatTask t) {
            return t.eventId() + "|" + t.grade();
        }

        void add(HeatTask t, Bin b) {
            Map<Long, Integer> c = periodAth.computeIfAbsent(b.periodIdx(), k -> new HashMap<>());
            int conf = periodConf.getOrDefault(b.periodIdx(), 0);
            for (long a : t.athletes()) {
                Integer cur = c.get(a);
                if (cur != null && cur >= 1) conf++;
                c.put(a, cur == null ? 1 : cur + 1);
            }
            periodConf.put(b.periodIdx(), conf);
        }

        void rm(HeatTask t, Bin b) {
            Map<Long, Integer> c = periodAth.get(b.periodIdx());
            if (c == null) return;
            int conf = periodConf.getOrDefault(b.periodIdx(), 0);
            for (long a : t.athletes()) {
                Integer cur = c.get(a);
                if (cur == null) continue;
                int next = cur - 1;
                if (next >= 1) conf--;
                if (next <= 0) c.remove(a);
                else c.put(a, next);
            }
            periodConf.put(b.periodIdx(), conf);
        }

        int conflictDelta(HeatTask t, Bin b) {
            Map<Long, Integer> c = periodAth.get(b.periodIdx());
            if (c == null) return 0;
            int d = 0;
            for (long a : t.athletes()) {
                Integer cur = c.get(a);
                if (cur != null && cur >= 1) d++;
            }
            return d;
        }

        List<Bin> candidates(HeatTask t, boolean forMove) {
            List<Bin> out = new ArrayList<>();
            String key = relKey(t);
            boolean rel = related.contains(key);
            String curKey = assign.get(t.uid());
            Bin cur = curKey == null ? null : binOf.get(curKey);
            Integer pmax = null;
            Integer fmin = null;
            if (rel) {
                TreeMap<Integer, Integer> pre = prelimAt.get(key);
                TreeMap<Integer, Integer> fin = finalAt.get(key);
                if (t.roundOrder() == 1 && pre != null && !pre.isEmpty()) pmax = pre.lastKey();
                if (t.roundOrder() == 0 && fin != null && !fin.isEmpty()) fmin = fin.firstKey();
            }
            for (Bin b : byPool.getOrDefault(t.pool(), List.of())) {
                int used = load.getOrDefault(b.key(), 0);
                if (forMove && cur != null && b.key().equals(cur.key())) used -= t.duration();
                if (used + t.duration() > b.capacity()) continue;
                if (pmax != null && b.periodIdx() <= pmax) continue;
                if (fmin != null && b.periodIdx() >= fmin) continue;
                out.add(b);
            }
            return out;
        }

        int[] costAfter(HeatTask t, Bin target) {
            String oldKey = assign.get(t.uid());
            Bin old = oldKey == null ? null : binOf.get(oldKey);
            if (old != null) rm(t, old);
            if (target != null) add(t, target);
            int conf = periodConf.values().stream().mapToInt(Integer::intValue).sum();
            int duT = (old == null ? 1 : 0) - (target == null ? 1 : 0);
            int duA = (old == null ? t.athletes().length : 0) - (target == null ? t.athletes().length : 0);
            int[] cost = {unplacedTasks + duT, unplacedAthletes + duA, conf};
            if (target != null) rm(t, target);
            if (old != null) add(t, old);
            return cost;
        }

        void apply(HeatTask t, Bin target) {
            String oldKey = assign.get(t.uid());
            Bin old = oldKey == null ? null : binOf.get(oldKey);
            String key = relKey(t);
            boolean rel = related.contains(key);
            if (old != null) {
                rm(t, old);
                load.put(old.key(), load.get(old.key()) - t.duration());
            } else {
                unplacedTasks--;
                unplacedAthletes -= t.athletes().length;
            }
            if (target != null) {
                add(t, target);
                load.put(target.key(), load.get(target.key()) + t.duration());
            } else {
                unplacedTasks++;
                unplacedAthletes += t.athletes().length;
            }
            assign.put(t.uid(), target == null ? null : target.key());
            if (rel) {
                Map<String, TreeMap<Integer, Integer>> store = t.roundOrder() == 0 ? prelimAt : finalAt;
                TreeMap<Integer, Integer> map = store.computeIfAbsent(key, k -> new TreeMap<>());
                if (old != null) {
                    map.merge(old.periodIdx(), -1, Integer::sum);
                    if (map.getOrDefault(old.periodIdx(), 0) <= 0) map.remove(old.periodIdx());
                }
                if (target != null) map.merge(target.periodIdx(), 1, Integer::sum);
            }
        }

        int[] currentCost() {
            return new int[]{unplacedTasks, unplacedAthletes,
                    periodConf.values().stream().mapToInt(Integer::intValue).sum()};
        }
    }
}
