package com.sports.schedule.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 分层调度门面：**第一次编排走主 MoE，各微调环节走各自的专项 MoE**。
 *
 * <h2>为什么要分层，而不是一个模型打通</h2>
 * 三类决策的输入结构、时效要求、失败代价完全不同：
 * <ul>
 *   <li><b>第一次编排（主 MoE）</b>：输入是整场赛会的约束图（19 类任务 / 10 类边），
 *       一次跑完整场，输出优先级 + 时间槽 + 工期 + 后续步骤。
 *       失败代价高但可容忍 —— 后面还有 GA/LNS/MNSA/ALNS 一整条精修链兜底。</li>
 *   <li><b>组次错开（专项 MoE）</b>：输入只有「一个运动员的候选组次」，
 *       在毫秒级反复调用；要求单次推理极快，且<b>红线绝不能越</b>。</li>
 *   <li><b>跨时段拆分（专项 MoE）</b>：输入是「一批放不下的项目」，
 *       决定先拆谁；每趟编排只跑几次。</li>
 * </ul>
 * 把三者塞进一个模型，会出现「为了迁就最小时间窗，把最慢的那条路径塞进热路径」的反模式。
 *
 * <h2>模型不可用时的行为</h2>
 * 每个环节<b>独立降级</b>：主 MoE 缺失只影响第一次编排（退回贪心 + 规则编排），
 * 专项模型缺失只影响该微调环节（退回规则择优）。任何一环不可用都不会让功能不可用 ——
 * 这是本项目一贯的「AI 是锦上添花而非交付前提」。
 *
 * <h2>可观测</h2>
 * {@link #status()} 汇总各模型可用性与调用次数，直接供 {@code /api/ai/status}。
 * 「模型建了但从不调用」与「模型缺失」同样重要：前者是接线断了。
 */
@Slf4j
@Component
public class AiTiers {

    private final SuperMoeService superMoe;
    private final HeatStaggerAdvisorService heatStagger;
    private final SlotSplitAdvisorService slotSplit;

    /** 各环节的调用次数（进程内累计）。用于识别「接了但从没被调用」。 */
    private final Map<String, long[]> calls = new LinkedHashMap<>();

    public AiTiers(SuperMoeService superMoe,
                   HeatStaggerAdvisorService heatStagger,
                   SlotSplitAdvisorService slotSplit) {
        this.superMoe = superMoe;
        this.heatStagger = heatStagger;
        this.slotSplit = slotSplit;
        calls.put("primary", new long[1]);
        calls.put("heatStagger", new long[1]);
        calls.put("slotSplit", new long[1]);
    }

    /** 环节名（与 status / 日志口径一致）。 */
    public static final String TIER_PRIMARY = "primary";
    public static final String TIER_HEAT_STAGGER = "heatStagger";
    public static final String TIER_SLOT_SPLIT = "slotSplit";

    /**
     * 第一次编排：调主 MoE。
     *
     * <p>失败或模型缺失时返回 {@link java.util.Optional#empty()}，
     * 调用方走贪心 + 精修链的原路径。</p>
     */
    public java.util.Optional<SuperMoeService.Advice> advisePrimary(
            SuperScheduleEncoder.Encoded enc) {
        java.util.Optional<SuperMoeService.Advice> r = superMoe.advise(enc);
        if (r.isPresent()) {
            calls.get(TIER_PRIMARY)[0]++;
        }
        return r;
    }

    /** 组次错开：调专项 MoE（在已过滤的合法候选里给建议）。 */
    public java.util.Optional<Integer> adviseHeatStagger(
            int heatCount, int perRound, java.util.List<Integer> legal,
            int curHeat, int[] gapOf, double[] fillOf) {
        java.util.Optional<Integer> r =
                heatStagger.suggestHeat(heatCount, perRound, legal, curHeat, gapOf, fillOf);
        if (r.isPresent()) {
            calls.get(TIER_HEAT_STAGGER)[0]++;
        }
        return r;
    }

    /**
     * 跨时段拆分：调专项 MoE（决定「先拆谁」）。
     *
     * <p>候选不足 2 个时模型不参与（没有排序可言），返回 empty 由调用方按原顺序试。</p>
     */
    public java.util.Optional<java.util.List<Integer>> adviseSlotSplit(
            java.util.List<SlotSplitAdvisorService.Candidate> candidates,
            int amFree, int pmFree) {
        java.util.Optional<java.util.List<Integer>> r =
                slotSplit.suggestSplitOrder(candidates, amFree, pmFree);
        if (r.isPresent()) {
            calls.get(TIER_SLOT_SPLIT)[0]++;
        }
        return r;
    }

    /**
     * 各环节可用性 + 调用次数。
     *
     * <p>{@code wiredButNeverCalled} 是本方法最有价值的一列：
     * 模型可用但调用数为 0，说明「接线正确但从未通电」——
     * 这类故障不会报错、只是功能一直没生效，只能靠这里暴露。</p>
     */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TIER_PRIMARY, tier(superMoe.available(), TIER_PRIMARY));
        out.put(TIER_HEAT_STAGGER, tier(heatStagger.isAvailable(), TIER_HEAT_STAGGER));
        out.put(TIER_SLOT_SPLIT, tier(slotSplit.isAvailable(), TIER_SLOT_SPLIT));
        boolean any = superMoe.available() || heatStagger.isAvailable()
                || slotSplit.isAvailable();
        out.put("anyAvailable", any);
        if (!any) {
            log.info("AI 分层调度：三个模型均不可用，编排全程走规则（功能不受影响）");
        }
        return out;
    }

    private Map<String, Object> tier(boolean available, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        long n = calls.getOrDefault(name, new long[1])[0];
        m.put("available", available);
        m.put("calls", n);
        m.put("wiredButNeverCalled", available && n == 0);
        return m;
    }
}
