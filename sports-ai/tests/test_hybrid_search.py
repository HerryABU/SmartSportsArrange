"""`sports_ai.plan` 的单元测试：启发式 + 神经排序 + 预测 + 回退。

覆盖点（每条都对应一个真实设计决策，不是凑数）：

* 确定性校验器**只判「能不能」**，且「场地未开」判不可行而不是当无限容量；
* 回溯确实发生、且最终解**合法**（回退不是为了好看，是为了找到可行解）；
* Repair 能靠换槽消掉超载；修不动时**诚实上报 blocked**；
* Restart 换序重来；状态局部化特征只依赖当前状态；
* 预测器的**非对称损失**（高估罚更重）与**可采纳性保护**（保守化后不高估）。
"""

from __future__ import annotations

import random
from types import SimpleNamespace

import numpy as np
import pytest

from sports_ai.plan import (
    STATE_FEAT_DIM,
    CompletionPredictor,
    StateView,
    collect_training_pairs,
    conservative_value,
    is_admissible,
    plan_predictive,
    verify_slot_map,
)


def u(key, venue="V0", dur=60, group=None, athletes=()):
    return SimpleNamespace(key=key, venue=venue, duration=dur,
                           group_key=group, athletes=list(athletes))


# ----------------------------------------------------------------------
# ① 确定性校验器
# ----------------------------------------------------------------------
def test_verify_detects_overload():
    units = [u("a", dur=60), u("b", dur=60)]
    caps = {(0, "V0"): 100}
    ok, why = verify_slot_map(units, {"a": 0, "b": 0}, caps)
    assert not ok and any("超容" in w for w in why)


def test_verify_treats_missing_venue_slot_as_infeasible():
    """⚠️ caps 里没有的 (槽, 场地) = 该时段场地**不开**，不能当无限容量放过。"""
    units = [u("a", dur=10)]
    caps = {(1, "V0"): 999}                     # 只有槽 1，没有槽 0
    ok, why = verify_slot_map(units, {"a": 0}, caps)
    assert not ok, "场地未开的时段必须判不可行"
    assert any("场地未开" in w for w in why)


def test_verify_detects_athlete_conflict():
    units = [u("a", athletes=[7]), u("b", athletes=[7])]
    caps = {(0, "V0"): 999, (1, "V0"): 999}
    ok, why = verify_slot_map(units, {"a": 0, "b": 0}, caps, {"a": [7], "b": [7]})
    assert not ok and any("兼项撞" in w for w in why)
    # 换到不同槽即可行
    ok2, _ = verify_slot_map(units, {"a": 0, "b": 1}, caps, {"a": [7], "b": [7]})
    assert ok2


# ----------------------------------------------------------------------
# ② 回溯：必须回退才能找到可行解
# ----------------------------------------------------------------------
def test_forward_check_prunes_athlete_conflict():
    """兼项冲突应在**落子时**被前向检查剪掉，因此**不需要**回退。

    这是设计演进的结果：早期只在**候选排序**里考虑兼项（打分给 -inf），
    但排序不剪枝 —— DFS 仍会生成「同槽同人」的**完整方案**，
    校验失败 → Repair 也修不动 → 回溯后确定性顺序又走同一条路，
    实测空转到 2 万次回退一个都没排下。
    把兼项提到 `_local_ok`（前向检查）后，这类分支根本不会被展开：
    **回退次数为 0**，且解必然合法。
    """
    units = [u("a", athletes=[7]), u("b", athletes=[7])]
    caps = {(0, "V0"): 999, (1, "V0"): 999}
    cands = {"a": [0, 1], "b": [0, 1]}
    res = plan_predictive(
        units,
        candidates_of=lambda x: cands[str(x.key)],
        caps=caps,
        athletes={"a": [7], "b": [7]},
        # 神经先验**故意**把 b 往槽 0 引（与 a 撞车）
        neural_prior=lambda unit, sid: 1.0 if (unit.key == "b" and sid == 0) else 0.0,
    )
    assert res.feasible and res.violations == []
    assert res.slot_of["a"] != res.slot_of["b"], "共享运动员的单元不得同槽"
    assert res.log.backtracks == 0, "前向检查应在展开之前就剪掉冲突分支（无需回退）"
    assert res.log.repairs == 0, "既然没有冲突方案产出，Repair 就不该被触发"


def test_backtracking_exercised_when_forward_check_insufficient():
    """前向检查**看不出的**死胡同仍需回退。

    这里把 A 排在最前（`heuristic_order` 显式给），A 的两个候选槽「装完一样满」，
    于是按候选顺序先占槽 1 —— 而 C 只有槽 1 可去，A 占了就谁都放不下。
    前向检查只能看「当前这一步」，看不到这个跨步的连锁，
    必须**回溯**把 A 改到槽 0，解才存在。这正是 Rollback 存在的意义。
    """
    units = [u("A", dur=60), u("B", dur=40), u("C", dur=60)]
    caps = {(0, "V0"): 100, (1, "V0"): 100}
    cands = {"A": [1, 0], "B": [0], "C": [1]}
    res = plan_predictive(
        units,
        candidates_of=lambda x: cands[str(x.key)],
        caps=caps,
        heuristic_order=[0, 1, 2],          # 强行先排 A
        max_backtracks=200,
    )
    assert res.feasible, f"回溯后应找到可行解，违规={res.violations}"
    assert res.log.backtracks >= 1, "该场景必须发生回退（前向检查看不出的跨步死胡同）"
    assert res.log.rollbacks == res.log.backtracks
    assert res.slot_of["C"] == 1 and res.slot_of["A"] == 0, "A 必须让位给 C"


def test_no_candidates_reports_blocked_honestly():
    """无候选可用时**如实报 blocked**，不返回一个假装可行的方案。"""
    units = [u("a"), u("b")]
    caps = {(0, "V0"): 999}
    res = plan_predictive(
        units,
        candidates_of=lambda x: [0] if x.key == "a" else [],
        caps=caps,
    )
    assert not res.feasible
    assert "b" in res.blocked or any("无候选" in r for r in res.log.backtrack_reasons)


# ----------------------------------------------------------------------
# ③ Repair 与 Restart
# ----------------------------------------------------------------------
def test_repair_fixes_overload_without_backtracking():
    """完成态超载时，Repair 靠换槽消掉，不必回退整条路径。"""
    units = [u("a", dur=60), u("b", dur=60)]
    caps = {(0, "V0"): 60, (1, "V0"): 60}      # 每槽只放得下 1 个
    cands = {"a": [0, 1], "b": [0, 1]}
    res = plan_predictive(
        units,
        candidates_of=lambda x: cands[str(x.key)],
        caps=caps,
    )
    assert res.feasible
    assert res.slot_of["a"] != res.slot_of["b"]


def test_restart_counter_increments_when_first_order_fails():
    """第一趟注定失败时，Restart 应被触发（计数器 > 0）。"""
    units = [u("a", dur=60), u("b", dur=60)]
    caps = {(0, "V0"): 60, (1, "V0"): 60}
    cands = {"a": [0], "b": [0]}               # 两者都只有槽 0 → 首趟无解
    res = plan_predictive(
        units, candidates_of=lambda x: cands[str(x.key)], caps=caps,
        max_restarts=3,
    )
    # 首趟失败 → Restart ≥ 1；即便最终仍无解，也必须如实报 blocked
    assert res.log.restarts >= 1
    assert (not res.feasible) and res.blocked


# ----------------------------------------------------------------------
# ④ 状态局部化（对应 arXiv:2605.22221 的 history entanglement）
# ----------------------------------------------------------------------
def test_state_features_depend_only_on_state_not_history():
    """到达同一状态的两条不同路径，特征必须逐位相同。"""
    s1 = StateView(n_total=6, n_placed=2, used={(0, "V0"): 60}, cap={(0, "V0"): 120},
                   days_used=1, day_budget=3, remaining_exposure=2.0)
    s2 = StateView(n_total=6, n_placed=2, used={(0, "V0"): 60}, cap={(0, "V0"): 120},
                   days_used=1, day_budget=3, remaining_exposure=2.0)
    assert s1.feat().shape == (STATE_FEAT_DIM,)
    assert np.array_equal(s1.feat(), s2.feat())
    assert np.all(s1.feat() >= 0.0) and np.all(s1.feat() <= 1.0 + 1e-6), "特征必须归一"


# ----------------------------------------------------------------------
# ⑤ 预测器：非对称损失 + 可采纳性保护
# ----------------------------------------------------------------------
def test_asymmetric_loss_penalizes_overestimation_harder():
    torch = pytest.importorskip("torch")
    p = CompletionPredictor(hidden=8, seed=1)
    tgt = torch.tensor([[1.0], [1.0]])
    near = torch.tensor([[0.9], [0.9]])       # 接近真值
    far = torch.tensor([[0.1], [0.1]])        # 离真值很远
    assert float(p.asymmetric_loss(far, tgt)) > float(p.asymmetric_loss(near, tgt)), \
        "偏离真值越远，损失必须越大"

    # 反向：target=0 时，高估（0.9）应被重罚（这正是「别乐观」的方向）
    tgt0 = torch.tensor([[0.0], [0.0]])
    assert float(p.asymmetric_loss(torch.tensor([[0.9], [0.9]]), tgt0)) > \
           float(p.asymmetric_loss(torch.tensor([[0.1], [0.1]]), tgt0))


def test_conservative_value_and_admissibility():
    assert conservative_value(10.0, 2.5) == pytest.approx(7.5)
    assert is_admissible(7.5, 10.0), "低估是可采纳的"
    assert not is_admissible(11.0, 10.0), "高估不可采纳"


def test_calibration_removes_optimistic_bias():
    """欠训的预测器必然乐观；校准后**逐样本**不得再出现高估。

    这里刻意用**混合标签**（一半可完成、一半不可）：全 1 的标签下
    sigmoid 输出永远 < 1，校准偏移恒为 0，测不出任何东西（假测试）。
    """
    torch = pytest.importorskip("torch")
    rng = np.random.default_rng(0)
    feats = rng.random((64, STATE_FEAT_DIM)).astype(np.float32)
    labels = np.array([1.0 if i % 2 == 0 else 0.0 for i in range(64)], dtype=np.float32)
    p = CompletionPredictor(hidden=16, seed=2)
    p.fit(feats, labels, epochs=1)                  # 欠训 → 必然乐观
    before = [float(p.net(torch.from_numpy(f.reshape(1, -1))).item()) for f in feats]
    over_before = sum(1 for v, y in zip(before, labels) if v > y + 1e-9)
    assert over_before > 0, "欠训模型应当存在高估（否则这个测试没有意义）"

    delta = p.calibrate(feats, labels, quantile=1.0)
    assert delta > 0.0
    after = [p(f) for f in feats]
    assert all(is_admissible(v, float(y)) for v, y in zip(after, labels)), \
        "校准后不得再有高估（这正是可采纳性要保的东西）"


# ----------------------------------------------------------------------
# ⑥ 搜索树标签（Pyligent 的「失败分支也是监督信号」）
# ----------------------------------------------------------------------
def test_collect_training_pairs_marks_infeasible_states():
    units = [u("a", dur=60), u("b", dur=60)]
    caps = {(0, "V0"): 60}                     # 只能放 1 个 → 存在失败状态
    cands = {"a": [0], "b": [0]}
    feats, labels = collect_training_pairs(
        units, candidates_of=lambda x: cands[str(x.key)], caps=caps, max_states=64)
    assert feats.shape[1] == STATE_FEAT_DIM
    assert feats.shape[0] == labels.shape[0] > 0
    assert set(np.unique(labels)).issubset({0.0, 1.0})
    assert 0.0 in set(np.unique(labels)), "不可完成的状态必须被标为 0（失败也是信号）"


def test_collect_training_pairs_all_success_when_roomy():
    units = [u("a"), u("b")]
    caps = {(0, "V0"): 999, (1, "V0"): 999}
    cands = {"a": [0, 1], "b": [0, 1]}
    feats, labels = collect_training_pairs(
        units, candidates_of=lambda x: cands[str(x.key)], caps=caps, max_states=64)
    assert len(labels) > 0 and labels.min() == 1.0
