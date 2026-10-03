# 模型产物的独立分发

> 关联：`sports-ai/models/MANIFEST.json`（清单）｜`scripts/gen_models_manifest.py`（生成/校验）
> 状态：**onnx 已从 git 索引移除**（2026-10-03），仓库只保留清单

---

## 1. 为什么不入库

| | 入库 | 独立分发（当前做法） |
|---|---|---|
| 仓库体积 | 26 个 onnx ≈ **90 MB**，且每次重训都变动 → 线性膨胀 | 0（只有一个小 JSON 清单） |
| 提交可读性 | 每次提交都是二进制巨块，无法 review | 清单可 diff，且能校验一致性 |
| clone 速度 | 显著变慢 | 不受影响 |

代码与产物分离：**代码进仓库，产物走分发**。

## 2. 清单（`sports-ai/models/MANIFEST.json`）

每个模型记录 **文件名 / 字节数 / sha256 / 用途**，并写明**缺失时会降级成什么**
（例如 `super_moe.onnx` 缺失 → L4 退化为 L3 优化链；`referee_gnn.onnx` 缺失 → 裁判走规则派遣）。

当前 13 个模型、合计约 **44.8 MB**：

| 类别 | 模型 |
|---|---|
| 超级编排 | `super_moe.onnx`（11 类任务合并模型） |
| GNN | `constraint_gnn` / `tournament_gnn` / `conflict_gnn` / `referee_gnn` |
| 辅助 | `algorithm_selector` / `lane_advisor` |
| 生成式 | `scheme_generator` / `scheme_discriminator` / `scheme_refiner` / `scheme_diffusion` |
| 预测 | `forecast_direct` / `forecast_mimo` |

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
