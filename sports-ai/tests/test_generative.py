"""GAN 组件与方案契约的形状/性质测试。"""

import numpy as np
import torch

from sports_ai.data.features import NODE_FEAT_DIM
from sports_ai.data.gnn_io import TRAIN_PAD_TO
from sports_ai.data.generator import generate_scenario
from sports_ai.data.gnn_io import encode_gnn_inputs
from sports_ai.generative.discriminator import SchemeDiscriminator
from sports_ai.generative.generator import SchemeGenerator
from sports_ai.generative.oracle import oracle_batch
from sports_ai.generative.scheme import MAX_SLOTS, combination_loss, scheme_conflicts


def _batch(seed=0):
    s = generate_scenario(seed=seed, n_athletes=120)
    nf, adj, mask, _ = encode_gnn_inputs(s, pad_to=TRAIN_PAD_TO)
    return (torch.from_numpy(nf), torch.from_numpy(adj), torch.from_numpy(mask))


def test_oracle_produces_one_hot():
    nf, adj, mask = _batch()
    real = oracle_batch(adj, mask, None, MAX_SLOTS)
    assert real.shape == (1, TRAIN_PAD_TO, MAX_SLOTS)
    rows = real[0, : int(mask[0].sum())]
    assert torch.allclose(rows.sum(-1), torch.ones(rows.shape[0]), atol=1e-5)
    assert set(rows.unique().tolist()) <= {0.0, 1.0}


def test_generator_output_shapes_and_valid_probs():
    nf, adj, mask = _batch()
    g = SchemeGenerator()
    z = torch.randn(1, TRAIN_PAD_TO, 8)
    logits, scheme = g(nf, adj, mask, z, forbid=None)
    assert logits.shape == (1, TRAIN_PAD_TO, MAX_SLOTS)
    assert scheme.shape == (1, TRAIN_PAD_TO, MAX_SLOTS)
    n = int(mask[0].sum())
    assert torch.allclose(scheme[0, :n].sum(-1), torch.ones(n), atol=1e-5), "每节点概率和为 1"


def test_discriminator_output_shape():
    nf, adj, mask = _batch()
    d = SchemeDiscriminator()
    scheme = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    out = d(nf, adj, mask, scheme)
    assert out.shape == (1, 1)


def test_combination_loss_finite_and_penalizes_forbid():
    nf, adj, mask = _batch()
    g = SchemeGenerator()
    z = torch.randn(1, TRAIN_PAD_TO, 8)
    forbid = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    _, scheme = g(nf, adj, mask, z, forbid=forbid)
    loss, parts = combination_loss(scheme, adj, mask, forbid, capacity=4.0)
    assert torch.isfinite(loss)
    assert parts["conflict"] >= 0.0
    # 生成器输出对禁止槽的占用应为 0（架构上已屏蔽）
    forbid2 = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    forbid2[:, :, 0] = 1.0
    _, scheme2 = g(nf, adj, mask, z, forbid=forbid2)
    assert scheme2[..., 0].abs().sum().item() == 0.0, "禁止槽不得被占用"


def test_conflicts_zero_when_all_distinct():
    # 全部节点各占不同槽 → 冲突应为 0
    p = torch.eye(MAX_SLOTS)[:TRAIN_PAD_TO].unsqueeze(0) if TRAIN_PAD_TO <= MAX_SLOTS else None
    n = min(TRAIN_PAD_TO, MAX_SLOTS)
    P = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    for i in range(n):
        P[0, i, i] = 1.0
    adj = torch.ones(1, TRAIN_PAD_TO, TRAIN_PAD_TO) - torch.eye(TRAIN_PAD_TO).unsqueeze(0)
    mask = torch.zeros(1, TRAIN_PAD_TO)
    mask[0, :n] = 1.0
    c = scheme_conflicts(P, adj, mask)
    assert abs(c.item()) < 1e-5, "全不同槽不应有同色冲突"


def test_refiner_shapes():
    from sports_ai.generative.refiner import SchemeRefiner

    nf, adj, mask = _batch()
    r = SchemeRefiner()
    init = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    out = r(nf, adj, mask, init, None)
    assert out.shape == (1, TRAIN_PAD_TO, MAX_SLOTS)


def test_hard_conflict_helper():
    from sports_ai.generative.refine import hard_conflict

    n = 3
    adj = torch.zeros(1, TRAIN_PAD_TO, TRAIN_PAD_TO)
    mask = torch.zeros(1, TRAIN_PAD_TO)
    mask[0, :n] = 1.0
    for i in range(n):
        for j in range(n):
            if i != j:
                adj[0, i, j] = 1.0
    logits_all_same = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    logits_all_same[0, :, 0] = 10.0            # 全部倾向槽 0
    assert abs(hard_conflict(logits_all_same, adj, mask).item() - 1.0) < 1e-6
    logits_distinct = torch.zeros(1, TRAIN_PAD_TO, MAX_SLOTS)
    for i in range(n):
        logits_distinct[0, i, i] = 10.0
    assert abs(hard_conflict(logits_distinct, adj, mask).item()) < 1e-6
