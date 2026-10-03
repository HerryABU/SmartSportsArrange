package com.sports.schedule.ai;

import com.sports.schedule.opt.solver.Placement;
import com.sports.schedule.opt.solver.ScheduleUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * <b>自回归场景规划器</b>——从「一次性给每个单元打分」升级为「一步步往后预测，并给出多条未来走向」。
 *
 * <h3>为什么必须换范式</h3>
 * <p>旧链路（{@code ConflictGraphEncoder} → GNN → {@code reorderByPriority}）是
 * <b>静态一次性</b>的：算一张图、给每个单元一个固定优先级、整体排序。对「从头到脚的全编排」
 * 有两个根本缺陷：</p>
 * <ol>
 *   <li><b>不看前几步的决策</b>：第 1 个单元放哪会改变第 2 个单元的处境
 *       （占了田径场 → 后面径赛只能换时段）。静态打分对所有单元用<b>同一张图</b>，
 *       无法表达「这一步的选择改变了那一步的处境」；</li>
 *   <li><b>只给一个答案</b>：真实编排有多条互有优劣的走法（激进紧凑 vs 保守均衡），
 *       单点排序把「未来走向」压成一条，用户看不到备选也无从表达偏好。</li>
 * </ol>
 *
 * <h3>本类怎么做</h3>
 * <p>把编排当成<b>逐格填装</b>：每一步都在<b>动态更新后的状态</b>上决策，条件包含
 * <b>已经放好的前 k 步</b>。{@link DynaState} 维护三件事：</p>
 * <ul>
 *   <li><b>已放集合</b>——决定哪些运动员的时间被占用，兼项冲突随之动态产生/消失；</li>
 *   <li><b>各时段箱已消耗容量</b>——决定装箱可行性；</li>
 *   <li><b>累计冲突数与累计时长</b>——供模型判断「还剩多少空间」。</li>
 * </ul>
 *
 * <h3>多条未来走向 + 概率（TimePrism 思路）</h3>
 * <p>参考 <b>TimePrism</b>（arXiv:2509.19975, ICLR 2026）「From Samples to Scenarios」：
 * 决策系统需要的不是一次采样，而是<b>一组可能的结果 + 各自的概率</b>。本类产出
 * {@code numScenarios} 条完整编排，概率按 {@code softmax(-cost/T)} 归一化——
 * <b>代价越低概率越高</b>，调用方可按偏好取用。</p>
 *
 * <h3>回溯（LMask 思路）</h3>
 * <p>参考 <b>LMask</b>（2026-03）关于「回溯相比单纯前瞻，以更低成本换更高可行率」的结论：
 * 贪心逐步放置可能走进死路。本类在每一步保留候选快照，走投无路时回退到最近的
 * 仍有多个候选的分叉点改选另一个，上限 {@code maxBacktracks} 步防止无界回溯。</p>
 */
public final class AutoregressiveScenarioPlanner {

    /** 一步决策的输入特征维度（固定，双端契约）。 */
    public static final int STEP_FEAT_DIM = 16;

    /** 每步参与打分的候选上限——超大实例只看「最紧的若干个」，避免 O(n²)。 */
    private static final int TOP_CANDIDATES = 64;

    private final int numScenarios;
    private final int maxBacktracks;
    private final int seed;
    private int backtracksUsed;

    public AutoregressiveScenarioPlanner(int numScenarios, int maxBacktracks, int seed) {
        this.numScenarios = Math.max(1, numScenarios);
        this.maxBacktracks = Math.max(0, maxBacktracks);
        this.seed = seed;
    }

    /** 一条完整走向。 */
    public record Scenario(
            List<Integer> order,         // 单元下标序列 = 从头到脚的编排顺序
            List<float[]> stepFeatures,  // 每步的条件特征（16 维，可直接喂模型）
            double cost,                 // 总代价：未排单元重罚 + 兼项冲突
            double probability) {        // 归一化概率，与代价单调
    }

    /** 规划结果。 */
    public record Plan(List<Scenario> scenarios, int steps, int backtracks, boolean complete) {
        public Scenario best() {
            return scenarios == null || scenarios.isEmpty() ? null : scenarios.get(0);
        }
    }

    /**
     * 动态状态——「前面几步」的记忆载体。每放一个单元就更新一次。
     */
    public static final class DynaState {
        private final List<ScheduleUnit> units;
        private final boolean[] placed;
        /** 运动员 → 其被占用的步序号（存在即表示该运动员此刻不可用）。 */
        private final Map<Long, Integer> athleteBusyStep = new HashMap<>();
        /** 时段箱 key → 已消耗容量。 */
        private final Map<String, Double> binUsed = new LinkedHashMap<>();
        /** 每个单元上一次落在哪个箱（回滚时用）。 */
        private final Map<Integer, String> placedBin = new HashMap<>();
        private int step;
        private double cumDuration;
        private int cumConflicts;

        DynaState(List<ScheduleUnit> units) {
            this.units = units;
            this.placed = new boolean[units.size()];
        }

        public boolean isPlaced(int i) {
            return i >= 0 && i < placed.length && placed[i];
        }

        public int remaining() {
            int n = 0;
            for (boolean p : placed) {
                if (!p) {
                    n++;
                }
            }
            return n;
        }

        public int placedCount() {
            return step;
        }

        public int conflicts() {
            return cumConflicts;
        }

        public double cumDuration() {
            return cumDuration;
        }

        public int step() {
            return step;
        }

        public double binUsed(String key) {
            return binUsed.getOrDefault(key, 0.0);
        }

        public boolean isAthleteBusy(long a) {
            return athleteBusyStep.containsKey(a);
        }

        /** 该单元此刻的兼项冲突面（有多少运动员已被占用）。 */
        public int conflictOf(int i) {
            long[] ath = units.get(i).getAthletes();
            if (ath == null) {
                return 0;
            }
            int c = 0;
            for (long a : ath) {
                if (athleteBusyStep.containsKey(a)) {
                    c++;
                }
            }
            return c;
        }

        /** 找第一个装得下的候选时段；null 表示装箱不可行。 */
        public Placement firstFitting(int i) {
            List<Placement> cands = units.get(i).getCandidatePlacements();
            if (cands == null) {
                return null;
            }
            int need = units.get(i).getRawDuration();
            for (Placement p : cands) {
                if (binUsed.getOrDefault(p.getBinKey(), 0.0) + need <= p.getWindowCapacity()) {
                    return p;
                }
            }
            return null;
        }

        /** 提交一次放置。调用前须已用 {@link #firstFitting} 确认可行。 */
        boolean place(int i, Placement p) {
            ScheduleUnit u = units.get(i);
            cumConflicts += conflictOf(i);
            placed[i] = true;
            placedBin.put(i, p.getBinKey());
            binUsed.merge(p.getBinKey(), (double) u.getRawDuration(), Double::sum);
            long[] ath = u.getAthletes();
            if (ath != null) {
                for (long a : ath) {
                    athleteBusyStep.put(a, step);
                }
            }
            cumDuration += u.getRawDuration();
            step++;
            return true;
        }

        /** 撤销一次放置（回溯用）。 */
        void unplace(int i) {
            if (!isPlaced(i)) {
                return;
            }
            ScheduleUnit u = units.get(i);
            placed[i] = false;
            String key = placedBin.remove(i);
            if (key != null) {
                binUsed.merge(key, -(double) u.getRawDuration(), Double::sum);
            }
            long[] ath = u.getAthletes();
            if (ath != null) {
                for (long a : ath) {
                    athleteBusyStep.remove(a);
                }
            }
            cumDuration -= u.getRawDuration();
            step--;
            // 冲突数在 place 时累加，这里按当时的冲突面重算更稳（避免多次回滚累积误差）
            cumConflicts -= conflictOf(i);
        }
    }

    /**
     * 主入口：产出多条编排走向。
     *
     * @param units   全部单元
     * @param explore 探索强度 0=纯贪心；越大场景越多样
     */
    public Plan plan(List<ScheduleUnit> units, double explore) {
        if (units == null || units.isEmpty()) {
            return new Plan(List.of(), 0, 0, true);
        }
        Random rnd = new Random(seed);
        List<Scenario> raw = new ArrayList<>(numScenarios);
        int steps = 0;
        backtracksUsed = 0;
        boolean allComplete = true;

        for (int s = 0; s < numScenarios; s++) {
            // 场景 0 纯贪心保底；其余逐步加大探索度制造多样性
            double e = s == 0 ? 0.0 : Math.max(explore, 0.2 * s);
            Scenario sc = runOne(units, e, rnd);
            if (sc.order().size() < units.size()) {
                allComplete = false;
            }
            steps = Math.max(steps, sc.order().size());
            raw.add(sc);
        }

        // 概率：softmax(-cost / T)，T 以「总需求/总容量」为量纲——越紧张越尖锐
        double t = temperatureOf(units);
        double minCost = raw.stream().mapToDouble(Scenario::cost).min().orElse(0.0);
        double[] w = new double[raw.size()];
        double sum = 0;
        for (int i = 0; i < raw.size(); i++) {
            w[i] = Math.exp(-(raw.get(i).cost() - minCost) / t);
            sum += w[i];
        }
        List<Scenario> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Scenario sc = raw.get(i);
            out.add(new Scenario(sc.order(), sc.stepFeatures(), sc.cost(), w[i] / sum));
        }
        out.sort(Comparator.comparingDouble(Scenario::probability).reversed());
        return new Plan(out, steps, backtracksUsed, allComplete);
    }

    private double temperatureOf(List<ScheduleUnit> units) {
        double demand = 0;
        int cap = 0;
        Set<String> seen = new HashSet<>();
        for (ScheduleUnit u : units) {
            demand += u.getRawDuration();
            if (u.getCandidatePlacements() != null) {
                for (Placement p : u.getCandidatePlacements()) {
                    if (seen.add(p.getBinKey())) {
                        cap += p.getWindowCapacity();
                    }
                }
            }
        }
        return Math.max(1.0, demand / Math.max(1, cap));
    }

    /** 跑一条完整走向：自回归逐步放置 + 死路时回溯。 */
    private Scenario runOne(List<ScheduleUnit> units, double explore, Random rnd) {
        int n = units.size();
        DynaState st = new DynaState(units);
        List<Integer> order = new ArrayList<>(n);
        List<float[]> feats = new ArrayList<>(n);
        // 分叉点：每步若候选 >1 就记下**整份候选列表**（回溯时要改选另一个）
        List<List<int[]>> forks = new ArrayList<>();
        List<Integer> forkAt = new ArrayList<>();

        while (st.remaining() > 0) {
            List<int[]> cands = rankCandidates(st, units, explore, rnd);
            boolean placedOne = false;
            int placedIdx = -1;
            Placement placedAt = null;
            for (int[] c : cands) {
                int i = c[0];
                Placement p = st.firstFitting(i);
                if (p == null) {
                    continue;
                }
                feats.add(stepFeature(st, units, i, p));
                st.place(i, p);
                order.add(i);
                placedIdx = i;
                placedAt = p;
                if (cands.size() > 1) {
                    forks.add(new ArrayList<>(cands));
                    forkAt.add(order.size() - 1);
                }
                placedOne = true;
                break;
            }
            if (placedOne) {
                continue;
            }
            // ---- 死路：回溯到最近分叉点，改选当时未选的那个候选 ----
            if (backtracksUsed < maxBacktracks && !forks.isEmpty()) {
                List<int[]> candsAtFork = forks.remove(forks.size() - 1);
                int at = forkAt.remove(forkAt.size() - 1);
                backtracksUsed++;
                // 撤销 at 之后（含 at）的所有放置，回到分叉点
                while (order.size() > at) {
                    int last = order.remove(order.size() - 1);
                    if (!feats.isEmpty()) {
                        feats.remove(feats.size() - 1);
                    }
                    st.unplace(last);
                }
                // 改选：遍历当时的整份候选，跳过已被撤销的那个（cands[0]）
                int rejected = at < order.size() ? order.get(at) : -1;
                for (int[] cand : candsAtFork) {
                    int i = cand[0];
                    if (i == rejected || st.isPlaced(i)) {
                        continue;
                    }
                    Placement p = st.firstFitting(i);
                    if (p == null) {
                        continue;
                    }
                    feats.add(stepFeature(st, units, i, p));
                    st.place(i, p);
                    order.add(i);
                    placedOne = true;
                    break;
                }
            }
            if (!placedOne) {
                break;      // 无路可走：由上层做部分编排兜底
            }
        }
        return new Scenario(order, feats, costOf(st, units), 0.0);
    }

    /**
     * 给未放单元打分。<b>分数依赖当前 {@link DynaState}</b>——
     * 这正是「后面几步依据前面几步判断」的打分函数：
     * 同一单元在不同历史下会得到不同分数。
     */
    private List<int[]> rankCandidates(DynaState st, List<ScheduleUnit> units,
                                       double explore, Random rnd) {
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            if (st.isPlaced(i)) {
                continue;
            }
            ScheduleUnit u = units.get(i);
            long[] ath = u.getAthletes();
            int people = ath == null ? 0 : ath.length;
            int conflict = st.conflictOf(i);
            boolean fits = st.firstFitting(i) != null;
            // 瓶颈前置：优先放「冲突面小 + 体积大 + 装得下」的单元
            double score = (double) people * 3.0 - conflict * 2.0 + u.getRawDuration() * 0.01
                    + (fits ? 1000.0 : -1000.0);
            if (explore > 0) {
                score += rnd.nextGaussian() * explore * 500.0;
            }
            scored.add(new int[]{i, (int) Math.round(score)});
        }
        scored.sort((a, b) -> Integer.compare(b[1], a[1]));
        return scored.size() > TOP_CANDIDATES
                ? new ArrayList<>(scored.subList(0, TOP_CANDIDATES))
                : scored;
    }

    /** 一步决策的 16 维条件特征（可直接作为模型的输入）。 */
    private float[] stepFeature(DynaState st, List<ScheduleUnit> units, int i, Placement p) {
        ScheduleUnit u = units.get(i);
        long[] ath = u.getAthletes();
        int people = ath == null ? 0 : ath.length;
        int conflict = st.conflictOf(i);
        float[] f = new float[STEP_FEAT_DIM];
        f[0] = people / 100f;
        f[1] = Math.min(1f, u.getRawDuration() / 600f);
        f[2] = people == 0 ? 0f : conflict / (float) people;
        f[3] = st.remaining() / (float) Math.max(1, units.size());
        f[4] = st.step() == 0 ? 0f : st.conflicts() / (float) st.step();
        f[5] = p == null ? 0f : (float) (st.binUsed(p.getBinKey()) / Math.max(1, p.getWindowCapacity()));
        f[6] = u.isTrack() ? 1f : 0f;
        f[7] = p == null ? 0f : Math.min(1f, (p.getDay() - 1) / 10f);
        f[8] = p == null ? 0f : Math.min(1f, (p.getStartMinute() - 480) / 600f);
        f[9] = u.getInterval() / 60f;
        f[10] = st.step() / (float) Math.max(1, units.size());
        f[11] = p != null && p.getSlotName() != null ? 1f : 0f;
        f[12] = u.getGroupKey() == null ? 0f : 1f;
        f[13] = u.getPoolLabel() == null ? 0f : 1f;
        f[14] = (float) Math.min(1.0, st.cumDuration() / 10000.0);
        f[15] = p == null ? 0f : 1f;
        return f;
    }

    /** 总代价：未排单元重罚（1000/个）+ 兼项冲突（10/次）。 */
    private double costOf(DynaState st, List<ScheduleUnit> units) {
        return st.remaining() * 1000.0 + st.conflicts() * 10.0;
    }
}
