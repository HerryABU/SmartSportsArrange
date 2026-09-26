package com.sports.schedule.opt.bandit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UCB1 多臂老虎机——<b>自适应算子选择</b>的通用组件。
 *
 * <h2>为什么需要它</h2>
 * 无论是多邻域模拟退火（MNSA）还是自适应大邻域搜索（ALNS），都面临同一个问题：
 * 搜索过程里有多种「邻域 / 算子」可选，<b>固定轮换或纯随机选择都无法感知「哪种算子在
 * 当前这个实例上更有效」</b>。ITC2021 排名靠前的方案与 ALNS 数学启发式（改进 17 个最佳已知解）
 * 都把这一层交给多臂老虎机：把每个算子当成一台老虎机，「收益」就是它对解质量的贡献，
 * UCB1 公式在「利用已知好算子」与「探索冷门算子」之间做理论保证的平衡：
 * <pre>选择值 = 平均收益 + c × sqrt(ln(总次数) / 该臂次数)</pre>
 *
 * <p>纯函数、无 Spring 依赖、确定性（并列时取编号最小者），因此可脱离搜索循环独立单测。</p>
 */
public final class OperatorBandit {

    private static final double DEFAULT_EXPLORATION = Math.sqrt(2);

    private final List<String> names;
    private final double[] rewardSums;
    private final int[] pulls;
    private final double exploration;
    private int totalPulls;

    public OperatorBandit(List<String> names) {
        this(names, DEFAULT_EXPLORATION);
    }

    public OperatorBandit(List<String> names, double exploration) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("算子池不能为空");
        }
        this.names = List.copyOf(names);
        this.rewardSums = new double[names.size()];
        this.pulls = new int[names.size()];
        this.exploration = exploration <= 0 ? DEFAULT_EXPLORATION : exploration;
    }

    /**
     * 选出下一台老虎机（算子下标）。
     *
     * <p>UCB1 规则：从未试过的臂里按顺序先选（保证每个算子至少被尝试一次，
     * 否则其平均收益无意义）；都试过之后选「利用 + 探索」加权值最大者。</p>
     */
    public int select() {
        for (int i = 0; i < pulls.length; i++) {
            if (pulls[i] == 0) {
                return i;
            }
        }
        int best = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < pulls.length; i++) {
            double mean = rewardSums[i] / pulls[i];
            double explore = exploration * Math.sqrt(Math.log(totalPulls) / pulls[i]);
            double value = mean + explore;
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    /** 记一次该算子的使用收益（任意实数，典型取 0..1） */
    public void reward(int arm, double reward) {
        if (arm < 0 || arm >= pulls.length) {
            throw new IllegalArgumentException("算子下标越界: " + arm);
        }
        rewardSums[arm] += reward;
        pulls[arm]++;
        totalPulls++;
    }

    /** 该算子的平均收益（未试用返回 NaN） */
    public double mean(int arm) {
        return pulls[arm] == 0 ? Double.NaN : rewardSums[arm] / pulls[arm];
    }

    public List<String> names() {
        return names;
    }

    /** 各算子的使用次数与平均收益（供日志与前端「算法解释」面板展示） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("pulls", pulls[i]);
            stat.put("meanReward", pulls[i] == 0 ? null : Math.round(mean(i) * 1000.0) / 1000.0);
            m.put(names.get(i), stat);
        }
        return m;
    }

    /** 每个算子的试用次数快照（测试与断言用） */
    public List<Integer> pullCounts() {
        List<Integer> counts = new ArrayList<>(pulls.length);
        for (int p : pulls) {
            counts.add(p);
        }
        return counts;
    }
}
