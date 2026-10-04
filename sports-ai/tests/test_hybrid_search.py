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

# ----------------------------------------------------------------------
# ⑦ 截断与剪枝（本轮修的两个静默缺陷）
# ----------------------------------------------------------------------
def test_budget_exhaustion_is_not_infeasibility():
    """**预算用尽不能当成「不可行」**（本轮最贵的一条教训）。

    第一版 `collect_training_pairs` 在 `len(feats) >= max_states` 时
    `return False`，而 `False` 会沿递归上传，把整条祖先链标成「排不完」——
    实测 HELL 档采到 1182 个样本、**标签 100% 是 0**，
    预测器因此退化成常数（零区分力），在规划层里只是个固定偏移。

    这里用一个**宽松到必然可完成**的场景 + 极小的 `max_states`：
    只要还有确定的样本，标签就必须是 1。
    若还是全 0，说明「预算耗尽」又被当成了「不可行」。
    """
    units = [u("a"), u("b"), u("c")]
    caps = {(i, "V0"): 999 for i in range(3)}
    cands = {k: [0, 1, 2] for k in ("a", "b", "c")}
    feats, labels = collect_training_pairs(
        units, candidates_of=lambda x: cands[str(x.key)], caps=caps,
        max_states=3, n_trials=2)
    assert len(labels) > 0, "至少要采到一些确定样本"
    assert labels.max() == 1.0, (
        "宽松场景下的确定样本必须标为可完成；全 0 说明「预算耗尽」又被当成了不可行")


def test_budget_exhaustion_keeps_labels_over_zero_ratio():
    """截断样本应被**丢弃**而不是标成 0：0/1 比例必须反映真实分布。"""
    units = [u("a"), u("b")]
    caps = {(0, "V0"): 60}                      # 只放得下一个 → 存在真不可完成
    cands = {"a": [0], "b": [0]}
    feats, labels = collect_training_pairs(
        units, candidates_of=lambda x: cands[str(x.key)], caps=caps, max_states=2)
    assert len(labels) > 0
    assert 0.0 in set(labels), "真不可完成的状态必须被标 0（失败也是信号）"


def test_deferred_pruning_never_loses_solution():
    """保守剪枝的「延迟重试」必须保证**不因剪枝而丢解**。

    把阈值拉到 1.0（等于「把所有候选都剪掉」这种最极端情况）——
    因为被剪的候选会在其它候选全失败后回来重试，
    所以结果必须与不剪枝**完全一致**。
    """
    units = [u("A", dur=60), u("B", dur=40), u("C", dur=60)]
    caps = {(0, "V0"): 100, (1, "V0"): 100}
    cands = {"A": [1, 0], "B": [0], "C": [1]}

    class AlwaysHopeless:
        """永远说「排不完」的假预测器：最极端的剪枝。"""
        def __call__(self, feat):
            return 0.0

    base = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                           caps=caps, heuristic_order=[0, 1, 2], max_backtracks=500)
    pruned = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                             caps=caps, heuristic_order=[0, 1, 2], max_backtracks=500,
                             predictor=AlwaysHopeless(), pred_prune_below=1.0)
    assert pruned.feasible == base.feasible
    assert pruned.slot_of == base.slot_of, "延迟重试必须让结果与不剪枝一致"
    assert pruned.log.pruned > 0, "应当确实发生过剪枝（否则这个测试没测到东西）"
    assert pruned.log.pruned_retried > 0, "被剪的候选应当被追回来重试"


def test_pruning_threshold_off_by_default():
    """未给阈值时不发生剪枝（避免默认行为被悄悄改掉）。"""
    units = [u("a"), u("b")]
    caps = {(0, "V0"): 999, (1, "V0"): 999}
    cands = {"a": [0, 1], "b": [0, 1]}

    class Noisy:
        def __call__(self, feat):
            return 0.0

    r = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)], caps=caps,
                        predictor=Noisy())
    assert r.log.pruned == 0, "没给 pred_prune_below 就不该剪枝"

# ----------------------------------------------------------------------
# ⑧ 多样化算子（本轮：先做了新算子，实测后**退回原算子** —— 见下方留档）
# ----------------------------------------------------------------------
OPS = ("none", "rotate", "failure", "slack", "adaptive", "hybrid")


def _pack_instance():
    """异质时长 + 兼项 + 紧容量：顺序会影响可行性，适合观察重启的作用。"""
    spec = [("u0", 70, 0), ("u1", 30, 1), ("u2", 70, 2), ("u3", 30, 0),
            ("u4", 70, 1), ("u5", 30, 2), ("u6", 55, 0), ("u7", 45, 1),
            ("u8", 60, 2), ("u9", 40, 0)]
    units = [u(k, dur=d, athletes=[a]) for k, d, a in spec]
    caps = {(i, "V0"): 90 for i in range(4)}
    return units, caps, {"u" + str(i): [0, 1, 2, 3] for i in range(10)}


def test_more_restarts_never_worse():
    """**真不变量**：重启更多时解不得更差。

    为什么必然成立：第 0 次尝试在两种预算下**完全相同**，而返回值取历次最优
    （``best``）。所以 R=32 至少不劣于 R=1。
    这条锁住的是「重启没有把已有解弄丢」—— 一旦实现里把 best 覆盖成
    「最后一次的结果」，它立刻失败。
    """
    units, caps, cands = _pack_instance()
    for op in OPS:
        r1 = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                             caps=caps, max_restarts=1, div_operator=op)
        r32 = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                              caps=caps, max_restarts=32, div_operator=op)
        assert r32.value <= r1.value + 1e-9, f"{op}: 重启更多反而更差 {r1.value} → {r32.value}"


def test_no_diversification_means_restarts_are_wasted():
    """**真不变量**：``div_operator="none"`` 时每次尝试完全相同 → 改进恒为 0。

    这正是「加预算救不了坏算子」的形式化：没有算子时，把 R 从 1 加到 32
    只是把同一件事重做 32 次，代价与改进次数都不变。
    """
    units, caps, cands = _pack_instance()
    r1 = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                         caps=caps, max_restarts=1, div_operator="none")
    r32 = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                          caps=caps, max_restarts=32, div_operator="none")
    assert r32.value == r1.value, "无算子时多跑重启不该改变结果"
    assert r32.log.restart_improved == 0, "无算子时不可能有「重启改进」"
    # ⚠️ 重启次数**不一定**跑满：`backtracks` 触顶会提前 break（这是设计里的刹车）。
    #    所以这里只断言「确有重启发生」，而不断言等于 max_restarts-1。
    assert r32.log.restarts >= 1, "应确实发生过重启（否则没测到东西）"


def test_every_operator_accounts_for_all_units():
    """**真不变量**：任何算子下，每个单元要么被排、要么被列为未排 —— 不能凭空消失。

    锁住的是「换序算子不得破坏单元集合」：一个写错的 permute（例如用 set
    去重、或切片漏掉首尾）会让单元静默丢失，而代价函数只会看到「未排变少」，
    看起来像**变好了**。
    """
    units, caps, cands = _pack_instance()
    all_keys = {str(x.key) for x in units}
    for op in OPS:
        r = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                            caps=caps, max_restarts=8, div_operator=op)
        placed = set(r.slot_of.keys())
        blocked = set(r.blocked)
        assert placed | blocked == all_keys, f"{op}: 单元丢失 {all_keys - placed - blocked}"
        assert not (placed & blocked), f"{op}: 同一单元既被排又未排"
        # violations **只放非法落位**；「排不下」走 blocked。
        # 这条断言钉住的是双端契约：Java 侧 PredictivePlanner.verify() 也只报这三类。
        assert r.violations == [], f"{op}: 不得有任何非法落位（未排不属于 violations）"


def test_default_operator_is_rotate():
    """锁住**数据驱动**的默认值：rotate。

    ⚠️ 留档：上一轮曾断言「rotate 不产生有效多样化」并打算换掉它 ——
    那是**单 seed（seed=0）假象**。6 seeds × 五档实测合计代价（R=1 → R=32）：

    ============  ==========  ==========
    算子           R=32        总降幅
    ============  ==========  ==========
    none          335.8       0.0%
    **rotate**    **292.8**   **12.8%**
    adaptive      293.8       12.5%
    failure       294.2       12.4%
    hybrid        298.2       11.2%
    slack         317.2       5.6%
    ============  ==========  ==========

    成立的两条是：① **不加算子时重启完全无用**（none 恒 0 降幅、改进恒 0）；
    ② 但几个算子**统计上旗鼓相当（12.4%~12.8%）**，没有单一算子显著胜出。
    所以保持 rotate 作默认（最优且最简单），其余算子留作可选 ——
    这个断言就是为了防止有人再凭单点观测改掉它。
    """
    import inspect
    sig = inspect.signature(plan_predictive)
    assert sig.parameters["div_operator"].default == "rotate"
    assert sig.parameters["rcl_k"].default == 1, "默认必须是确定性（rcl>=2 只在大预算下微利）"

def test_violations_exclude_unplaced():
    """``violations`` 只放非法落位，「排不下」走 ``blocked`` —— 两者不得混。

    这是一个**真缺陷的回归钉子**：Python 侧原先直接把 ``verify_slot_map``
    的完整原因（含 ``未排:<key>``）塞进 ``violations``，于是
    「全部单元都排下了但 violations 非空」与「violations 为空但一个都没排下」
    两种误读都可能发生。Java 侧的 ``verify()`` 从来只报超容/兼项/场地未开，
    所以这也是**双端契约漂移**。
    """
    # 容量只够放 1 个 → 必然有未排；但不应产生任何「非法落位」
    units = [u("a", dur=60), u("b", dur=60)]
    caps = {(0, "V0"): 60}
    r = plan_predictive(units, candidates_of=lambda x: [0], caps=caps, max_restarts=2)
    assert r.blocked, "必然有单元排不下"
    assert r.violations == [], "排不下不得出现在 violations 里"
    assert not r.feasible

# ----------------------------------------------------------------------
# ⑨ 块连续性修复（代价里已罚「同组跨天」，这一步真的去修）
# ----------------------------------------------------------------------
def test_block_repair_moves_group_onto_one_day():
    """直接测算子：同组跨天时，应把成员搬到同一天（用**元组槽**表达「同一天多个槽」）。

    ⚠️ 槽的「天」由 ``_day_of`` 定义：int 槽直接取整数值当天
    （即槽 0 是第 0 天、槽 1 是第 1 天），元组槽 ``(day, idx)`` 取 ``day``。
    这里用 ``day_of = lambda s: s // 2`` 把「每 2 个槽一天」显式表达出来，
    目的是让「同一天里有空位」这件事可构造 —— 否则演示不出搬迁。
    """
    from sports_ai.plan.hybrid_search import _repair_blocks, _breaks_of

    units = [u("g1", dur=30, group="G"), u("g2", dur=30, group="G")]
    caps = {(0, "V0"): 60, (1, "V0"): 60, (2, "V0"): 60}
    day_of = lambda sd: sd // 2                     # slot 0,1 → 第 0 天；slot 2,3 → 第 1 天
    slot_of = {"g1": 2, "g2": 0}                    # 跨了第 0/1 天 → 1 处断裂
    cands = {"g1": [2, 1], "g2": [0]}

    assert _breaks_of(units, slot_of, day_of) == 1
    fixed, moves = _repair_blocks(units, slot_of, caps, None,
                                 lambda x: cands[str(x.key)], day_of)
    assert _breaks_of(units, fixed, day_of) == 0, "应把同组并到同一天"
    assert moves == 1
    ok, why = verify_slot_map(units, fixed, caps)
    assert ok, f"修复后仍须合法：{why}"


def test_block_repair_uses_swap_when_anchor_day_is_full():
    """锚点天**没有空闲容量**时必须靠**交换**收敛（这是本轮加 swap 的原因）。

    构造：第 0 天两个槽都被别人占满，但 g1 在第 1 天。
    直接搬迁无处可去（容量不够），只能把 g1 与第 0 天的某个单元**互换位置**
    —— 交换是容量守恒的，所以不需要任何空闲容量。
    实测（6 seeds、五档）：只有直接搬迁时 BLOCK 档 12 处断裂只修掉 0.7 处；
    加上交换后升到 1.2 处、全局 breaks 降幅由 5.0% 提到 **9.2%**。
    """
    from sports_ai.plan.hybrid_search import _breaks_of, _is_legal, _repair_blocks

    units = [u("g1", dur=30, group="G"), u("g2", dur=30, group="G"),
             u("x1", dur=30), u("x2", dur=30)]
    caps = {(0, "V0"): 30, (1, "V0"): 30, (2, "V0"): 30}
    day_of = lambda sd: sd // 2
    # 第 0 天（槽 0）已被 g2 占满；槽 1 被 x1 占满；g1 在槽 2（第 1 天）
    slot_of = {"g1": 2, "g2": 0, "x1": 1}
    cands = {"g1": [2, 0, 1], "g2": [0], "x1": [1], "x2": [2]}

    assert _breaks_of(units, slot_of, day_of) == 1
    fixed, moves = _repair_blocks(units, slot_of, caps, None,
                                 lambda x: cands[str(x.key)], day_of)
    assert _breaks_of(units, fixed, day_of) == 0, "交换后应同组同天"
    # ⚠️ 这里必须用 ``_is_legal`` 而不是 ``verify_slot_map(...)[0]``：
    #    本用例的 ``slot_of`` 是**部分解**（x2 还没排），而 ``verify_slot_map``
    #    会把「未排」也算作不可行 —— 用它判合法性，会让**任何**在部分解上的
    #    修复都被误判成非法（这正是本轮修掉的一个真 bug：
    #    块修复在「尚有单元未排」的实例上完全失效，表现为「算子没效果」）。
    assert _is_legal(units, fixed, caps, None), "交换后仍须合法（容量/兼项/场地开放）"
    assert "x2" not in fixed, "未排的单元不应被凭空塞进来"


def test_block_repair_never_worsens_the_plan():
    """**真不变量**：开块修复不得让解变差，也不得破坏合法性。

    它只接受「合法 + breaks 严格下降」的移动，所以是**纯改进算子**。
    这条锁住的是「修复层把自己的约束搞坏」——例如搬完之后忘了重查容量。
    """
    units, caps, cands = _pack_instance()
    for restarts in (1, 8):
        off = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                              caps=caps, max_restarts=restarts, repair_blocks=False)
        on = plan_predictive(units, candidates_of=lambda x: cands[str(x.key)],
                             caps=caps, max_restarts=restarts, repair_blocks=True)
        assert on.value <= off.value + 1e-9, "块修复不得让代价变大"
        assert on.feasible == off.feasible, "块修复不得改变可行性结论"
        assert on.violations == [], "块修复不得引入非法落位"
        assert set(on.slot_of) | set(on.blocked) == set(off.slot_of) | set(off.blocked)
