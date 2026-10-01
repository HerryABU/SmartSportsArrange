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


def test_gnn_shapes():
    s = generate_scenario(seed=3, n_athletes=120)
    nf, adj, mask, label = encode_gnn_inputs(s)
    assert nf.shape == (1, MAX_NODES, NODE_FEAT_DIM)
    assert adj.shape == (1, MAX_NODES, MAX_NODES)
    assert mask.shape == (1, MAX_NODES)
    assert label.shape == (MAX_NODES,)
    # 邻接对称、无自环、只在 mask 内
    a = adj[0]
    assert np.allclose(a, a.T)
    assert np.allclose(np.diag(a), 0.0)
    n = int(mask[0].sum())
    assert np.allclose(a[n:, :], 0.0)
    assert np.allclose(a[:, n:], 0.0)


def test_conflict_edges_consistent():
    # 兼项边数与冲突图度数口径一致：label 度数之和 * (n-1) = 2 * 冲突边数
    s = generate_scenario(seed=7)
    f = extract_features(s)
    _, _, mask, label = encode_gnn_inputs(s)
    n = int(mask[0].sum())
    if n >= 2:
        degree_sum = label[:n].sum() * (n - 1)
        assert abs(degree_sum - 2 * f[6]) < 1e-3, "度数之和应等于 2×冲突边数"
