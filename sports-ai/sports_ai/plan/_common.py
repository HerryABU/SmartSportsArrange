"""plan 子包的共享常量层（2026-10-06 从 hybrid_search.py 拆出）。

只放「被多处引用、且不属于任何单一职责」的东西：状态特征维度契约与标准导入。
之所以要有这一层：状态特征向量的**下标顺序本身是契约**（谁改了顺序，
预测器与采集器就会静默对不上，而 shape 完全一致、不报错），
把它集中在唯一一处，比散落在各模块顶上更安全。
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np

STATE_FEAT_DIM = 8
S_FILL, S_SLACK, S_EXPO, S_BLOCK, S_DAYS, S_REMAIN, S_SPREAD, S_FEAS = range(8)
