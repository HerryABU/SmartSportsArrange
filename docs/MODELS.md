# 模型产物的独立分发

> 关联：`sports-ai/models/MANIFEST.json`（清单）｜`scripts/gen_models_manifest.py`（生成/校验）
> 状态：**onnx 已从 git 索引移除**（2026-10-03），仓库只保留清单

---

## 1. 为什么不入库

| | 入库 | 独立分发（当前做法） |
|---|---|---|
| 仓库体积 | 16 个 onnx ≈ **470 MB**（其中 `super_moe` 单个 344 MB），每次重训都变动 → 线性膨胀 | 0（只有一个小 JSON 清单） |
| 提交可读性 | 每次提交都是二进制巨块，无法 review | 清单可 diff，且能校验一致性 |
| clone 速度 | 显著变慢 | 不受影响 |

代码与产物分离：**代码进仓库，产物走分发**。

## 2. 清单（`sports-ai/models/MANIFEST.json`）

每个模型记录 **文件名 / 字节数 / sha256 / 用途**，并写明**缺失时会降级成什么**
（例如 `super_moe.onnx` 缺失 → L4 退化为 L3 优化链；`referee_gnn.onnx` 缺失 → 裁判走规则派遣）。

当前 16 个模型、合计约 **470 MB**（每次重训后以 `MANIFEST.json` 为准）：

| 类别 | 模型 |
|---|---|
| 超级编排 | `super_moe.onnx`（**19 类任务**合并模型，84.6M 参数，专家池含 4 个嵌套专项 MoE，8 个输出） |
| GNN | `constraint_gnn` / `tournament_gnn` / `conflict_gnn` / `referee_gnn` / `teacher_gnn` |
| 辅助 | `algorithm_selector`（v2）/ `ai`（v1，历史保留）/ `lane_advisor` |
| 微调专项 | `heat_stagger_advisor`（组次错开）/ `slot_split_advisor`（跨时段拆分） |
| 生成式 | `scheme_generator` / `scheme_discriminator` / `scheme_refiner` / `scheme_diffusion` |
| 未升级（**刻意**） | `forecast_direct` / `forecast_mimo`（趋势预测，不做编排决策，保持轻量原架构） |

## 2.1 架构：所有模型已升级为专项 MoE（2026-10-05）

除 `forecast_*` 外，**每个模型内部都是多架构混合专家**：

| 组件 | 说明 |
|---|---|
| 专家架构（9 种轮转） | 残差 MLP / 图卷积 GNN / 一维 CNN / Transformer 自注意力 / 交叉特征 / **状态空间 SSM** / **ROI 池化** / **指针网络** / **搜索式网络** |
| 门控 | 层次**两级**门控（先选专家组、再组内选专家）+ 共享专家隔离 |
| 融合 | **稠密融合**（每个专家都算、都拿梯度），不是稀疏 Top-K |
| 深度 | 主干 ≥6 层（`SpecialistMoE` 实际深度 8） |
| 附加输出 | 后续步骤预测（预测「下一步该做什么」而非「下一时刻是什么」） |

前沿依据：SSM 取自 Mamba-2/3 与 Mamba-MoE（线性 O(N) 长程，弥补注意力的 O(N²)）；
搜索式网络取自 NCO4CVRP（**推理策略收益 > 再训一轮**：Beam Search + 模拟退火式接受）；
指针网络解决「从候选集里选一个」的相对排序。

⚠️ **为什么是稠密融合而不是稀疏 Top-K**（本项目实测结论）：
标准稀疏 MoE 在「CPU + 几千步 + 小 batch」的预算下**必然专家塌缩**，
连续 5 轮修（动态偏置加强 / warmup / 轮转 / 保留梯度 / 辅助损失）全部失败——
根因是结构性的：「没被选中的专家拿不到梯度 → 学不动 → 更不被选」。
DeepSeek 那套动态偏置需要**万卡级训练量**才撑得住。
改稠密融合后主 MoE 的 19 个专家**全部有效激活**（最小使用率 1.6%、路由熵 0.96）。

### ⚠️ 训练预算：主 MoE 目前**远未收敛**（2026-10-05 实测）

主 MoE（84.6M 参数）在 CPU 上以 `--samples 700 --epochs 6` 训 6 轮耗时 **3 h 42 min**，
best 出现在最后一轮，val_loss 从 8.08 降到 **1.499**，且**看不出平台期**；
而脚本按「深度单位」给出的建议预算是 **135 轮 / patience 45** —— 当前约完成 **4%**。
脚本会主动打这条警告：

> ⚠️ 本次训练轮数明显低于该深度的建议预算（6 < 135）…极易被误读成「深层架构更差」

所以**不要**拿当前 val 横向对比浅层旧模型（那是「没训够」，不是「架构更差」）。
要真正收敛只有两条路：上 GPU，或把 `--samples/--epochs` 提上去并接受小时级训练。

### ⚠️ 选择器（实例级模型）的两个契约点

`algorithm_selector` / `ai` 是**实例级二分类**模型，与逐行打分的 GNN 走不同的适配路径：

| 点 | 要求 | 写错的后果 |
|---|---|---|
| 输入 | `[B,F]`（**2 维**，无节点轴），ONNX 输入名 `features` | `UpgradedMoE.forward` 曾硬解包 3 维 → 一训就崩（说明该路径从登记起就没通电） |
| 输出 | `[B,2]`（硬解 / 取消两条路径的 logits） | 漏写 `out_dim=2` → 导出 `[1,1]`；Java 读第二个值越界 → 异常被 catch → **静默回退规则** |
| loss | `ce`（多类交叉熵） | 用 `bce` 会把 `[B,2]` 拉平成 `[2B]` 再与 `[B]` 的标签比 → 形状不符，或语义全错 |

### 三种升级方式的适用边界

| 方式 | 适用 | 例子 |
|---|---|---|
| **外部适配器** `UpgradedMoE` | 单输入、单输出的逐行打分模型 | `lane_advisor` / `conflict_gnn` / `referee_gnn` / `teacher_gnn` / 两个选择器 |
| **就地换编码器** `MoEEncoder` | 结构各异但共用同一编码器的模型 | 生成式四模型（共用 `GnnEncoder`） |
| **就地增强表征** `MoERepr` | **多输出**模型（外部适配器回不了多个输出） | `tournament_gnn`（三个输出头） |

⚠️ 多输出模型**不能**用外部适配器：`TournamentAiService` 读 `r.get(0..2)` 三个输出，
适配器只回主输出 → 少两个 → 抛异常被 catch → **静默回退规则**。
症状极具迷惑性：onnx 单独加载推理完全正常，只是输出个数不对。

## 2.2 新模型上线前的契约核对清单

适配器保证的是**契约一致**，不保证**精度达标**——精度必须靠重训。
上线顺序：**训完 → 导出 → 跑测试 → 再部署**。

1. ONNX **输入名**与 Java `feed` 逐字一致
   （`lane_advisor` 是 `athlete_feat` **不是** `node_feat`）
2. ONNX **输出个数**一致（按 `r.get(k)` 读的调用方，少一个就静默回退）
3. ONNX **输出形状**一致
4. 文件名用**原名**（`x.onnx`，不是 `x.moe.onnx`）才能零改动替换
5. 动态轴覆盖真实 N 范围
6. 部署后**必须跑对应测试**：ONNX 单独加载 OK ≠ 契约 OK

## 3. 获取模型的三种方式

### 3.1 从分发件安装（推荐，生产/CI）

把分发包（Release / 网盘 / 内网文件服务）里的 `.onnx` 全部放到：

```
sports-ai/models/
```

然后**校验一致性**：

```bash
python scripts/gen_models_manifest.py --check
# 通过 → [check] 通过：13 个模型全部与清单一致
# 失败 → 逐个列出「缺失」或「sha256 不符」，可直接定位到损坏/版本错误的文件
```

> ⚠️ **必须校验**。sha256 不符意味着分发件与训练产物不是同一份 ——
> 而模型的加载失败在本项目里表现为**静默回退规则**（不报错），
> 不校验的话会带着错误权重跑很久才发现。

### 3.2 从源码重新训练导出

```bash
cd sports-ai
python -m sports_ai.train_super_moe --device cpu            # 训练（详见 docx/models.md）
python -m sports_ai.export_super_moe_onnx                   # 导出
python -m sports_ai.referee_advisor --mode both             # 裁判模型
python -m sports_ai.export_constraint_onnx
python -m sports_ai.export_tournament_onnx
...
python ../scripts/gen_models_manifest.py                    # 重训后必须重新生成清单
```

### 3.3 同步到后端资源目录（**构建前必做**）

后端从 `sports-backend/src/main/resources/models/` 打包进 jar：

```bash
# Git Bash
cp -f sports-ai/models/*.onnx sports-backend/src/main/resources/models/

# 或 PowerShell
Copy-Item sports-ai/models/*.onnx sports-backend/src/main/resources/models/ -Force
```

`build.ps1` 已包含这一步（构建时自动同步）。

## 4. 运行时也可以完全外置

不必把模型打进 jar —— `ModelSource` 支持外部目录：

```yaml
sports:
  schedule:
    ai:
      enabled: true
      model-dir: file:/opt/sports/models   # 或绝对路径
      super-model: super_moe.onnx
```

这样换模型不需要重新打包，只需替换目录里的 `.onnx`（`ModelSource` 会重新读取）。

## 5. 新增/更新模型的流程

1. 训练 → 导出 `.onnx` 到 `sports-ai/models/`
2. `python scripts/gen_models_manifest.py`（刷新清单）
3. `cp -f sports-ai/models/*.onnx sports-backend/src/main/resources/models/`
4. 提交**清单 + 代码**（**不要**提交 onnx —— 已被 `.gitignore` 的 `*.onnx` 排除）
5. 把新的 `.onnx` 上传到分发包

> ⚠️ 若误把 onnx 加进了索引（`.gitignore` 对**已跟踪**文件无效），用
> `git ls-files '*.onnx' | git update-index --force-remove --stdin` 移除 ——
> **只动索引、不删工作区文件**。
> 不要用 `git rm`：本仓库的 safe-delete 守卫会把它扩展成整目录的真实删除（有过惨痛教训）。
