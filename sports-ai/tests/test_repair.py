"""硬约束修复层测试：模型输出 → 规则兜底 → 可交付方案。

这一层存在的理由：深度模型学的是**排序/打分**，没有任何机制保证「同一个人在
两个项目里不同时开赛」这种 100% 不能错的事故约束。所以断言全部按「硬约束
必须成立、修不动必须诚实报错」来写，而不是看分数好不好看。
"""

from __future__ import annotations

from types import SimpleNamespace

from sports_ai.solve.repair import fragmentation, need_of, repair_assignment

# (day, window) 三个时段，两片场地
SLOTS = [(1, 0), (1, 1), (1, 2)]
VENUES = ("V1", "V2")


def _caps(total_by_slot: dict[tuple[int, int], int]) -> dict:
    """总容量按场地对半劈，返回 (槽, 场地) → 容量。"""
    out = {}
    for sid, total in total_by_slot.items():
        half = total // 2
        out[(sid, "V1")] = half
        out[(sid, "V2")] = total - half
    return out


def _unit(key: str, dur: int, interval: int = 10, venue: str = "V1") -> SimpleNamespace:
    return SimpleNamespace(key=key, duration=dur, interval=interval, venue=venue)


def test_need_of_counts_duration_plus_interval():
    """占用口径 = 时长 + 最小间隔；必须与场景生成/贪心落位同一口径。"""
    assert need_of(_unit("A", 30, 10)) == 40
    assert need_of(_unit("A", 30, 0)) == 30
    # 缺省字段按 0 处理，不能抛
    assert need_of(SimpleNamespace(duration=5)) == 5


def test_capacity_overload_creates_free_space_by_relocating():
    """三单元塞同一槽把场地压爆 → 必须把最占地方的挪到后续空槽。

    ⚠️ 夹具容量要留够：单元 need = 时长+间隔 = 70，而 (1,0) 每片场地只有 50，
    所以 (1,0) 必然超载、只能往后找；后段槽必须给到 100 以上，否则「挪过去也
    装不下」，修复层会正确地报 blocked（那属于另一条测试）。
    """
    caps = _caps({(1, 0): 100, (1, 1): 200, (1, 2): 200})
    units = [_unit("A", 60), _unit("B", 60), _unit("C", 5)]
    slot_of = {"A": (1, 0), "B": (1, 0), "C": (1, 0)}

    fixed, rep = repair_assignment(units, slot_of, caps)

    assert rep["capacity_fixes"] >= 1, "应当发生容量修复"
    # 修完必须真的放下了：最占地方的先走，小单元留在原位
    assert fixed["A"] != (1, 0) or fixed["B"] != (1, 0)
    assert fixed["C"] == (1, 0), "小单元不该被无谓搬走"
    assert rep["feasible"] is True, rep["blocked"]


def test_athlete_clash_actually_repairs():
    """同一运动员被排进同一槽 → 时间靠后的那个必须被挪走。"""
    caps = _caps({(1, 0): 200, (1, 1): 200, (1, 2): 200})
    units = [_unit("A", 20, 0, "V1"), _unit("B", 20, 0, "V2")]
    slot_of = {"A": (1, 0), "B": (1, 0)}
    athlete_of = {"A": [1, 2], "B": [2, 3]}  # 2 号同时报 A/B

    fixed, rep = repair_assignment(units, slot_of, caps, athlete_of)

    assert rep["conflict_fixes"] >= 1 or fixed["B"] != fixed["A"]
    # 硬约束自检：修复后不能有同人在同槽
    seen = {}
    for key, sid in fixed.items():
        for a in athlete_of[key]:
            assert a not in seen or seen[a] != sid, f"兼项仍撞车：{key} 与 {seen[a]}"
            seen[a] = sid
    assert rep["feasible"] is True, rep["blocked"]


def test_different_slots_same_athlete_is_not_a_conflict():
    """不同槽里的同一个人完全合法 —— 早期自检把它误判成撞车。"""
    caps = _caps({(1, 0): 200, (1, 1): 200, (1, 2): 200})
    units = [_unit("A", 20, 0), _unit("B", 20, 0)]
    slot_of = {"A": (1, 0), "B": (1, 1)}
    athlete_of = {"A": [7], "B": [7]}

    fixed, rep = repair_assignment(units, slot_of, caps, athlete_of)

    assert rep["conflict_fixes"] == 0, "不同槽不该被当成冲突"
    assert rep["feasible"] is True, rep["blocked"]
    assert fixed == {"A": (1, 0), "B": (1, 1)}


def test_no_available_slot_reports_blocked_instead_of_fake_feasible():
    """一个可用槽都没有 → 老实报 blocked，绝不能假装可行。"""
    caps = _caps({(1, 0): 40})
    units = [_unit("A", 60), _unit("B", 60)]
    slot_of = {"A": (1, 0), "B": (1, 0)}

    _, rep = repair_assignment(units, slot_of, caps)

    # 注意这里**不**断言 capacity_fixes >= 1：挪不动时修复层是「记录 blocked
    # 然后放弃」而不是假装修好，所以判据只能是可行标志 + 原因，不能是修复计数。
    assert rep["feasible"] is False
    assert any("超载" in b for b in rep["blocked"]), rep["blocked"]


def test_already_feasible_assignment_is_left_alone():
    """本来就合法的解一个单元都不该动（修复层不许乱搅）。"""
    caps = _caps({(1, 0): 100, (1, 1): 100, (1, 2): 100})
    units = [_unit("A", 30), _unit("B", 30), _unit("C", 30)]
    slot_of = {"A": (1, 0), "B": (1, 1), "C": (1, 2)}

    fixed, rep = repair_assignment(units, slot_of, caps)

    assert rep["capacity_fixes"] == 0 and rep["conflict_fixes"] == 0
    assert rep["feasible"] is True
    assert fixed == slot_of


def test_fragmentation_measures_block_spread_not_a_constant():
    """碎片度必须是**真的**会变的量，而不是恒等于 1。"""
    group_of = {f"u{i}": "P1" for i in range(6)}
    caps = _caps({s: 200 for s in SLOTS})

    # 整块挤在一个槽 → 最紧凑
    tight, _ = repair_assignment([_unit(f"u{i}", 5) for i in range(6)],
                                 {f"u{i}": (1, 0) for i in range(6)}, caps)
    f_tight = fragmentation(tight, group_of)

    # 每块单元各占一个槽 → 最「见缝插针」
    loose = {"u0": (1, 0), "u1": (1, 1), "u2": (1, 2),
             "u3": (1, 0), "u4": (1, 1), "u5": (1, 2)}
    f_loose = fragmentation(loose, group_of)

    assert f_tight < f_loose, f"紧凑块 {f_tight} 应小于散块 {f_loose}"
    assert f_tight <= 1.0 and f_loose <= 1.0
    # 整块一个槽 = 1/块大小（6 单元 → 0.167），不是 1.0
    assert abs(f_tight - 1 / 6) < 1e-6, f"整块同槽应等于 1/块大小，实际 {f_tight}"


def test_fragmentation_empty_and_single_group_safe():
    """空输入/单槽槽位不应抛异常。"""
    assert fragmentation({}, {}) == 0.0
    assert fragmentation({"u": (1, 0)}, {}) == 0.0
    assert fragmentation({"u": (1, 0)}, {"u": "P"}) == 1.0
