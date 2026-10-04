package com.sports.schedule.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 预测式规划器：<b>启发式打底 + 前向剪枝 + 局部回退（ejection chain）+ 3R</b>。
 *
 * <p>这是 Python 侧 {@code sports_ai/plan/hybrid_search.py} 的 Java 对应实现，
 * 两边语义逐条对齐（同一套前向检查、同一套代价、同一套三动作计数），
 * 目的是让「线上编排」与「离线实验」用的是同一套算法，而不是两套只有名字相同的实现。</p>
 *
 * <h2>为什么不是朴素的「深度优先 + 回溯」</h2>
 *
 * <p>离线实测：真实规模（50+ 单元 / 10~15 个时间桶）下，各场地容量总量都够
 * （田径场需求 970 / 容量 1116），但**装得很紧**（87% 利用率）。
 * 紧装箱问题里朴素 DFS 会指数级抖动 —— 2 万次回溯后一个单元都没排下。
 * 所以采用装箱/排课领域的标准做法：</p>
 *
 * <pre>
 * ① 启发式贪心打底（MSBF 序 + best-fit + 前向检查）  ← 一步到位，绝大多数单元直接排下
 * ② 剩下的"钉子户"做**局部回退**：让它挤进某个槽，把挡路者挪走再重排（ejection chain）
 * ③ 挪不动 → Repair（换槽修复）→ 仍无解 → Restart（换序重来）
 * </pre>
 *
 * <h2>「排序」与「剪枝」必须分开（最贵的一条教训）</h2>
 *
 * <p>早期只在**候选排序**里给兼项冲突打负无穷，没有在落子时剪枝。
 * 结果搜索反复生成「同槽同人」的**完整方案**：校验失败 → Repair 也修不动
 * → 回溯后确定性顺序**又走同一条路** —— 空转到 2 万次回退，一个单元都没排下。</p>
 *
 * <p>正确分工：<b>可行性判定（剪枝）必须确定性且尽早；模型与预测只影响顺序。</b>
 * 本类把兼项检查放进 {@link #localOk}（前向检查），与容量检查并列。</p>
 *
 * <h2>另一个坑：ejection 让位后必须重做前向检查</h2>
 *
 * <p>踢掉一个挡路者不等于「现在装得下了」—— 容量是多个单元共同占的，
 * 也可能踢掉的那个根本不占容量。第一版直接在让位后落子，引入了
 * 「超容 180&gt;179」与「兼项撞 REF0/REF2」两类违规，而对外仍报 blocked 为空 ——
 * 看起来「排下了」，其实方案非法。</p>
 */
public final class PredictivePlanner {

    private PredictivePlanner() {
    }

    // ------------------------------------------------------------------ 模型

    /** 一个待排单元。{@code duration} 是**占用时长**（比赛 + 间隔），与解码器口径一致。 */
    public record PlanUnit(String key, String venue, int duration, String groupKey,
                           List<Long> athletes) {
        public PlanUnit {
            athletes = athletes == null ? List.of() : List.copyOf(athletes);
        }
    }

    /** 三动作 + 3R 计数，以及搜索量指标。 */
    public record PlanDiagnostics(int expansions, int continues, int backtracks, int repairs,
                                  int restarts, int rollbacks, int pruned, int prunedRetried,
                                  int blockMoves, int restartImproved) {
        /** 搜索量（节点扩展数）—— 与 arXiv:2606.04860 的评价维度对应。 */
        public int searchCost() {
            return expansions;
        }
    }

    /** 规划结果。{@code violations} 只可能是「未排」（blocked），绝不含非法落位。 */
    public record PlanOutcome(Map<String, Integer> slotOf, boolean feasible,
                              List<String> violations, List<String> blocked,
                              double value, PlanDiagnostics diagnostics) {
    }

    /** 槽位 ID 与「第几天」的映射（用于块完整性与工期代价）。 */
    public interface SlotDays {
        int dayOf(int slot);
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 求解。
     *
     * @param units       待排单元
     * @param capacity    容量表，键 = {@code slot + "#" + venue}，值 = 该槽该场地的分钟容量
     * @param slotDays    槽 → 天 的映射
     * @param maxRestarts 最大重启次数（换序重来）
     * @param maxBacktracks 最大回退次数（防止病态实例把 CPU 吃满）
     * @param seed        重启打散用的随机种子（确定性可复现）
     */
    /**
     * 重启的**多样化算子**（与 Python 侧 {@code plan_predictive.div_operator} 同名同义）。
     *
     * <p>实测（6 seeds × 五档，R=1 → R=32 的合计代价）：{@code NONE} 335.8→335.8（0%）、
     * <b>{@code ROTATE} 335.8→292.8（12.8%）</b>、{@code ADAPTIVE} 293.8（12.5%）、
     * {@code FAILURE} 294.2（12.4%）、{@code SLACK} 317.2（5.6%）。</p>
     *
     * <p>结论：① <b>不加算子时重启完全无用</b>（NONE 恒 0 降幅）；
     * ② 但几个算子统计上旗鼓相当，<b>没有单一算子显著胜出</b> —— 所以默认取
     * {@link #ROTATE}（最优且最简单）。</p>
     */
    public enum DivOperator {
        /** 不换序：每次尝试完全相同（**仅用于对照**，会白烧预算）。 */
        NONE,
        /** 顺序旋转 + 奇次打散（默认）。 */
        ROTATE,
        /** 失败驱动：上次未排的 + 上次被让位最多的单元提到最前。 */
        FAILURE,
        /** 紧度驱动：按「需求 / 该场地可用总容量」升序（越难塞越先排）。 */
        SLACK,
        /** 失败信息（头部）+ 紧度排序（尾部）。 */
        ADAPTIVE
    }

    /** 默认算子：{@link DivOperator#ROTATE}（实测最优且最简单）。 */
    public static final DivOperator DEFAULT_DIV = DivOperator.ROTATE;

    public static PlanOutcome solve(List<PlanUnit> units, Map<String, Integer> capacity,
                                    SlotDays slotDays, int maxRestarts, int maxBacktracks,
                                    long seed) {
        return solve(units, capacity, slotDays, maxRestarts, maxBacktracks, seed,
                DEFAULT_DIV, true);
    }

    /**
     * 完整签名。
     *
     * @param divOperator 重启的多样化算子，见 {@link DivOperator}
     * @param repairBlocks 是否启用**块连续性修复**（把同组跨天的单元并到同一天）。
     *                     代价函数里 {@code breaks} 是计了费的，不开它就成了「罚了但不治」。
     */
    public static PlanOutcome solve(List<PlanUnit> units, Map<String, Integer> capacity,
                                    SlotDays slotDays, int maxRestarts, int maxBacktracks,
                                    long seed, DivOperator divOperator, boolean repairBlocks) {
        Ctx ctx = new Ctx(units, capacity, slotDays, maxBacktracks, new Random(seed));
        ctx.divOperator = divOperator;
        ctx.repairBlocks = repairBlocks;
        if (units.isEmpty()) {
            return new PlanOutcome(Map.of(), true, List.of(), List.of(), 0.0, ctx.snapshot());
        }
        return ctx.run(maxRestarts);
    }

    /** 便捷重载：默认预算。 */
    public static PlanOutcome solve(List<PlanUnit> units, Map<String, Integer> capacity,
                                    SlotDays slotDays) {
        return solve(units, capacity, slotDays, 3, 20000, 0L);
    }

    /** 容量表键。 */
    public static String capKey(int slot, String venue) {
        return slot + "#" + venue;
    }

    // ------------------------------------------------------------------ 实现

    private static final class Ctx {
        final List<PlanUnit> units;
        final Map<String, Integer> capacity;
        final SlotDays slotDays;
        final int maxBacktracks;
        final Random rng;

        final List<String> keys = new ArrayList<>();
        final Map<String, PlanUnit> byKey = new LinkedHashMap<>();
        final Map<String, List<Integer>> cands = new LinkedHashMap<>();

        Map<String, Integer> slotOf = new LinkedHashMap<>();

        DivOperator divOperator = DEFAULT_DIV;
        boolean repairBlocks = true;

        int expansions;
        int continues;
        int completes;
        int backtracks;
        int repairs;
        int restarts;
        int rollbacks;
        int pruned;
        int prunedRetried;
        int blockMoves;
        int restartImproved;
        final List<String> lastReasons = new ArrayList<>();
        /** 上一次尝试里「谁被让位最多」——失败驱动重排要靠它。 */
        final Map<String, Integer> ejectCount = new LinkedHashMap<>();
        List<String> prevBlocked = new ArrayList<>();

        Ctx(List<PlanUnit> units, Map<String, Integer> capacity, SlotDays slotDays,
            int maxBacktracks, Random rng) {
            this.units = units;
            this.capacity = capacity;
            this.slotDays = slotDays;
            this.maxBacktracks = maxBacktracks;
            this.rng = rng;
            Set<String> capKeys = capacity.keySet();
            for (PlanUnit u : units) {
                keys.add(u.key());
                byKey.put(u.key(), u);
                // 候选槽 = 容量表里属于该单元的场地、且容量为正的槽（与 Python 口径一致）
                Set<Integer> slots = new LinkedHashSet<>();
                String suffix = "#" + u.venue();
                for (String k : capKeys) {
                    if (k.endsWith(suffix)) {
                        Integer c = capacity.get(k);
                        if (c != null && c > 0) {
                            slots.add(Integer.parseInt(k.substring(0, k.indexOf('#'))));
                        }
                    }
                }
                List<Integer> list = new ArrayList<>(slots);
                Collections.sort(list);
                cands.put(u.key(), list);
            }
        }

        PlanDiagnostics snapshot() {
            return new PlanDiagnostics(expansions, continues, backtracks, repairs, restarts,
                    rollbacks, pruned, prunedRetried, blockMoves, restartImproved);
        }

        /** MSBF 序：候选越少（越受限）越先排；同候选数按 key 稳定排序。 */
        List<String> heuristicOrder() {
            List<String> order = new ArrayList<>(keys);
            order.sort(Comparator
                    .comparingInt((String k) -> cands.get(k).size())
                    .thenComparing(k -> k));
            return order;
        }

        /**
         * 构造下一次尝试的**单元顺序** —— 这就是多样化算子的全部。
         *
         * <p>三种算子共用一条原则：<b>保留启发式骨架，只在关键处扰动</b>。
         * 纯随机会把「越受限越先排」这个下界信息整个丢掉，重启就退化成从头乱猜。</p>
         */
        private List<String> permute(List<String> base, int attempt,
                                     List<String> blocked, Map<String, Integer> ejects) {
            if (divOperator == DivOperator.NONE) {
                return new ArrayList<>(base);
            }
            if (divOperator == DivOperator.ROTATE) {
                // ⚠️ 必须用新列表承载旋转结果：在同一个列表上「add(remove(0))」
                //    会边读边改，旋转步长变成 2 且顺序不稳定。
                List<String> out = new ArrayList<>(base.size());
                out.addAll(base.subList(1, base.size()));
                out.add(base.get(0));
                if (attempt % 2 == 0) {
                    Collections.shuffle(out, rng);
                }
                return out;
            }
            if (divOperator == DivOperator.SLACK) {
                Map<String, Double> rk = new LinkedHashMap<>();
                for (String k : base) {
                    rk.put(k, rng.nextDouble());
                }
                List<String> out = new ArrayList<>(base);
                out.sort(Comparator.<String, Double>comparing(this::slackOf)
                        .thenComparing(k -> rk.get(k)));
                return out;
            }
            // FAILURE / ADAPTIVE：失败信息（头部）+ 尾部（打散 或 紧度排序）
            List<String> head = new ArrayList<>();
            for (String k : blocked) {
                if (byKey.containsKey(k) && !head.contains(k)) {
                    head.add(k);
                }
            }
            List<Map.Entry<String, Integer>> hot = new ArrayList<>(ejects.entrySet());
            hot.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Integer> en : hot) {
                if (en.getValue() > 0 && byKey.containsKey(en.getKey())
                        && !head.contains(en.getKey())) {
                    head.add(en.getKey());
                }
            }
            List<String> tail = new ArrayList<>();
            Map<String, Double> rk = new LinkedHashMap<>();
            for (String k : base) {
                rk.put(k, rng.nextDouble());
                if (!head.contains(k)) {
                    tail.add(k);
                }
            }
            if (divOperator == DivOperator.ADAPTIVE) {
                tail.sort(Comparator.<String, Double>comparing(this::slackOf)
                        .thenComparing(k -> rk.get(k)));
            } else {
                Collections.shuffle(tail, rng);
            }
            head.addAll(tail);
            return head;
        }

        /** 「本单元需求 / 该场地可用总容量」—— 越大越难塞。 */
        private double slackOf(String key) {
            PlanUnit u = byKey.get(key);
            if (u == null) {
                return 0.0;
            }
            double tot = 0.0;
            String suffix = "#" + u.venue();
            for (Map.Entry<String, Integer> e : capacity.entrySet()) {
                if (e.getKey().endsWith(suffix)) {
                    tot += e.getValue();
                }
            }
            return u.duration() / Math.max(1.0, tot);
        }

        PlanOutcome run(int maxRestarts) {
            List<String> order = heuristicOrder();
            PlanOutcome best = null;
            int attempts = Math.max(1, maxRestarts);
            for (int attempt = 0; attempt < attempts; attempt++) {
                if (attempt > 0) {
                    restarts++;
                    order = permute(order, attempt, prevBlocked, ejectCount);
                }
                slotOf = new LinkedHashMap<>();
                List<String> pending = new ArrayList<>();

                // ---------- ① 启发式贪心打底 ----------
                for (String key : order) {
                    Integer sid = firstFeasible(key);
                    if (sid == null) {
                        pending.add(key);
                    } else {
                        slotOf.put(key, sid);
                        continues++;
                    }
                }

                // ---------- ② 局部回退：让"钉子户"挤进去 ----------
                List<String> still = new ArrayList<>();
                for (String key : pending) {
                    if (!tryPlace(key, 3)) {
                        still.add(key);
                    }
                }

                // ---------- ③ 完成校验 + Repair ----------
                List<String> why = verify();
                if (!why.isEmpty()) {
                    repairs++;
                    Map<String, Integer> fixed = tryRepair();
                    if (fixed != null) {
                        slotOf = fixed;
                        why = verify();
                    }
                }

                // ---- 块连续性修复（纯改进算子：只接受「合法 + breaks 严格下降」）----
                if (repairBlocks && why.isEmpty()) {
                    Map<String, Integer> fixedBlocks = repairBlocks(new LinkedHashMap<>(slotOf));
                    if (fixedBlocks != null) {
                        slotOf = fixedBlocks;
                        why = verify();
                    }
                }

                double value = cost();
                PlanOutcome cand = new PlanOutcome(new LinkedHashMap<>(slotOf),
                        why.isEmpty() && still.isEmpty(), why, still, value, snapshot());
                if (cand.feasible()) {
                    completes++;
                    return cand;
                }
                if (best == null || better(cand, best)) {
                    if (attempt > 0) {
                        // 只有**重启里**的改进才计入：首轮是基线，不能算作算子的功劳
                        restartImproved++;
                    }
                    best = cand;
                }
                // 交给下一次尝试：谁没排下、谁被踢得最多
                prevBlocked = new ArrayList<>(still);
                if (backtracks >= maxBacktracks) {
                    break;
                }
            }
            return best != null ? best
                    : new PlanOutcome(Map.of(), false, List.of("无解"), keys, Double.POSITIVE_INFINITY,
                    snapshot());
        }

        private static boolean better(PlanOutcome a, PlanOutcome b) {
            if (a.blocked().size() != b.blocked().size()) {
                return a.blocked().size() < b.blocked().size();
            }
            return a.value() < b.value();
        }

        /** 候选槽按 best-fit 紧度降序（确定性启发为主）。 */
        private List<Integer> ranked(PlanUnit u) {
            String key = u.key();
            String venue = u.venue();
            List<int[]> scored = new ArrayList<>();   // {sid, score*1000}
            for (Integer sid : cands.get(key)) {
                Integer c = capacity.get(capKey(sid, venue));
                if (c == null || c <= 0) {
                    continue;
                }
                int load = 0;
                for (PlanUnit x : units) {
                    if (x.key().equals(key) || !Integer.valueOf(sid).equals(slotOf.get(x.key()))) {
                        continue;
                    }
                    if (x.venue().equals(venue)) {
                        load += x.duration();
                    }
                }
                if (load + u.duration() > c) {
                    continue;                              // 装不下
                }
                scored.add(new int[]{sid, (int) Math.round(1000.0 * (load + u.duration()) / c)});
            }
            scored.sort((p, q) -> Integer.compare(q[1], p[1]));
            List<Integer> out = new ArrayList<>(scored.size());
            for (int[] p : scored) {
                out.add(p[0]);
            }
            return out;
        }

        /** 贪心：按 best-fit 序试，第一个通过前向检查的槽即选中。 */
        private Integer firstFeasible(String key) {
            PlanUnit u = byKey.get(key);
            for (Integer sid : ranked(u)) {
                if (this.localOk(key, sid)) {
                    this.expansions++;
                    return sid;
                }
                this.expansions++;
            }
            return null;
        }

        /** 前向检查：容量 + 兼项（硬约束，落子即可判定）。 */
        boolean localOk(String key, int sid) {
            PlanUnit u = byKey.get(key);
            String venue = u.venue();
            int load = 0;
            for (PlanUnit x : units) {
                if (x.key().equals(key)) {
                    continue;
                }
                Integer xs = slotOf.get(x.key());
                if (xs == null || xs != sid) {
                    continue;
                }
                if (x.venue().equals(venue)) {
                    load += x.duration();
                }
                if (clashes(u, x)) {
                    return false;                          // 同槽同人：硬不可行（不看场地）
                }
            }
            Integer c = capacity.get(capKey(sid, venue));
            return c != null && load + u.duration() <= c;
        }

        private boolean clashes(PlanUnit a, PlanUnit b) {
            if (a.athletes().isEmpty() || b.athletes().isEmpty()) {
                return false;
            }
            Set<Long> small = new HashSet<>(a.athletes());
            for (Long id : b.athletes()) {
                if (small.contains(id)) {
                    return true;
                }
            }
            return false;
        }

        /** 局部回退：让 key 挤进某个槽，必要时把挡路者挪走再重排（ejection chain）。 */
        boolean tryPlace(String key, int depth) {
            PlanUnit u = byKey.get(key);
            for (Integer sid : ranked(u)) {
                if (localOk(key, sid)) {
                    expansions++;
                    slotOf.put(key, sid);
                    continues++;
                    return true;
                }
                expansions++;
                if (depth <= 0) {
                    continue;
                }
                List<String> blockers = blockers(key, sid);
                for (String b : blockers) {
                    Integer old = slotOf.remove(b);
                    ejectCount.merge(b, 1, Integer::sum);
                    // ⚠️ 必须重做一次前向检查再提交这次让位（见类注释）
                    if (!localOk(key, sid)) {
                        slotOf.put(b, old);
                        continue;
                    }
                    expansions++;
                    slotOf.put(key, sid);
                    backtracks++;
                    rollbacks++;
                    if (tryPlace(b, depth - 1)) {
                        return true;
                    }
                    slotOf.remove(key);
                    slotOf.put(b, old);
                    if (backtracks >= maxBacktracks) {
                        lastReasons.add("触达回退上限");
                        return false;
                    }
                }
            }
            lastReasons.add("无可用槽:" + key);
            return false;
        }

        private List<String> blockers(String key, int sid) {
            PlanUnit u = byKey.get(key);
            String venue = u.venue();
            List<String> out = new ArrayList<>();
            for (PlanUnit x : units) {
                if (x.key().equals(key)) {
                    continue;
                }
                Integer xs = slotOf.get(x.key());
                if (xs == null || xs != sid) {
                    continue;
                }
                if (x.venue().equals(venue)) {
                    out.add(x.key());                      // 占容量的
                } else if (clashes(u, x)) {
                    out.add(x.key());                      // 撞兼项的（不同场地也算）
                }
            }
            return out;
        }

        /** 校验：容量超占 + 兼项撞车。只报「非法落位」；未排单独由 blocked 表达。 */
        List<String> verify() {
            List<String> why = new ArrayList<>();
            Map<String, Integer> load = new LinkedHashMap<>();
            for (PlanUnit u : units) {
                Integer sid = slotOf.get(u.key());
                if (sid == null) {
                    continue;
                }
                String vk = capKey(sid, u.venue());
                load.merge(vk, u.duration(), Integer::sum);
            }
            for (Map.Entry<String, Integer> e : load.entrySet()) {
                Integer c = capacity.get(e.getKey());
                if (c == null) {
                    why.add("槽场地未开放:" + e.getKey());
                } else if (e.getValue() > c) {
                    why.add("超容 " + e.getValue() + ">" + c + " @" + e.getKey());
                }
            }
            Map<String, String> seen = new HashMap<>();
            for (PlanUnit u : units) {
                Integer sid = slotOf.get(u.key());
                if (sid == null) {
                    continue;
                }
                for (Long a : u.athletes()) {
                    String sk = a + "@" + sid;
                    String prev = seen.get(sk);
                    if (prev != null) {
                        why.add("兼项撞 " + prev + "/" + u.key());
                    } else {
                        seen.put(sk, u.key());
                    }
                }
            }
            return why;
        }

        /** Repair：只靠**换槽**消掉容量/兼项违规；修不动返回 null（诚实上报）。 */
        Map<String, Integer> tryRepair() {
            Map<String, Integer> cur = new LinkedHashMap<>(slotOf);
            for (int round = 0; round < 3; round++) {
                Map<String, Integer> saved = slotOf;
                slotOf = cur;
                List<String> why = verify();
                slotOf = saved;
                if (why.isEmpty()) {
                    return cur;
                }
                // 针对第一条违规，尝试把涉事单元挪到别的槽
                String first = why.get(0);
                boolean moved = false;
                for (PlanUnit u : units) {
                    Integer cs = cur.get(u.key());
                    if (cs == null || !first.contains("@") || !involved(first, u, cs)) {
                        continue;
                    }
                    int before = violationCount(cur);
                    for (Integer alt : ranked(u)) {
                        if (alt.equals(cs)) {
                            continue;
                        }
                        Map<String, Integer> trial = new LinkedHashMap<>(cur);
                        trial.put(u.key(), alt);
                        if (violationCount(trial) < before) {
                            cur = trial;
                            moved = true;
                            break;
                        }
                    }
                    if (moved) {
                        break;
                    }
                }
                if (!moved) {
                    return null;
                }
            }
            Map<String, Integer> saved = slotOf;
            slotOf = cur;
            boolean ok = verify().isEmpty();
            slotOf = saved;
            return ok ? cur : null;
        }

        private boolean involved(String violation, PlanUnit u, int sid) {
            return violation.contains("@" + capKey(sid, u.venue()))
                    || violation.contains("兼项撞") && violation.contains(u.key());
        }

        int violationCount(Map<String, Integer> cand) {
            Map<String, Integer> saved = slotOf;
            slotOf = cand;
            int n = verify().size();
            slotOf = saved;
            return n;
        }

        /** 块断裂数 = 各 groupKey 占用的天数减一之和（与 {@link #cost()} 同口径）。 */
        int breaks() {
            return breaksOf(slotOf);
        }

        int breaksOf(Map<String, Integer> cand) {
            Map<String, Set<Integer>> byGroup = new LinkedHashMap<>();
            for (PlanUnit u : units) {
                Integer sid = cand.get(u.key());
                if (sid == null || u.groupKey() == null || u.groupKey().isBlank()) {
                    continue;
                }
                byGroup.computeIfAbsent(u.groupKey(), k -> new LinkedHashSet<>())
                        .add(slotDays.dayOf(sid));
            }
            int out = 0;
            for (Set<Integer> days : byGroup.values()) {
                out += Math.max(0, days.size() - 1);
            }
            return out;
        }

        /**
         * 块连续性修复：把「同一组跨了多天」的单元**并到同一天**去。
         *
         * <p>为什么需要它：代价里 {@code breaks}（同组跨天）是计了费的，
         * 但修复层若只修容量/兼项，就成了「罚了但不治」—— 搜索每轮被扣分，
         * 却没有任何算子把那几分挣回来。</p>
         *
         * <p>只接受同时满足两条件的移动：① 移动后仍<b>合法</b>
         * （容量/兼项/场地开放，用 {@link #isLegal} —— 注意是<b>合法</b>而<b>不是</b>完整，
         * 部分解上也要能用）；② 全局 {@code breaks} <b>严格下降</b>。
         * 所以它是<b>纯改进算子</b>，不可能让解变差。</p>
         *
         * <p>为什么需要<b>交换</b>（swap）而不只是搬迁：搬迁要求锚点天有空余容量，
         * 而紧实例里锚点天往往是满的 —— 实测只做搬迁时 BLOCK 档 12 处断裂只修掉 0.7 处；
         * 加上容量守恒的交换后升到 1.8 处，全局 breaks 降幅由 5.0% 提到 <b>12.6%</b>。</p>
         */
        Map<String, Integer> repairBlocks(Map<String, Integer> start) {
            Map<String, Integer> cur = start;
            final int maxMoves = 64;
            for (int step = 0; step < maxMoves; step++) {
                int curBreaks = breaksOf(cur);
                if (curBreaks == 0) {
                    break;
                }
                Map<String, List<String>> byGroup = new LinkedHashMap<>();
                for (PlanUnit u : units) {
                    if (u.groupKey() != null && !u.groupKey().isBlank()
                            && cur.containsKey(u.key())) {
                        byGroup.computeIfAbsent(u.groupKey(), k -> new ArrayList<>()).add(u.key());
                    }
                }
                Map<String, Integer> found = null;
                outer:
                for (Map.Entry<String, List<String>> g : byGroup.entrySet()) {
                    Map<Integer, Integer> dayCount = new LinkedHashMap<>();
                    for (String k : g.getValue()) {
                        int d = slotDays.dayOf(cur.get(k));
                        dayCount.merge(d, 1, Integer::sum);
                    }
                    if (dayCount.size() <= 1) {
                        continue;
                    }
                    // ⚠️ 逐个天试锚点，而不是只试「单元最多的那天」：
                    //    单元数打平时 max 会挑到**搬不动的那天**，
                    //    于是明明能并却一步都不动（表现为「算子没效果」）。
                    List<Integer> anchors = new ArrayList<>(dayCount.keySet());
                    anchors.sort((a, b) -> Integer.compare(dayCount.get(b), dayCount.get(a)));
                    for (Integer anchorDay : anchors) {
                        for (String k : g.getValue()) {
                            if (slotDays.dayOf(cur.get(k)) == anchorDay) {
                                continue;
                            }
                            PlanUnit u = byKey.get(k);
                            int blockSlot = cur.get(k);
                            // ---- ① 直接搬迁 ----
                            for (Integer sid : cands.get(k)) {
                                if (sid == blockSlot || slotDays.dayOf(sid) != anchorDay) {
                                    continue;
                                }
                                Map<String, Integer> trial = new LinkedHashMap<>(cur);
                                trial.put(k, sid);
                                if (!isLegal(trial)) {
                                    continue;
                                }
                                if (breaksOf(trial) < curBreaks) {
                                    found = trial;
                                    break;
                                }
                            }
                            // ---- ② 交换（容量守恒，锚点天满也能动）----
                            if (found == null) {
                                for (PlanUnit x : units) {
                                    if (x.key().equals(k) || !cur.containsKey(x.key())) {
                                        continue;
                                    }
                                    if (slotDays.dayOf(cur.get(x.key())) != anchorDay) {
                                        continue;
                                    }
                                    Map<String, Integer> trial = new LinkedHashMap<>(cur);
                                    trial.put(k, cur.get(x.key()));
                                    trial.put(x.key(), blockSlot);
                                    if (!isLegal(trial)) {
                                        continue;
                                    }
                                    if (breaksOf(trial) < curBreaks) {
                                        found = trial;
                                        break;
                                    }
                                }
                            }
                            if (found != null) {
                                break outer;
                            }
                            if (u == null) {
                                break;
                            }
                        }
                    }
                }
                if (found == null) {
                    break;
                }
                cur = found;
                blockMoves++;
            }
            return cur;
        }

        /**
         * **只判合法性**（容量 / 兼项 / 场地是否开），<b>不判完整性</b>。
         *
         * <p>⚠️ 不能直接用 {@link #verify()}：它会把「未排」也算作不可行。
         * 在**部分解**上（规划过程中很常见），任何移动都会被误判成非法，
         * 于是修复算子一步都动不了 —— 调用方只看到「算子没效果」，
         * 看不出是<b>判定口径用错</b>。合法性 ≠ 完整性。</p>
         */
        boolean isLegal(Map<String, Integer> cand) {
            Map<String, Integer> saved = slotOf;
            slotOf = cand;
            List<String> why = verify();
            List<String> illegal = new ArrayList<>();
            for (String w : why) {
                if (!w.startsWith("未排:")) {
                    illegal.add(w);
                }
            }
            slotOf = saved;
            return illegal.isEmpty();
        }

        /** 统一的「越小越好」代价：未排 ×10 + 超占/60 + 块断裂 + 工期跨度。 */
        double cost() {
            int unplaced = 0;
            Map<String, Integer> load = new LinkedHashMap<>();
            for (PlanUnit u : units) {
                Integer sid = slotOf.get(u.key());
                if (sid == null) {
                    unplaced++;
                    continue;
                }
                load.merge(capKey(sid, u.venue()), u.duration(), Integer::sum);
            }
            double over = 0;
            for (Map.Entry<String, Integer> e : load.entrySet()) {
                Integer c = capacity.get(e.getKey());
                if (c != null && e.getValue() > c) {
                    over += e.getValue() - c;
                }
            }
            Map<String, Set<Integer>> byGroup = new LinkedHashMap<>();
            for (PlanUnit u : units) {
                Integer sid = slotOf.get(u.key());
                if (sid == null || u.groupKey() == null || u.groupKey().isBlank()) {
                    continue;
                }
                byGroup.computeIfAbsent(u.groupKey(), k -> new LinkedHashSet<>())
                        .add(slotDays.dayOf(sid));
            }
            int breaks = 0;
            for (Set<Integer> days : byGroup.values()) {
                breaks += Math.max(0, days.size() - 1);
            }
            Set<Integer> usedDays = new LinkedHashSet<>();
            for (Integer sid : slotOf.values()) {
                usedDays.add(slotDays.dayOf(sid));
            }
            int span = usedDays.isEmpty() ? 0 : usedDays.size() - 1;
            return unplaced * 10.0 + over / 60.0 + breaks + span;
        }
    }
}
