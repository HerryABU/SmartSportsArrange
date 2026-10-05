"""Diffusion 方案生成器：逐步去噪生成时间槽分配方案（替代 GAN 的 minmax 训练）。

## 为什么要换掉 GAN

现有 {@code generative/generator.py + discriminator.py} 是标准 GAN。GAN 在本任务上有三个
已知的结构性痛点：

1. **minmax 训练不稳**——生成器与判别器互相追逐，损失此消彼长，极易模式崩塌；
   而我们的下游只需要「一个可行解」，崩塌的代价是整轮白训。
2. **需要判别器**——多一个网络、多一份权重、多一处推理开销，而 D 学到的
   「像不像可行解」最终还是要靠真约束校验才能确认。
3. **推理不可控**——GAN 推理只能靠采样，随机性无法通过架构调节。

## Diffusion 的优势（对应 DISCO / DIFUSCO 一系）

* **训练是简单的去噪回归**（MSE），没有 minmax、没有两个网络互相博弈；
* **逐步去噪天然满足约束趋势**——从纯噪声一步步退火到方案，中间过程可插入
  硬约束投影（禁止槽、装箱容量），每一步都在往可行域靠；
* **推理步数可控**——步数越多质量越好，调用方可按预算取舍（这是 GAN 做不到的）。

## 与既有组件的关系

* 复用 {@code .encoder.GnnEncoder}（单通道邻接）作为条件编码器，**不改变 ONNX 输入契约**；
* 复用 {@code .scheme.gumbel_scheme}，但**只在最后一轮**用它出硬解（推理确定性）；
* 新增 {@code train_diffusion.py} 与 {@code export_diffusion_onnx.py}，与旧 GAN 并存，
  Java 端可通过配置切换，不破坏既有链路。
"""

from __future__ import annotations

import torch
import torch.nn as nn

from .encoder import GnnEncoder
from sports_ai.nn.moe_encoder import MoEEncoder, make_moe_encoder
from ..data.features import NODE_FEAT_DIM
from .scheme import MAX_SLOTS, gumbel_scheme


def cosine_beta_schedule(steps: int, s: float = 0.008) -> torch.Tensor:
    """余弦噪声日程（Nichol & Dhariwal）：比线性日程在中间步信噪比更均匀。"""
    t = torch.linspace(0, steps, steps + 1, dtype=torch.float64)
    f = torch.cos(((t / steps) + s) / (1 + s) * torch.pi * 0.5) ** 2
    alphas_cumprod = (f / f[0]).clamp(1e-8, 1.0)
    betas = torch.cat([torch.zeros(1, dtype=torch.float64),
                       (1 - alphas_cumprod[1:] / alphas_cumprod[:-1]).clamp(1e-8, 0.999)])
    return betas.float()


class ResidualBlock(nn.Module):
    """一维时间步条件的残差块（DDPM 标准结构）。"""

    def __init__(self, hidden: int, time_dim: int):
        super().__init__()
        self.t1 = nn.Linear(time_dim, hidden)
        self.n1 = nn.LayerNorm(hidden)
        self.f1 = nn.Linear(hidden, hidden)
        self.f2 = nn.Linear(hidden, hidden)
        self.n2 = nn.LayerNorm(hidden)

    def forward(self, x: torch.Tensor, temb: torch.Tensor) -> torch.Tensor:
        h = self.n1(x + self.t1(temb).unsqueeze(1))
        h = torch.relu(self.f1(h))
        h = self.f2(h)
        return self.n2(x + h)


class SchemeDiffusion(nn.Module):
    """条件扩散生成器：噪声方案 →（逐步去噪）→ 时间槽分配。

    条件是冲突图（node_feat/adj/mask）+ 禁止表（forbid）。
    每一步预测噪声 ε，网络结构与步数无关（时间步是**嵌入**而非结构），
    因此训练用 8 步、推理用 32 步是同一个模型。
    """

    def __init__(self, node_feat: int = NODE_FEAT_DIM, hidden: int = 192, noise: int = 8,
                 slots: int = MAX_SLOTS, steps: int = 8, layers: int = 6,
                 moe: bool = True, moe_layers: int = 6, moe_experts: int = 9):
        super().__init__()
        self.slots = slots
        self.steps = steps
        # 条件编码：冲突图 → [B,N,H]
        self.enc = (make_moe_encoder(node_feat, hidden, n_layers=moe_layers,
                                     n_experts=moe_experts)
                    if moe else GnnEncoder(node_feat, hidden))
        # 噪声预测网络：吃 [去噪中的方案, 条件嵌入, 时间步嵌入]
        # ⚠️ 时间步嵌入的**第一层输入维度是 1**（一个标量 t），不是 steps。
        #    写 nn.Linear(steps, ...) 后传 [B,1] 会报
        #    "mat1 and mat2 shapes cannot be multiplied (3x1 and 8x32)"。
        self.time_dim = hidden // 2
        self.temb = nn.Sequential(nn.Linear(1, self.time_dim), nn.SiLU(),
                                  nn.Linear(self.time_dim, self.time_dim))
        self.inp = nn.Linear(slots + hidden, hidden)
        self.blocks = nn.ModuleList([ResidualBlock(hidden, self.time_dim) for _ in range(layers)])
        self.out = nn.Sequential(nn.LayerNorm(hidden), nn.Linear(hidden, slots))
        # 噪声日程用 buffer 固化，导出时不会变成可训练参数
        self.register_buffer("betas", cosine_beta_schedule(steps), persistent=False)
        alphas = 1.0 - self.betas
        self.register_buffer("alphas_cumprod", torch.cumprod(alphas, dim=0), persistent=False)

    # ------------------------------------------------------------------
    def forward(self, node_feat: torch.Tensor, adj: torch.Tensor, mask: torch.Tensor,
                z: torch.Tensor, forbid: torch.Tensor | None = None):
        """标准 forward：等价于 ``logits_of(..., steps=self.steps)``。

        必须提供 forward，否则本类无法当普通 nn.Module 使用
        （``NotImplementedError: Module is missing the required "forward" function``），
        也无法被 torch.onnx.export 直接导出。
        """
        return self.logits_of(node_feat, adj, mask, z, forbid, self.steps)

    # ------------------------------------------------------------------
    def predict_noise(self, x: torch.Tensor, node_feat: torch.Tensor, adj: torch.Tensor,
                      mask: torch.Tensor, t_idx: torch.Tensor,
                      forbid: torch.Tensor | None = None) -> torch.Tensor:
        """预测 t 步上的噪声。x: [B,N,K]（当前带噪方案） → [B,N,K]。"""
        h_cond = self.enc(node_feat, adj, mask)                     # [B,N,H]
        te = self.temb(t_idx.float().unsqueeze(-1) / max(1, self.steps))  # [B,time_dim]
        h = self.inp(torch.cat([x, h_cond], dim=-1))               # [B,N,H]
        for blk in self.blocks:
            h = blk(h, te)
        eps = self.out(h) * mask.unsqueeze(-1)
        if forbid is not None:
            # 禁止槽不参与重建：预测值置 0，让模型无需浪费容量去学「不能选」
            eps = eps * (1.0 - (forbid > 0.5).float())
        return eps

    # ------------------------------------------------------------------
    def q_sample(self, x0: torch.Tensor, t: torch.Tensor,
                 noise: torch.Tensor) -> torch.Tensor:
        """前向加噪（DDPM 定义）。"""
        a = self.alphas_cumprod[t].view(-1, 1, 1)
        return a.sqrt() * x0 + (1 - a).sqrt() * noise

    def training_loss(self, x0: torch.Tensor, node_feat: torch.Tensor, adj: torch.Tensor,
                      mask: torch.Tensor, forbid: torch.Tensor | None = None) -> torch.Tensor:
        """训练损失就是 ε 预测的 MSE——**没有 minmax，没有对抗**。"""
        b, n, k = x0.shape
        t = torch.randint(0, self.steps, (b,), device=x0.device)
        noise = torch.randn_like(x0)
        xt = self.q_sample(x0, t, noise)
        pred = self.predict_noise(xt, node_feat, adj, mask, t, forbid)
        m = mask.unsqueeze(-1)
        # 分母用有效元素数，避免 mask 让 loss 被稀释
        return (((pred - noise) ** 2) * m).sum() / m.sum().clamp(min=1.0) / k

    # ------------------------------------------------------------------
    def sample(self, node_feat: torch.Tensor, adj: torch.Tensor, mask: torch.Tensor,
               forbid: torch.Tensor | None = None, steps: int | None = None,
               guidance: float = 0.0, hard: bool = True, z: torch.Tensor | None = None):
        """推理：DDPM 逐步去噪。返回 (logits, scheme)。

        :param steps: 推理步数。**允许大于训练步数**——时间步是嵌入不是结构，
            但 ``alphas_cumprod`` 只有 ``steps+1`` 项，索引越界会 IndexError，
            所以这里把步数映射到训练日程的有效下标。
        :param z: 起点。**不传则用 randn**（多样性）；传 0 向量则确定性。
        :param guidance: >0 时启用简化版 classifier-free guidance：
            「无 forbid 条件」与「有 forbid 条件」的预测做外推，放大硬约束的影响。
        """
        train_steps = self.steps
        steps = steps or train_steps
        b, n, k = node_feat.shape[0], node_feat.shape[1], self.slots
        # ⚠️ 旧实现忽略了传入的 z（自己 randn），导致「传 z=0 求确定性」根本无效。
        x = (torch.randn_like(node_feat[:, :, :k]) if z is None else z) * mask.unsqueeze(-1)
        if forbid is not None:
            x = x * (1.0 - (forbid > 0.5).float())
        # 推理步数 → 训练日程下标（支持 steps > train_steps）
        idx_of = [min(int(round(i * (train_steps - 1) / max(1, steps - 1))), train_steps - 1)
                  for i in range(steps)][::-1]
        for pos in range(steps):
            i = idx_of[pos]
            t = torch.full((b,), i, device=node_feat.device, dtype=torch.long)
            eps = self.predict_noise(x, node_feat, adj, mask, t, forbid)
            if guidance > 0 and forbid is not None:
                eps_uncond = self.predict_noise(x, node_feat, adj, mask, t, None)
                eps = eps_uncond + guidance * (eps - eps_uncond)
            a = self.alphas_cumprod[i]
            a_prev = self.alphas_cumprod[i - 1] if i > 0 else torch.tensor(1.0, device=x.device)
            # 注入噪声只在「下标真的前进了」时做，否则多步映射会重复采样同一档
            if pos + 1 < steps and idx_of[pos + 1] < i:
                beta_tilde = (1 - a_prev) / (1 - a) * (1 - a / a_prev)
                mean = (x - (1 - a).sqrt() * eps) / a.sqrt()
                x = mean + beta_tilde.sqrt() * torch.randn_like(x)
            else:
                x = (x - (1 - a).sqrt() * eps) / a.sqrt()
            x = x * mask.unsqueeze(-1)
        logits = x
        if forbid is not None:
            logits = logits.masked_fill(forbid > 0.5, -1e9)
        logits = logits * mask.unsqueeze(-1)
        scheme = gumbel_scheme(logits, tau=1.0, hard=hard) * mask.unsqueeze(-1)
        return logits, scheme

    @torch.no_grad()
    def logits_of(self, node_feat, adj, mask, z, forbid=None, steps: int | None = None,
                  guidance: float = 0.0):
        """导出/推理用：**确定性**去噪（DDIM 风格，不再注入随机噪声）。

        与 {@link sample} 的区别只有两点，且都是为了让 ONNX 图确定：
        1. 起点用外部传入的 ``z``（推理时固定为 0 均值），而不是 ``randn``；
        2. 每步取均值，**不注入随机噪声**（DDIM 的 eta=0）。

        训练时步数可少于推理（时间步是嵌入不是结构），这里步数与 steps 一致。
        """
        train_steps = self.steps
        steps = steps or train_steps
        b, n, k = node_feat.shape[0], node_feat.shape[1], self.slots
        x = z * mask.unsqueeze(-1)
        if forbid is not None:
            x = x * (1.0 - (forbid > 0.5).float())
        # 与 sample 同一套步数映射：允许 steps > 训练步数而不越界
        seq = [min(int(round(i * (train_steps - 1) / max(1, steps - 1))), train_steps - 1)
               for i in range(steps)][::-1]
        for i in seq:
            t = torch.full((b,), i, device=node_feat.device, dtype=torch.long)
            eps = self.predict_noise(x, node_feat, adj, mask, t, forbid)
            if guidance > 0 and forbid is not None:
                eps_uncond = self.predict_noise(x, node_feat, adj, mask, t, None)
                eps = eps_uncond + guidance * (eps - eps_uncond)
            a = self.alphas_cumprod[i]
            # DDIM (eta=0)：x_{t-1} = x_t / sqrt(a_t) - eps * sqrt(1-a_t)/sqrt(a_t)
            x = (x - (1 - a).sqrt() * eps) / a.sqrt()
            x = x * mask.unsqueeze(-1)
        logits = x
        if forbid is not None:
            logits = logits.masked_fill(forbid > 0.5, -1e9)
        return logits * mask.unsqueeze(-1)
