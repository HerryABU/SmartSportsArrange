"""对抗式生成（货真价实的 GAN）：为运动会编排生成「时间槽着色方案」。

对应架构文档第八节「对抗式网络与自迭代升级」：
- 生成器 G：噪声 + 实例条件（冲突图）→ 节点着色方案（软分配）；
- 判别器 D：**神经网络**判定方案「像不像真实可行解」（并非规则校验器，保证是真 GAN 的
  minimax 对抗，而不是退化成「生成器 + 规则校验」）；
- 组合损失（GenCO 式）：把兼项冲突矩阵 / 容量 / 禁止列表（行政时间保护）编码为损失，
  与 GAN 生成损失联合训练；
- 真样本来自 ``oracle``（贪心图着色，即真实可行解），假样本来自 G。
"""

from .encoder import GnnEncoder
from .scheme import scheme_conflicts, gumbel_scheme, build_forbid_mask
from .generator import SchemeGenerator
from .discriminator import SchemeDiscriminator
from .refiner import SchemeRefiner
from .refine import AdversarialRefiner

__all__ = [
    "GnnEncoder",
    "scheme_conflicts",
    "gumbel_scheme",
    "build_forbid_mask",
    "SchemeGenerator",
    "SchemeDiscriminator",
    "SchemeRefiner",
    "AdversarialRefiner",
]
