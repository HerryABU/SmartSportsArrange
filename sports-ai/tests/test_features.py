"""特征契约自检：验证数据生成器与特征提取器的正确性（不依赖 torch）。"""

import numpy as np

from sports_ai.data.features import MAX_NODES, N_FEATURES, NODE_FEAT_DIM, extract_features
from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import encode_gnn_inputs


def test_feature_dim():
    s = generate_scenario(seed=1)
    f = extract_features(s)
    assert len(f) == N_FEATURES == 16


def test_determinism():
    f1 = extract_features(generate_scenario(seed=42))
    f2 = extract_features(generate_scenario(seed=42))
    assert f1 == f2


def test_tension_range():
    # 天越少容量越紧张，tension 越高
    tight = extract_features(generate_scenario(seed=1, n_days=1, n_athletes=300))[3]
    loose = extract_features(generate_scenario(seed=1, n_days=4, n_athletes=80))[3]
    assert tight > loose


def test_gnn_shapes_dynamic():
    """节点数是**动态轴**：shape 随实例变化，不再补齐到固定 1024。"""
    s = generate_scenario(seed=3, n_athletes=120)
    nf, adj, mask, label = encode_gnn_inputs(s)
    n = nf.shape[1]
    assert nf.shape == (1, n, NODE_FEAT_DIM)
    assert adj.shape == (1, n, n)
    assert mask.shape == (1, n)
    assert label.shape == (n,)
    assert 0 < n <= MAX_NODES
    a = adj[0]
    assert np.allclose(a, a.T), "邻接应对称"
    assert np.allclose(np.diag(a), 0.0), "无自环"


def test_gnn_padding_is_neutral():
    """补齐不影响真实节点输出 —— 这是「训练补齐、推理不补齐」成立的前提。"""
    import torch

    from sports_ai.models.gnn import ConflictGnn

    s = generate_scenario(seed=11, n_athletes=120)
    nf0, adj0, mk0, _ = encode_gnn_inputs(s)                       # 动态（推理路径）
    nf1, adj1, mk1, _ = encode_gnn_inputs(s, pad_to=256)           # 补齐（训练路径）
    n = nf0.shape[1]
    assert nf1.shape[1] == 256 and adj1.shape[1] == 256

    g = ConflictGnn()
    g.eval()
    with torch.no_grad():
        o0 = g(torch.from_numpy(nf0), torch.from_numpy(adj0), torch.from_numpy(mk0))
        o1 = g(torch.from_numpy(nf1), torch.from_numpy(adj1), torch.from_numpy(mk1))
    assert o0.shape == (1, n) and o1.shape == (1, 256)
    assert np.allclose(o0[0].numpy(), o1[0, :n].numpy(), atol=1e-4), \
        "补齐 191 个 padding 节点后，真实节点输出必须不变"
    assert np.allclose(o1[0, n:].numpy(), 0.0), "padding 输出应为 0"


def test_gnn_accepts_arbitrary_scale():
    """同一组权重（与节点数无关）应同时接受极小图与上百节点的图 —— 归纳式 GNN 的核心性质。"""
    import torch

    from sports_ai.models.gnn import ConflictGnn

    g = ConflictGnn()
    g.eval()
    for n in (1, 2, 37, 257):
        nf = np.zeros((1, n, NODE_FEAT_DIM), dtype=np.float32)
        adj = np.zeros((1, n, n), dtype=np.float32)
        mk = np.ones((1, n), dtype=np.float32)
        with torch.no_grad():
            out = g(torch.from_numpy(nf), torch.from_numpy(adj), torch.from_numpy(mk))
        assert out.shape == (1, n)


def test_gnn_reports_overflow():
    """单元数超上限时如实报告，不静默丢弃。"""
    from sports_ai.data.generator import Scenario, Unit

    over = 25
    units = [Unit(key=f"u{i}", event_id=i, event_name="项目", grade="高一", track=True,
                  pool_label="径赛", group_key=None, raw_duration=10, athletes=[i])
             for i in range(MAX_NODES + over)]
    sc = Scenario(units=units, placements=[])
    nf, _adj, _mk, _lb, meta = encode_gnn_inputs(sc, return_meta=True)
    assert meta["nodeCount"] == MAX_NODES
    assert meta["totalUnits"] == MAX_NODES + over
    assert meta["dropped"] == over, "必须如实报告被截断的单元数"
    assert nf.shape[1] == MAX_NODES


def test_adjacency_is_weighted():
    """邻接带权：共享 2 人的边权应是共享 1 人的两倍。"""
    from sports_ai.data.generator import Scenario, Unit

    units = [
        Unit(key="a", event_id=1, event_name="x", grade="高一", track=True,
             pool_label="径赛", group_key=None, raw_duration=10, athletes=[1, 2, 3]),
        Unit(key="b", event_id=2, event_name="y", grade="高一", track=True,
             pool_label="径赛", group_key=None, raw_duration=10, athletes=[2, 3]),   # 与 a 共享 2 人
        Unit(key="c", event_id=3, event_name="z", grade="高一", track=True,
             pool_label="径赛", group_key=None, raw_duration=10, athletes=[1]),      # 与 a 共享 1 人
    ]
    _nf, adj, _mk, _lb = encode_gnn_inputs(Scenario(units=units, placements=[]))
    a = adj[0]
    assert a[0, 1] > a[0, 2] > 0, "共享人数多的边权必须更大（二值邻接会丢掉这个信息）"
    assert abs(a[0, 1] - 1.0) < 1e-6, "最大共享 2 人 → 归一化权重 1.0"
    assert abs(a[0, 2] - 0.5) < 1e-6


def test_conflict_edges_consistent():
    # 兼项边数与冲突图度数口径一致：label 度数之和 * (n-1) = 2 * 冲突边数
    s = generate_scenario(seed=7)
    f = extract_features(s)
    _, _, mask, label = encode_gnn_inputs(s)
    n = int(mask[0].sum())
    if n >= 2:
        degree_sum = label[:n].sum() * (n - 1)
        assert abs(degree_sum - 2 * f[6]) < 1e-3, "度数之和应等于 2×冲突边数"
