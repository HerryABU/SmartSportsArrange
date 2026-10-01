package com.sports.schedule.ai;

/**
 * AI 编排建议——选择器与冲突簇 GNN 的推理结果。
 *
 * @param strategy          硬解还是取消路径（由算法选择器 softmax 决定）
 * @param cancelProbability 取消路径的估计概率 [0,1]
 * @param nodePriority      各单元的着色优先级（GNN 输出，按 {@code units} 顺序对齐，
 *                          长度 = min(单元数, {@link ConflictGraphEncoder#MAX_NODES})）
 */
public record AiAdvisory(Strategy strategy, double cancelProbability, double[] nodePriority) {

    public enum Strategy { HARD_SOLVE, CANCEL_PATH }

    /** 置信度：取消概率偏离 0.5 越远越可信。低置信时调用方应回退到规则决策。 */
    public boolean confident() {
        return Math.abs(cancelProbability - 0.5) >= 0.15;
    }
}
