"""专家表征相似度度量的契约测试。"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import pytest

torch = pytest.importorskip("torch")


def _model_and_batch():
    from sports_ai.models.super_moe import (SuperScheduleMoE, NODE_FEAT_DIM, N_EDGES,
                                            GRAPH_FEAT_DIM, MAX_SLOTS, N_TASKS)
    m = SuperScheduleMoE(hidden=32, steps=4, expert_depth=1, n_global=1)
    m.eval()
    B, N = 2, 6
    return m, (torch.randn(B, N, NODE_FEAT_DIM) * 0.3,
               torch.zeros(B, N_EDGES, N, N),
               torch.zeros(B, N_EDGES),
               torch.ones(B, N),
               torch.full((B, GRAPH_FEAT_DIM), 0.3))


def test_expert_similarity_shape_and_symmetry():
    m, batch = _model_and_batch()
    # ⚠️ 必须经 model_diagnostics 取（它内部先做投影再喂 MoE）。
    #    直接把原始 node_feat 当 h 传进 MoE 会形状不匹配 ——
    #    维度看着都是 hidden 级，但语义完全不同，报错是
    #    「mat1 and mat2 shapes cannot be multiplied」而不是「参数传错」。
    sim = m.model_diagnostics(*batch)["expert_similarity"]
    S = sim["matrix"]
    E = sim["n_experts"]
    assert E == m.moe.n_experts and len(S) == E and all(len(r) == E for r in S)
    for i in range(E):
        assert abs(S[i][i] - 1.0) < 1e-3, "自相似必须为 1"
        for j in range(E):
            assert -1.0 <= S[i][j] <= 1.0, "余弦相似度必须在 [-1,1]"
            assert abs(S[i][j] - S[j][i]) < 1e-3, "相似度矩阵必须对称"


def test_expert_similarity_reports_cross_group():
    """必须给出「任务专家 × 能力专家」的跨组相似度 —— 它是异构门控是否有效的判据。"""
    from sports_ai.models.super_moe import N_UNIT_TASKS, N_TASKS
    m, batch = _model_and_batch()
    # ⚠️ 必须经 model_diagnostics 取（它内部先做投影再喂 MoE）。
    #    直接把原始 node_feat 当 h 传进 MoE 会形状不匹配 ——
    #    维度看着都是 hidden 级，但语义完全不同，报错是
    #    「mat1 and mat2 shapes cannot be multiplied」而不是「参数传错」。
    sim = m.model_diagnostics(*batch)["expert_similarity"]
    assert N_UNIT_TASKS < N_TASKS
    assert "cross_mean" in sim and "cap_within_mean" in sim
    assert -1.0 <= sim["cross_mean"] <= 1.0


def test_model_diagnostics_populates_usage():
    """``model_diagnostics`` 必须能填上使用率。

    ⚠️ 回归钉子：``expert_usage`` 读的是 forward 缓存的 ``_last_gate_probs``。
    第一版诊断入口只算相似度、**没跑 MoE forward**，于是 usage 恒为空字典 ——
    调用方拿到 ``{}`` 而不是报错，属静默失效。
    """
    m, batch = _model_and_batch()
    d = m.model_diagnostics(*batch)
    assert d["expert_usage"], "使用率不得为空（必须先跑一次 MoE forward）"
    assert len(d["expert_usage"]) == m.moe.n_experts
    assert 0.0 <= d["route_entropy"] <= 1.0
    assert "expert_similarity" in d


def test_similarity_is_not_todays_random_init_noise():
    """未训练模型也应给出**有限**的相似度（不是 NaN/全 1），保证度量本身可用。"""
    m, batch = _model_and_batch()
    # ⚠️ 必须经 model_diagnostics 取（它内部先做投影再喂 MoE）。
    #    直接把原始 node_feat 当 h 传进 MoE 会形状不匹配 ——
    #    维度看着都是 hidden 级，但语义完全不同，报错是
    #    「mat1 and mat2 shapes cannot be multiplied」而不是「参数传错」。
    sim = m.model_diagnostics(*batch)["expert_similarity"]
    assert sim["mean"] == sim["mean"], "不得是 NaN"
    assert sim["min"] <= sim["mean"] <= sim["max"]
