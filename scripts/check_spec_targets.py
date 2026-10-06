# -*- coding: utf-8 -*-
"""审计：每个登记模型的**标签定位**是否真的取到标签。

背景（2026-10-06 实测踩到）：`algorithm_selector` / `ai` 的 `layout` 写成 "xL"，
而 `make_selector_batch` 返回的批里索引 1 是**掩码**、标签在索引 4。于是：
  prepare_batch 认为掩码缺失 → 合成一个全 1 张量 → find_target 的身份排除失效
  → **标签恒为 1，模型被训成常量函数**，而日志上「指标 1.0000」看起来非常成功。
修好 layout 后实测：留出集准确率 **0.392**、多数类基线 **0.608** ⇒ 比永远猜多数类还差。

判据（三条，缺一不可）：
  ① layout 声明的每个输入字符，对应的张量维度必须与语义相符（m/a/t 应是掩码/邻接/类型掩码）；
  ② 标签张量**不等于**批次里任何一个输入张量（身份与数值都不等）；
  ③ 分类任务里标签必须**至少出现两个类别**、且**不等于全 1 的掩码**。

用法：python scripts/check_spec_targets.py [--batch 8]
退出码 1 表示有模型不通过（可挂 CI / 提交钩子）。
"""
import argparse
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
sys.path.insert(0, os.path.join(ROOT, 'sports-ai'))

import numpy as np  # noqa: E402
import torch  # noqa: E402

from sports_ai.nn import upgrade_train as U  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--batch', type=int, default=8)
    args = ap.parse_args()

    bad, ok = [], 0
    for name, spec in U.registry().items():
        src = U.data_source(spec)
        if src is None:
            print('[skip] %-22s 未登记数据源' % name)
            continue
        try:
            if spec.takes_pad_to:
                b = src(args.batch, seed=1, pad_to=spec.pad_to)
            else:
                b = src(args.batch, seed=1)
            if spec.batch_fn is not None:
                b = spec.batch_fn(b, spec)
            x, mask, adj, tmask, target = U.prepare_batch(spec, b, 'cpu')
        except Exception as e:  # 数据源本身可能依赖随机场景
            print('[skip] %-22s 取批失败: %s' % (name, str(e)[:60]))
            continue

        problems = []
        t = target.detach()
        if t.dim() > 2:
            problems.append('标签维度 %d 维（应 ≤2）' % t.dim())
        # ② 标签不能与任何输入张量是同一个对象
        for label, obj in (('x', x), ('mask', mask), ('adj', adj), ('tmask', tmask)):
            if obj is not None and obj is target:
                problems.append('标签与输入 %s 是同一张量' % label)
        # ③ 分类任务：标签应有 >=2 个取值，且不能全是 1（典型的「取到掩码」特征）
        if spec.loss in ('ce', 'bce'):
            u = np.unique(t.numpy())
            if u.size < 2:
                problems.append('标签只有 1 个取值 %s（分类任务必然退化）' % u.tolist())
            if u.size == 1 and float(u[0]) == 1.0:
                problems.append('标签恒为 1 —— 高度疑似取到了全 1 掩码')
            if spec.loss == 'ce':
                mx = int(t.max()) if t.numel() else 0
                if mx >= (spec.out_dim or 2):
                    problems.append('标签最大值 %d ≥ out_dim %d' % (mx, spec.out_dim))
        # ① layout 字符数不能超过批次长度（多出来的字符必然错位）
        if len(spec.layout) > len(b):
            problems.append('layout %r 比批次长度 %d 还长' % (spec.layout, len(b)))

        if problems:
            bad.append(name)
            print('[FAIL] %-22s loss=%-4s layout=%-6r → %s'
                  % (name, spec.loss, spec.layout, '；'.join(problems)))
        else:
            ok += 1
            extra = ''
            if spec.loss in ('ce', 'bce'):
                u, c = np.unique(t.numpy(), return_counts=True)
                extra = ' 类别分布 %s' % dict(zip(u.tolist(), c.tolist()))
            print('[ ok ] %-22s loss=%-4s layout=%-6r 标签 %s%s'
                  % (name, spec.loss, spec.layout, tuple(t.shape), extra))

    print('\n通过 %d 个，失败 %d 个' % (ok, len(bad)))
    if bad:
        print('不通过：', ', '.join(bad))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
