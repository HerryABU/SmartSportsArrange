# sports-ai — 运动会编排系统的 AI 训练侧

本目录是编排算法 AI 核心的**训练侧**（Python 3.12）。生产部署**不依赖**本目录：
训练完成后导出 `.onnx`，运行时由 Java 端 `com.sports.schedule.ai` 通过 onnxruntime 加载推理，
生产环境无需 Python 解释器。

---

## 现状（2026-10-06 校正）

**16 个在役模型全部是「专项 MoE」**（无豁免项）：9 种专家架构轮转（残差 MLP / 图卷积 / CNN /
Transformer / 交叉 / SSM / ROI 池化 / 指针 / 搜索式）+ 两级门控 + **稠密融合** + 主干 8 层 +
`next_step` 附加输出。**稠密而非稀疏 Top-K**：稀疏在「CPU + 几千步 + 小 batch」下必然专家塌缩。

**主 MoE 是「其他 MoE 的混合体」**：`nn/ensemble.py` 把 15 个专项模型当「能力专家」接进主 MoE 的专家池
（`extra_experts`），主体参数 95%+ 原样继承，只需为新专家训两片投影 ⇒ 扩容成本仅 **+11%**。
当前 **34 专家**（19 主干 + 15 专项）/ **122.19M 参数** / 4528 专家层 / 14 门控，导出 `super_moe.onnx` 475 MB。

| 指标 | 34 专家主 MoE | 旧 19 专家 |
|---|---|---|
| val_loss | **1.35064** | 1.4994 |
| pri_mse | **0.00403** | 0.0547 |
| slot_mse | **0.82275** | 0.8964 |
| 最低专家使用率 | 0.01188 | 0.0095 |
| 路由熵（无塌缩判据） | **0.99015** | 0.9866 |

> 本节取代下文「五大 AI 能力」的旧口径；逐模型的契约与升级边界见
> [`docs/MODELS.md`](../docs/MODELS.md)，模型清单见 [`models/MANIFEST.json`](models/MANIFEST.json)。

---

## 一、训练方法

### 1.1 数据从哪来：合成场景 + 贪心标签

训练不依赖真实历史数据 —— 用 `data/generator.py` 的**场景生成器**按五档难度造报名数据
（REGULAR / BLOCK / LANE / TEAM / HELL），标签由**确定性贪心启发式**产出。
这样做的两个理由：① 真实数据里兼项冲突的「冲突簇」结构太稀疏，直接学不到；
② 贪心标签可复现、可无限采样，且与线上推理同源（不会有训练/推理口径漂移）。

关键开关 —— 要造出「排不下」的场景必须给 `extra_days`：

```bash
python -m sports_ai.train_super_moe --samples 700 --extra-days 2   # 否则「未排」分量恒为 0，学不到
```

### 1.2 主 MoE：从 19 专家长成 34 专家

```
第 1 步  各专项模型独立训练 / 升级为 MoE
         python -m sports_ai.nn.upgrade_train --model lane_advisor --verify
         （登记表是唯一来源；漏登记 = 永远不升级且文档过度声称）

第 2 步  把小模型当「能力专家」接进主 MoE 的专家池
         python -m sports_ai.train_super_moe --ensemble --resume --epochs 6

第 3 步  导出 + 部署 + 刷清单（三步必须一起做）
         scripts/finalize_models.py --only super_moe
```

**升级方式三选一**（选错会静默接不上）：

| 方式 | 适用 | 判据（按 key 前缀，先剥外层前缀） |
|---|---|---|
| `UpgradedMoE` 外部适配器 | 单入单出的普通模型 | 有 `predictor.*` |
| `MoEEncoder` 就地换编码器 | 生成式四模型 | 无 predictor 有 `legacy.*` |
| `MoERepr` 就地增强表征 | **多输出**模型（适配器回不了多输出） | 两者皆无 |

> ⚠️ 判据一旦退化成二分类，`MoERepr` 会落到 encoder 分支报「缺少 in_proj」，
> 而 `build_ensemble` 只打印一行 `[skip]` ⇒ 该模型**静默缺席专家池**，日志上却像全部接入成功。

### 1.3 续训与预算守卫

- `--resume` 从 `models/super_moe.pt` 继续；`hidden` / `steps` 必须一致。
- 训练器会打印**预算守卫**：轮数明显低于该深度的建议值时告警（深层网络未加预算时 val 未收敛，
  极易被误读成「深层架构更差」——这个坑踩过）。
- 自动续训到达标：`scripts/train_super_moe_until.sh`（目标 `val < 阈值`，一段接一段跑到达标）。
- 刷新最优时会自动备份旧权重为 `super_moe.pt.bak.*`（**这些备份会迅速堆到几个 GB**，
  体检与清理见 `docs/MODELS.md`）。

### 1.4 训练侧的三条硬纪律

1. **改训练脚本后先小步试跑（4 步）**：变量名写错要跑到那一行才炸，正式训练几分钟才推进到那里。
2. **动态挂子模块必须在优化器创建之前**：`Adam(X.parameters())` 构造时即固化参数列表，
   之后挂的 `X.aux` **永远不更新**（症状＝loss 里算得到、梯度也回传主干、唯独自己的头一动不动）。
3. **加辅助任务用真序列分支**（`nn/forecast_aux.py` + `UpgradedMoE.forward_seq`），
   不要用软标签蒸馏：主任务特征里没有时间步语义，拿它预测未来等于让模型猜，只会训出常数输出。

---

## 二、推理链路：一次编排请求是怎么走到模型的

### 2.1 三档梯度（`POST /api/schedule/auto` 的 `mode`）

| 档 | 走什么 | 量级 | 是否用模型 |
|---|---|---|---|
| `rule` | 确定性 first-fit（蛇形分组 + 固定分道 + 时间栅格） | 毫秒 | 否 |
| `optimize` | Timefold 组合求解 + GA/LNS/MNSA/ALNS/Fix-and-Optimize 精修链 | 秒 | 否 |
| `ai` | 优化链 + ONNX 本地推理（默认 3 轮推理时自对抗） | **分钟** | 是 |

三档共用同一个下游分道（`assignLanes`）与同一套自检 / 下界评估 / 协作广播。

### 2.2 ai 档内模型的介入点（按调用顺序）

```
① 求解前的「初始顺序」—— 主 MoE
   SuperScheduleEncoder.encode(units, windows)          实例 → 定长张量
        ↓ PrimaryMoeBridge.advise(encoded)              （静态桥，见 §3.2）
   SuperMoeService 输出 Advice{ priority[], nextStepName }
        ↓ 按 priority 降序稳定排序 optUnits             （只改顺序，不改解）
   → portfolioInfo.primaryMoe = {applied, units, style}

② 求解器出解后再叠加精修（GA → LNS → MNSA → ALNS → Fix-and-Optimize）

③ 求解期自对抗（仅 ai 档）
   G 采样候选 → Refiner 残差精修 → D 打分 → 服务自算候选真实残余冲突
        ↓ 综合分 = D分 − λ·冲突（λ = ADVERSARIAL_LAMBDA = 0.5），多轮择优
   → portfolioInfo.aiReport = {roundsRun, dScore, conflict, conflictBefore, refined}

④ 道次编排（AI 派遣款型 laneStyle=ai）
   LaneAdvisorService 输出运动员派遣优先级 → AssignLanes 按它分道

⑤ 三个专项微调模型（正交维度，非「挪时间」）
   SlotSplitAdvisor  跨时段拆分排序「先拆谁」
   HeatStaggerAdvisor 组次顺序错开（项目时间窗一分不动，只换 heat）
   Referee/Teacher GNN 裁判与班主任派遣
```

### 2.3 输出怎么用：**模型只影响「顺序/优先级」，不直接改解**

这条是刻意的设计约束：模型给出的 priority 只用来重排**求解器的初始顺序**（`primaryMoe.applied`），
最终解仍由求解器 + 精修链产出。好处是**模型坏了不会产出非法赛程**，最差只是不发挥作用。
同理自对抗候选只做评估与上报，不覆盖主方案（二者粒度不同：槽着色 vs 并发位时段）。

### 2.4 失败即回退（绝不阻塞主链路）

每一个 AI 服务都是「锦上添花」：模型缺失 / 加载失败 / 推理异常一律 → 记 WARN + 回退规则，
编排照常出结果。因此**「没报错」不等于「跑到了模型」** —— 必须靠 §3.3 的观测手段确认。

---

## 三、模型文件怎么加载、怎么流转

### 3.1 全流程（训练侧 → 生产）

```
① 训练            sports-ai/models/<name>.pt            （升级后的权重叫 <name>.moe.pt）
                                                          ⚠️ .moe.pt 优先于 .pt，且不看新旧
② 导出            <name>.onnx                            需把 extra_experts / forecast_steps 一起传，
                                                          否则「训了导不出」（expert_embed 形状不匹配）
③ 刷清单          models/MANIFEST.json                   白名单制：USAGE 里登记过的才进清单
                                                          逐条记 bytes + sha256 + 用途
④ 部署            sports-backend/src/main/resources/models/*.onnx
                                                          scripts/finalize_models.py（必须用 venv 的 python）
⑤ 打包            BOOT-INF/classes/models/*.onnx          mvn package 一并打进 jar
⑥ 运行            ModelSource.read(modelDir, name)        onnxruntime 会话惰性加载（首次用到才读）
```

命令一条龙（**必须用 `sports-ai/venv/Scripts/python.exe`**，默认解释器没有 torch/onnx，
「导出」与「刷清单」会**静默失败且照样打印 ✅**）：

```bash
./sports-ai/venv/Scripts/python.exe scripts/finalize_models.py --only super_moe
./sports-ai/venv/Scripts/python.exe scripts/gen_models_manifest.py --check      # 16 个模型与清单一致
./sports-ai/venv/Scripts/python.exe scripts/check_models_freshness.py           # 时间戳 + 架构指纹 + 遮蔽判据
```

### 3.2 运行时的模型目录（可完全外置）

只有**一个**配置键，10 个 AI 服务全部共用：

```yaml
sports:
  schedule:
    ai:
      model-dir: classpath:/models      # 默认：读 jar 内的 /models
      # model-dir: ./models            # 或 jar 旁边的目录（普通路径 / file: 前缀都支持）
      # model-dir: file:/opt/ai-models # 热替换模型，无需重新打包
```

`ModelSource` 的三种形态：`classpath:/models`（jar 内）、普通磁盘路径、`file:` 前缀。
这也是发布包两个版本的区别 —— **嵌入式版**用默认值（模型在 jar 内，单文件交付）；
**外置模型版**由启动程序传 `--sports.schedule.ai.model-dir=<jar 旁的 models 目录>`，
换模型只要覆盖 `models/*.onnx` 再重启。

### 3.3 怎么确认「模型真的被用上了」

| 手段 | 看什么 |
|---|---|
| `GET /api/ai/status` | `modelSource`（实际生效的模型目录）、`models.*.modelInfo`（逐模型来源与文件）、`tiers`（分层调用计数 + **`wiredButNeverCalled`**） |
| 启动日志 | 模型加载/回退的 WARN（「不可用，回退规则编排」） |
| 编排响应的 `algorithmPortfolio` | `primaryMoe.applied`、`aiReport.roundsRun`、精修链各环回执 |
| 前端「算法详情」 | 上面这些字段的可视化回显（含「精修链未启用」这类**否定态**） |

> ⭐ 通用纪律：**「模型可用但一次没被调用」是接线断了的唯一可观测信号** ——
> 它不报错、只是功能一直没生效。所以 `tiers` 里带 `calls` 与 `wiredButNeverCalled`，
> 前端与 `/api/ai/status` 都要把它显示出来。
>
> ⚠️ 历史上在这里栽过两次，都是**同一类**静默失效：
> ① 调参在构造期被读成 0（`@Value` 是字段注入，晚于构造器）⇒ 整条精修链从未运行；
> ② 一次冒烟留下的 `.moe.pt` **永久遮蔽**真实权重（`ckpt_path` 只按优先级、不看新旧）。
> 两者都「日志正常、接口正常」。详见仓库根 `MEMORY.md` 的「静默失效通则」。

### 3.4 契约（改了就重训全部模型）

- 实例特征 **16 维**：Python `data/features.py` ↔ Java `InstanceFeatures`，逐位对齐；
- 冲突图 **16 维节点 + 带权二值邻接**，`MAX_NODES = 1024`，边去重口径一致；
- 归一化**只固化在 ONNX 内部**（Python 侧不做，Java 侧也不做）；
- 排序模型（`algorithm_selector` / 两个 advisor）的标签方向必须显式验证：
  **标签方向错了会低于随机基线且不报错**；
- 近零方差特征不做标准化（`std < 1e-3 → 1.0`），否则噪声被放大。

---

## 目录结构

```
sports-ai/
├── requirements.txt          # 训练依赖（torch CPU / onnx / onnxruntime / onnxscript / networkx）
├── sports_ai/
│   ├── data/                 # 合成数据 + 特征契约
│   │   ├── generator.py      # 合成报名数据生成器（镜像真实「冲突簇」结构）
│   │   ├── features.py       # 16 维实例特征契约（兼项共现统计层）
│   │   └── gnn_io.py         # 冲突图 → GNN 输入编码（固定 shape 契约）
│   ├── models/               # 经典监督模型
│   │   ├── graph.py          # 共享图卷积底座（带权邻接 + 对称归一化 + 残差块）
│   │   ├── selector.py       # 算法选择器（残差 MLP，硬解 vs 取消路径）
│   │   └── gnn.py            # 冲突簇 GNN（4 层带权消息传递 + 跳跃连接）
│   ├── generative/           # ★ 货真价实的 GAN（对抗式网络）
│   │   ├── encoder.py        #   共享 GNN 编码器
│   │   ├── scheme.py         #   方案表示 + 组合约束损失（GenCO 式）
│   │   ├── oracle.py         #   真样本：贪心图着色（可行硬解）
│   │   ├── generator.py      #   生成器 G（噪声 + 冲突图 → 时间槽方案）
│   │   ├── discriminator.py  #   判别器 D（神经网络，非规则校验器）
│   │   ├── train_gan.py      #   minimax 对抗训练
│   │   └── export_gan.py     #   导出生成器/判别器 ONNX
│   ├── forecast/             # ★ 多步预测（Direct / Recursive / MIMO）
│   ├── curriculum/           # ★ 自步学习（课程训练 + 自改进）与难度测量器
│   ├── tournament/           # ★ 球赛赛制生成（循环/淘汰/混合/种子/排球 + 编排任务适配）
│   │   └── adapt.py          #   赛制结构 → 可排任务（含偏序与队员名单）
│   ├── solve/                # ★ 经典求解层（与神经生成互补）
│   │   ├── heats.py          #   拆批：单元 → 组次任务（含预赛→决赛偏序）
│   │   ├── feasibility.py    #   可解性下界（容量缺口 / 最少天数 / 团 / 超大单元）
│   │   ├── scheduler.py      #   分批装箱 + 局部搜索（容量/池/兼项/偏序四约束）
│   │   └── report.py         #   不可解冲突的结构化输出（给程序消费的稳定 schema）
│   ├── train_selector.py     # 训练入口
│   ├── train_gnn.py
│   ├── lane_advisor.py       # ★ 道次编排 AI（运动员派遣顺序，Learning-to-Rank）
│   ├── export_onnx.py        # 统一导出 ONNX + onnxruntime 自检
│   └── onnx_utils.py         # 权重内联（单文件 .onnx）
├── models/                   # 产出：*.pt（中间）与 *.onnx（部署）
├── scripts/                  # setup_venv / train / export
└── tests/                    # 特征契约 / GAN / 赛制生成自检
```

## 快速开始

```powershell
# 1. 建虚拟环境（Python 3.12）+ 装依赖
.\scripts\setup_venv.ps1

# 2. 训练全部模型 + 导出 ONNX + 自检
.\scripts\train.ps1
```

产出 `models/` 下的 `.onnx`：`algorithm_selector` / `conflict_gnn` / `scheme_generator` /
`scheme_discriminator` / `scheme_refiner` / `forecast_mimo` / `forecast_direct`。

> **模型随 jar 交付（无需外部目录）**：根目录的 `build.ps1` 在打包前会把 `models/*.onnx`
> 同步到 `sports-backend/src/main/resources/models/`，运行时由 `ModelSource` 从
> **classpath 直读**（onnxruntime 接受 `byte[]`，不必解压到临时文件），因此部署只需一个 jar。
> 仍保留热替换能力：把 `sports.schedule.ai.model-dir` 指向磁盘目录（如 `./ai-models` 或
> `file:/opt/ai-models`）即可换模型而不重新打包。模型来源与逐模型加载状态见 `GET /api/ai/status`。

## 五大 AI 能力

| 能力 | 模块 | 说明 |
|:--|:--|:--|
| 算法选择 | `models/selector.py` | 16 维特征 → 硬解 / 取消路径；标签为**真实可解性**（含团下界）|
| GNN 辅助启发式 | `models/gnn.py` | 冲突图 → 节点着色优先级，中心簇先着色（4 层带权 + 跳跃连接）|
| **GAN 对抗生成** | `generative/` | **生成器 G + 判别器 D 神经网络 minimax 对抗**，组合约束损失（5.3）|
| 多步预测 | `forecast/` | Direct / Recursive / MIMO 三策略，让回溯提前发生（5.2）|
| 自步学习 | `curriculum/` | 难度测量器 + 从易到难课程 + 成功经验回流（5.4）|
| 球赛赛制生成 | `tournament/` | 圆桌轮转（主客平衡）/ 淘汰（同单位回避）/ 混合 / 种子 / 排球 + 编排适配 |
| **道次编排 AI** | `lane_advisor.py` | 运动员派遣顺序（班级/性别/兼项数 → 优先级），供分组分道消费 |

## 规模与模型强度

### 规模：支持「很大」

| 维度 | 早期 | 现在 |
|:--|:--|:--|
| 图节点数 | 固定 256 | **动态 `n`**（ONNX `dynamic_axes`），安全上限 1024 单元 |
| 单实例覆盖 | 约 25 项目 × 10 年级 | 约 100 项目 × 6 年级 × 2 轮次 ≈ 1200 单元 |
| 训练代价 | 补齐到 256，绝大部分算力花在 padding | 真实 `n`（典型几十），**快一个数量级** |
| 超限行为 | 静默截断 | 如实报告 `dropped`（不再无声降级）|

> 关键认识：GNN 是**归纳式**的（权重与节点数无关），固定 shape 从一开始就没必要。
> 把它当作「必须定长」来设计，换来的是训练慢 + 硬上限两个纯损失。

### 强度：不再是「简简单单」

- **节点特征 8 → 16 维**：补进「预赛/决赛、年级、时长占比、冲突暴露量、超大单元、序列位置」；
- **邻接二值 → 带权**：边权 = 共享运动员数归一化，冲突强度可感知；
- **GNN 2 层 → 4 层**：LayerNorm + 残差 + **跳跃连接**（JK），浅层局部与深层全局信号并用；
- **共享图卷积底座** `models/graph.py`：GNN 与 GAN 编码器复用同一实现，避免两条路径悄悄分叉；
- **选择器 MLP → 残差 MLP**（hidden 96 × 3 块）：判据依赖 16 维特征的**非线性交叉**
  （如「团下界 × 可用时段数」），窄而浅的网络在这类交叉判据上拟合不足；
- **不引入 PyG / DGL**：它们带原生扩展与自定义算子，导不出 ONNX。本项目契约是
  「训练导出 ONNX → Java onnxruntime 推理」，因此只用纯张量算子手写消息传递 ——
  朴素，但**可导出、可复刻、可核对**。

## 球赛赛制：三种赛制与真实约束

| 赛制 | 实现的约束 |
|:--|:--|
| **循环赛** | 圆桌轮转（每队每轮恰一场、每对恰交手一次）+ **主客场平衡且连续段受控**（贪心打断连主/连客）+ 强强对话分散度报告 |
| **淘汰赛** | 轮空给高种子 + 1/2 号种子分居半区 + **同单位回避**（同班/同年级不首轮相遇，局部交换爬山）+ 双淘汰**真实轮次结构**（每场标注 feedsFrom） |
| **混合赛制** | 蛇形分组（强队分散）+ 交叉对阵（**同组出线队首轮必不相遇**）+ 可切双淘汰 |

**接入编排**（`tournament/adapt.py`）：赛制结构 → 可排任务，保留**先后依赖**
（小组赛全部早于淘汰赛）与**队员名单**。队员名单是关键——它让球赛任务与田径任务
共用同一张冲突图，「同一名学生既打球又跑 100 米」的**跨大类兼项冲突**自动成立。

## 道次编排 AI（`lane_advisor.py`）

径赛一个项目 N 人、分 H 组、每组 L 条道，要同时满足**硬约束**（同组不能同班）
与**软目标**（各组实力均衡、同班在时间上分散）。

- **为什么输出「排序」而不是「分组」**：组数 H 随报名人数变化，输出分组意味着输出维度随 H 变，
  契约无法固定；输出**每人一个标量优先级**则与 N、H 都无关，Java 只要 `argsort`
  再走既有蛇形管线即可 —— **与现有管线零阻抗**（只换「谁先派」，不换「怎么分」）。
- **标签怎么来**：经典贪心构造「理想顺序」（按班级分桶后轮询交错，同班在序列中等距；
  桶内按实力交替），模型学的是这个构造规则。
- **Java 接入**：新增款型 `ai`（`ArrangeStyle.AI`）。模型不可用/推理失败时**自动回退**
  种子蛇形顺序，接口行为不变；`GET /api/arrange/styles` 自动列出该款型，**无需改前端**。

## 货真价实的 GAN（`generative/`）

这不是「生成器 + 规则校验」的降级版，而是真正的对抗训练：

- **生成器 G**（`generator.py`）：GNN 编码冲突图 + 每节点噪声 → 时间槽 logits，
  经 **straight-through Gumbel-Softmax** 输出硬方案；
- **判别器 D**（`discriminator.py`）：**可学习神经网络**，把方案拼进节点特征、图池化后判真/假；
- **真样本**（`oracle.py`）：贪心图着色求出的**可行硬解**；
- **假样本**：G 的输出（与真样本同为 one-hot 形态，D 只能靠「约束是否真满足」区分）；
- **minimax 训练**（`train_gan.py`）：D 最大化区分真假，G 最大化骗过 D **+** 最小化组合约束损失；
- **组合约束损失**（`scheme.py`，GenCO 式）：兼项冲突期望 / 行政时间保护禁止列表 / 槽容量。

调平手段：判别器学习率 `--d-lr-scale`（默认 0.4）、标签平滑 `--label-smooth`（0.9）、
判别器 dropout（0.2），避免 D 过快碾压 G 导致对抗梯度消失。

训练实况（参考）：判别器准确率在 0.78–1.0 间动态波动，生成方案残余冲突从 ~0.20 降到 ~0.08。

## 推理时的自对抗（生成推导中也对抗）

训练时的对抗只决定「模型长什么样」；**推理时的自对抗**决定「这一次生成的方案怎么逼出来」。
本仓库两层都做了：

1. **测试时对抗精修**（`refine.py` 的 `AdversarialRefiner`）：**冻结 G/D 参数**，在推理时对潜在
   噪声 `z` 做梯度上升——损失 = `-D(方案)`（骗过判别器）+ λ·组合约束（冲突/禁止/容量），
   迭代后硬化输出。实况：**残余冲突 0.199 → 0.129（↓35.3%）**，判别器分同步上升（更像真实解）。
2. **对抗精修器蒸馏**（`refiner.py` / `train_refiner.py`）：把上面这次迭代博弈**蒸馏成一次前向**
   （Java 不能反传），导出 `scheme_refiner.onnx`。实况：**残余冲突 0.087 → 0.021（↓76.3%）**。
3. **Java 端推理时博弈**（`com.sports.schedule.ai.AdversarialSchemeService`）：多轮
   「G 采样 → 精修器精修 → D 评判 → 计算真实冲突 → 择优」，即 G 生成、D 批评，在推理时交替。
   结果报告「单次生成基线冲突 → 自对抗后冲突」，保证自对抗不劣于基线。

```
训练时对抗：  G ←──── D          （决定模型参数）
推理时对抗：  G 生成 → 精修器 → D 批评 → 择优  （决定这一次的方案，多轮博弈）
```

## ONNX 契约（与 Java 端严格对齐）

### algorithm_selector.onnx — 算法选择

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `features` | float32 | `[1, 16]` |
| 输出 | `strategy` | float32 | `[1, 2]` |

输入是**原始** 16 维实例特征（未归一化），归一化作为模型第一层固化。

> **标签 = 该实例是否可解**（`solve.feasibility.analyze_bounds` 的 `capacityFeasible ∧ cliqueFeasible`），
> 而不是「tension ≥ 1」。原因是**容量够也可能排不下**——团下界（两两互相冲突、必须错开到不同时段的
> 单元数）超过可用时段数时，加场地也没用。只用 tension 阈值会让选择器在
> 「容量够但团超时段」的实例上误判为「硬解」（在地狱场景 2 天情形上实测踩到，见下）。

16 维顺序（`features.py` 的 `FEATURE_NAMES` =  Java `InstanceFeatures.FEATURE_NAMES`）：

```
0 unit_count  1 demand_minutes  2 supply_minutes  3 tension_ratio
4 multi_event_athlete_ratio  5 athlete_count  6 conflict_edges
7 conflict_density  8 conflict_components  9 max_degree  10 avg_degree
11 pool_count  12 day_count  13 avg_duration  14 duration_cv  15 group_count
```

### conflict_gnn.onnx — 冲突簇着色优先级

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `node_feat` | float32 | `[1, n, 16]` |
| 输入 | `adj` | float32 | `[1, n, n]`（**带权**：共享运动员数归一化，无自环）|
| 输入 | `mask` | float32 | `[1, n]`（1=真实节点）|
| 输出 | `priority` | float32 | `[1, n]` |

> **`n` = 单元数是动态轴**，不是固定 1024。GNN 是归纳式的（权重与节点数无关），
> 同一个模型从 1 个单元到上千个单元都能处理。早期版本固定 `[1,256,8]` 有两个致命后果：
> 训练时 99% 算力花在 padding 上、且给大型赛会留了硬上限。现在 ONNX 用 `dynamic_axes`
> 声明 `n` 可变，Java 按实际节点数构造 shape，`MAX_NODES=1024` 退化为**安全上限**。

16 维节点特征（`gnn_io.py` = Java `ConflictGraphEncoder`，逐位对齐）：

```
0 track            1 athlete_count_norm  2 duration_norm     3 has_group
4 group_size_norm  5 pool_idx_norm       6 event_idx_norm     7 log_athlete_norm
8 is_final         9 grade_idx_norm     10 duration_share    11 conflict_exposure
12 event_freq_norm 13 pool_share_norm   14 is_large_unit     15 order_norm
```

**8 → 16 维**不是堆料：8 维版本里模型看不到「预赛/决赛」「哪个年级」「这个单元占全局多少时间」，
而这些恰是决定「谁该先着色」的关键。**邻接带权**则是因为一条冲突边的真实强度是
「两单元共享多少名运动员」——共享 20 人与共享 1 人该被区别对待。

### scheme_generator.onnx — GAN 生成器

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `node_feat` / `adj` / `mask` | float32 | `[1,n,16]` / `[1,n,n]` / `[1,n]` |
| 输入 | `z` | float32 | `[1, n, 8]`（噪声；推理取 0）|
| 输入 | `forbid` | float32 | `[1, n, 16]`（禁止列表 = 行政时间保护）|
| 输出 | `logits` / `scheme` | float32 | `[1,n,16]` / `[1,n,16]`（one-hot）|

（`n` 同为动态轴；槽数 `MAX_SLOTS=16` 固定。）

### scheme_discriminator.onnx / scheme_refiner.onnx — 判别器 / 精修器

| 模型 | 输入 | 输出 |
|:--|:--|:--|
| `scheme_discriminator` | `node_feat[1,256,8]` / `adj[1,256,256]` / `mask[1,256]` / `scheme[1,256,16]` | `logits[1,1]`（真/假）|
| `scheme_refiner` | `node_feat` / `adj` / `mask` / `init_logits[1,256,16]` / `forbid[1,256,16]` | `logits[1,256,16]`（精修方案）|

### forecast_mimo.onnx / forecast_direct.onnx — 多步预测

| 方向 | 名称 | 类型 | shape |
|:--|:--|:--|:--|
| 输入 | `x` | float32 | `[1, 12, 4]` |
| 输出 | `y` | float32 | `[1, 8]` |

## 求解层：拆批 + 尽量可解 + 不可解冲突输出（`solve/`）

AI 负责「建议与候选」，**经典求解负责「在真实资源下尽量排下，排不下就说清楚」**。
真实规模下「排不下」有三种完全不同的成因，必须分开处理：

| 成因 | 判据 | 处置 |
|:--|:--|:--|
| **容量缺口** | 某池需求 > 供给 | 加天数 / 加并发位 / 减项目（与算法无关）|
| **超大单元** | 单元全部时长 > 单时段容量 | **必须拆批**，否则放不进任何位置 |
| **团下界** | 冲突图最大团 > 可用时段数 | 结构性不可解：加场地无效，只能拆组次或取消报名 |

### ① 拆批（`heats.py`）

单元的 `rawDuration` 是**全部时长 = 组数 × 每批时长**，真实规模下可达数百分钟
（720 人的立定跳远决赛 = 9 组 × 60 = 540 分钟），**远超单个时段容量**（4 小时 = 240 分钟）——
这正是「地狱场景不可解」的真正根因，而不是单纯的容量紧张。

拆批把单元展成组次任务，运动员按名单顺序**不重不漏**地均分到各批。副产品很重要：
同一单元的不同批次之间**不存在**兼项冲突，于是把一个单元摊到多个时段反而**降低**了冲突面。

### ② 下界分析（`feasibility.py`）

先算清楚「数学上够不够」：按池的容量缺口与最少天数、超过单时段容量的超大单元清单、
以及冲突图最大团（= 必须错开到不同时段的最少时段数）。

> ⚠️ 天数反推只取 `day=1` 的时段容量：`window_idx` 是跨天唯一的，直接聚合会把多天容量累加进来，
> 曾因此把 3 天场景算成「最少 1 天」。

### ③ 尽量可解（`scheduler.py`）

分批装箱 + 局部搜索，四类约束：**容量**（同并发位时长之和 ≤ 时段容量）、**池匹配**
（径赛/田赛各归其位）、**兼项**（同一时段同一运动员至多一次，跨池亦然）、
**偏序**（同项目同年级的预赛整体早于决赛）。

两个起点取代价更优者：**正排**（先预赛后决赛，选最早可行位）与**倒排**
（先让决赛占尾段——它受偏序钳制、时长小、必须放下——预赛再往前铺），后者专治
「预赛把尾部时段塞满、决赛无处容身」的碎片化失败。

代价是**字典序元组**（未排组次数 → 未排人次 → 兼项重叠重复度）：先不惜代价压「排不下」，
再在同等规模下压兼项重叠。用元组而非加权和，是为了避免权重标定失当导致
「多排 1 组但引入一堆冲突」这种劣解被选中。

### ④ 不可解冲突输出（`report.py`）

报告 schema `sports-ai/infeasibility-report@1`，字段：`feasible` / `scene` / `summary` /
`bounds` / `unplacedTasks` / `conflicts` / `actions`。

- `conflicts` 分类：`capacity_shortfall`、`oversized_unit`、`clique_exceeds_periods`、
  `athlete_clash`（按运动员聚合，给班主任的可执行清单）；
- `actions` 可执行：`extend_days`（还差几天）、`add_lanes`（还差几条赛道）、
  `cancel_entry`（建议取消某人的某项报名，交由班主任确认）。

Java 侧 `com.sports.schedule.analysis.ScheduleFeasibilityService` 产出**同构**报告，
保证「离线压测发现的问题」与「生产现场发现的问题」可以放在同一张表上比对。

## 地狱级规模测试（`hell.py` / `hell_test.py`）


用真实规模压测模型：**3 年级 × 8 班 × 30 人 = 720 名运动员**、9 个真实田径项目、
含预赛/决赛（复赛）与真实时长模型。

- `data/hell.py`：真实项目表（50m/100m/女子800m/男子1000m/4×100/立定跳远/跳高/引体向上/铅球），
  每项含**每批时长**、**每批容量**、**成绩排序方向**（径赛用时小→大 `asc`；田赛远度/高度/个数
  大→小 `desc`，用于取前 N 名进决赛）、**是否有复赛**、田赛同组；
  报名按专长（大部分 1 项、少部分 2 项、**每班至少 1 人 3 项**）；单元 = 项目 × 年级 × 轮次。
- `hell_test.py`：**三种**时间情形分别压测，并对每种情形跑经典求解与不可解冲突报告：

```powershell
python -m sports_ai.hell_test
```

| 情形 | 资源 | 结果 |
|:--|:--|:--|
| A 不限时间 | 按需求反推 **3 天**（每天 8h、径赛 2 道流、田赛 4 场地） | 142/142 组次 **100%** 排下，零兼项重叠 |
| B 限定 3 天（真实上限） | 同上 | 142/142 组次 **100%** 排下，零兼项重叠 |
| C 限定 2 天（紧张） | 供给砍掉 1/3 | **137/142（96.5%）**，未排 5 组次（100m@高2 预赛）；径赛容量缺 55 分钟；团下界 5 > 4 时段 → 结构性不可解 |

> 「体量最多 2-3 天」时，**3 天完全可解**；2 天属硬约束不足——求解器仍**尽量**排到 96.5%，
> 并把排不下的部分连同原因、缺口分钟数、可执行建议（加天 / 加赛道 / 取消某人某项报名）
> 一起输出成 `reports/infeasibility-2d.json`（`feasible=false`）。

暴露并修复的真实缺陷（记录在案）：
1. **归一化爆点**：`group_count` 在训练里近乎恒定 → std≈0 → 真实场景被除以 ~0 放大成 `z=1e6`，
   选择器直接误判。修复：标准化对近零方差特征**不缩放**（`std<1e-3 → 1.0`）。
2. **特征多样性不足**：生成器始终产出固定项目组合 → `group_count/duration_cv` 无分布多样性。
   修复：加 `event_drop_prob` 随机裁剪项目子集。
3. **单元时长模型错误**：真实「全部时长 = 组数 × 每批时长」（人数多时可达数百分钟），
   而训练曾用固定小值。修复：按真实模型生成（与 Java 端语义一致）。
4. **推理时自对抗择优准则**：原按「D 分 − λ·冲突」择优可能选到冲突更高的候选（0.43→0.49 变差）。
   修复：**残余冲突优先**，且把单次生成基线也纳入候选 —— 保证自对抗绝不劣于单次生成。
5. **大单元放不进任何位置**：单元 `rawDuration` 可达 540 分钟，远超单时段容量 240 分钟，
   旧模型下这些单元在数据结构上就无解（问题不在算法，在「没拆批」）。修复：组次级拆批。
6. **最少天数被多天容量累加稀释**：时段键跨天唯一，直接聚合会把多天容量加总 → 3 天场景算出「最少 1 天」。
   修复：天数反推只取 `day=1` 的时段容量。
7. **择优权重失衡**：加权和代价下「多排 1 组但引入 40 处兼项重叠」可能胜出。
   修复：代价改为**字典序元组**，并加**倒排起点**专治尾部时段碎片化（2 天排下率 95.8% → 96.5%）。

## 球赛赛制生成（`tournament/`）

- `seeding.py`：标准种子排位（`seed_order(8)=[1,8,4,5,2,7,3,6]`，1/2 号种子分居半区）；
- `round_robin.py`：圆桌轮转法（单/双循环、分组循环、主客场平衡）；
- `elimination.py`：单淘汰对阵图（轮空 + 种子）、双淘汰（胜者组/败者组）；
- `hybrid.py`：蛇形分组 → 小组循环 → 交叉淘汰；
- `volleyball.py`：排球赛完整赛制（分组 + 交叉 + 局制）。

Java 侧对应 `com.sports.schedule.tournament`，REST 入口 `POST /api/tournament/generate`。

## 设计要点

- **稀疏冲突图 + 真实时长**：合成数据还原「短跑簇 / 跳跃簇 / 投掷簇」三大兼项共现结构，
  网络密度落在真实排课冲突图的 0.02–0.29 区间；单元时长取**真实量级 20–120 分钟**
  （与 Java 端 `ScheduleUnit.rawDuration` 同量级），容量紧张度靠「多年级（3 年级 × 12 项目 ≈ 36 单元）」
  与「天数」调节（tension 0.40–1.61）。
- **固定 shape 契约**：GNN/GAN 用稠密邻接矩阵乘法实现消息传递，`MAX_NODES=256`、`MAX_SLOTS=16` 补齐/截断，保证 `torch.onnx.export` 稳定导出。
- **归一化固化**：选择器把 `mean/std` 作为模型第一层导出，Java 端只喂原始特征。
- **失败即降级**：模型缺失/加载失败/推理异常时，Java 端一律回退规则编排，接口始终能出方案。
- ⚠️ **分布一致性**：特征口径一致还不够——**数值分布也要落在真实量级**，否则模型在真实数据上
  会塌缩（曾因用「赛次」把时长放大到数百分钟，导致模型对真实 20–120 分钟时长分布外）。
