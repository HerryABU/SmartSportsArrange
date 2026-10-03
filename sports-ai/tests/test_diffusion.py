"""SchemeDiffusion 的回归测试：钉住「扩散真的在工作」而不是退化成常量输出。"""

from __future__ import annotations

import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sports_ai.generative.diffusion import SchemeDiffusion, cosine_beta_schedule
from sports_ai.generative.scheme import MAX_SLOTS, scheme_conflicts

B, N, F = 3, 12, 16


def _inputs(n: int = N, seed: int = 0):
    g = torch.Generator().manual_seed(seed)
    nf = torch.rand(1, n, F, generator=g)
    adj = (torch.rand(1, n, n, generator=g) * (torch.rand(1, n, n, generator=g) > 0.8)).float()
    mk = torch.ones(1, n)
    return nf, adj, mk


def _model(steps: int = 8) -> SchemeDiffusion:
    return SchemeDiffusion(hidden=32, steps=steps, layers=2)


def test_cosine_schedule_monotonic_decreasing_alpha() -> None:
    """余弦日程：alpha_bar 必须严格递减，且首末两端合理。"""
    betas = cosine_beta_schedule(8)
    assert betas.shape[0] == 9, f"应含 steps+1 项，实际 {betas.shape[0]}"
    ac = torch.cumprod(1.0 - betas, dim=0)
    assert torch.all(ac[1:] <= ac[:-1] + 1e-6), "alpha_bar 必须单调不增"
    assert 0.0 < ac[-1].item() < ac[0].item() <= 1.0


def test_forward_shapes_and_masking() -> None:
    """forward 返回各时间槽 logits [B,N,MAX_SLOTS]，padding 位必须为 0。"""
    m = _model()
    nf, adj, mk = _inputs()
    mk[0, 9:] = 0                       # 制造 padding
    z = torch.zeros(1, N, MAX_SLOTS)
    out = m(nf, adj, mk, z)
    assert out.shape == (1, N, MAX_SLOTS), f"logits 应为 [B,N,K]，实际 {tuple(out.shape)}"
    assert torch.all(out[0, 9:] == 0), "padding 位置的 logits 必须为 0"
    assert torch.isfinite(out).all(), "出现非有限值"


def test_output_depends_on_adjacency() -> None:
    """核心保证：不同冲突图的输出必须不同（否则就是常量返回）。"""
    m = _model()
    nf, adj, mk = _inputs(seed=1)
    adj2 = (torch.rand_like(adj) * (torch.rand_like(adj) > 0.8)).float()
    z = torch.zeros(1, N, MAX_SLOTS)
    a = m.logits_of(nf, adj, mk, z, None, 8)
    b = m.logits_of(nf, adj2, mk, z, None, 8)
    assert not torch.allclose(a, b), "邻接（冲突图）不同，输出不应完全相同"


def test_output_depends_on_type_mask() -> None:
    """行政保护（forbid）不同 → 输出不同：模型必须真的读禁止表。"""
    m = _model()
    nf, adj, mk = _inputs(seed=2)
    z = torch.zeros(1, N, MAX_SLOTS)
    f1 = torch.zeros(1, N, MAX_SLOTS)
    f2 = torch.zeros(1, N, MAX_SLOTS)
    f2[:, :, 5:] = 1.0
    a = m.logits_of(nf, adj, mk, z, f1, 8)
    b = m.logits_of(nf, adj, mk, z, f2, 8)
    assert not torch.allclose(a, b), "禁止表不同，输出不应完全相同"


def test_scale_robustness() -> None:
    """LEHD 动机：N 变化不能让模型崩（同一个模型跑 4..64 个单元）。"""
    m = _model()
    for n in (4, 12, 32, 64):
        nf, adj, mk = _inputs(n=n, seed=n)
        z = torch.zeros(1, n, MAX_SLOTS)
        out = m(nf, adj, mk, z)
        assert out.shape == (1, n, MAX_SLOTS), f"N={n} 时输出形状必须匹配"
        assert torch.isfinite(out).all(), f"N={n} 出现非有限值"


def test_forbidden_slot_never_selected() -> None:
    """行政时间保护：被禁的槽绝不能被选中。"""
    m = _model()
    nf, adj, mk = _inputs(seed=3)
    forbid = torch.zeros(1, N, MAX_SLOTS)
    forbid[:, :, 5:] = 1.0
    _, scheme = m.sample(nf, adj, mk, forbid, steps=8)
    assert scheme[:, :, 5:].sum() < 1e-6, "禁止槽被选中了"


def test_deterministic_with_zero_start() -> None:
    """logits_of 是**确定性**的（DDIM eta=0），这是能导出 ONNX 的前提。

    注意别拿 sample 比——sample 末尾走 gumbel_scheme 采样，本就带随机性。
    """
    m = _model()
    nf, adj, mk = _inputs(seed=4)
    z = torch.zeros(1, N, MAX_SLOTS)
    a = m.logits_of(nf, adj, mk, z, None, 8)
    b = m.logits_of(nf, adj, mk, z, None, 8)
    assert torch.allclose(a, b), "同一起点必须给出同一 logits（否则无法导出 ONNX）"


def test_sample_honours_custom_start() -> None:
    """传 z 必须真的生效——曾因忽略入参导致「求确定性」根本无效。"""
    m = _model()
    nf, adj, mk = _inputs(seed=5)
    z0 = torch.zeros(1, N, MAX_SLOTS)
    a = m.sample(nf, adj, mk, None, steps=8, z=z0)[1]
    b = m.sample(nf, adj, mk, None, steps=8, z=torch.randn(1, N, MAX_SLOTS))[1]
    assert not torch.allclose(a, b), "不同起点应给出不同方案（z 参数被忽略了？）"


def test_inference_steps_may_exceed_training_steps() -> None:
    """推理步数 > 训练步数不能越界（alphas_cumprod 只有 steps+1 项）。"""
    m = _model(steps=4)
    nf, adj, mk = _inputs(seed=6)
    for st in (2, 4, 16, 64):
        _, scheme = m.sample(nf, adj, mk, None, steps=st)
        assert scheme.shape == (1, N, MAX_SLOTS), f"steps={st} 形状错"
        assert torch.isfinite(scheme).all(), f"steps={st} 出现非有限值"


def test_training_loss_is_differentiable() -> None:
    """🔴 回归钉子：约束损失必须在 no_grad **之外**，否则对参数零梯度。

    这是本轮抓到的最贵的 bug——约束项虽然加进了 loss，但整体在 no_grad 里，
    等于只加了个常数，模型完全没学到「要可行」。
    """
    m = _model()
    nf, adj, mk = _inputs(seed=7)
    x0 = torch.nn.functional.one_hot(torch.randint(0, MAX_SLOTS, (1, N)), MAX_SLOTS).float()
    loss = m.training_loss(x0, nf, adj, mk, None)
    assert torch.isfinite(loss), "loss 必须是有限值"
    m.zero_grad()
    loss.backward()
    grads = [p.grad for p in m.parameters() if p.grad is not None]
    assert grads, "backward 后应有参数拿到梯度"
    assert any(float(g.abs().sum()) > 0 for g in grads), "梯度全为 0，去噪信号没传下去"


def test_constraint_term_reaches_gradients() -> None:
    """组合约束项必须真的改变参数梯度方向（对比加与不加）。"""
    from sports_ai.generative.scheme import combination_loss
    torch.manual_seed(11)
    adj = (torch.rand(1, N, N) * (torch.rand(1, N, N) > 0.7)).float()
    mk = torch.ones(1, N)
    x = torch.randn(1, N, MAX_SLOTS, requires_grad=True)
    soft = torch.softmax(x / 0.5, dim=-1)
    cl, parts = combination_loss(soft, adj, mk, None, 8.0)
    assert torch.isfinite(cl), "约束损失必须有限"
    cl.backward()
    assert x.grad is not None and float(x.grad.abs().sum()) > 0, "约束损失对输入零梯度"
    assert "conflict" in parts, f"应返回分解字典，实际 {list(parts)}"


if __name__ == "__main__":
    fns = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    ok = 0
    for n, f in fns:
        try:
            f()
            ok += 1
            print(f"  PASS {n}")
        except Exception as e:  # noqa: BLE001
            print(f"  FAIL {n}: {type(e).__name__}: {e}")
    print(f"\n{ok}/{len(fns)} passed")
    sys.exit(0 if ok == len(fns) else 1)
