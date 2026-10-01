"""不可解冲突的结构化输出（给程序消费）。

生产环境里「排不下」不是终点，而是**一条要给上层程序处理的数据**：班主任要收到
「谁该退哪一项」，教务处要收到「还差几天 / 还差几条赛道」。因此这里定义一份稳定的
JSON schema，把三类东西一起交付：

- ``bounds``      —— 数学下界：容量缺口 / 最少天数 / 超大单元 / 团下界；
- ``unplacedTasks`` —— 具体哪些组次没排下、为什么；
- ``conflicts``   —— 分类过的不可解冲突（容量 / 超大 / 团 / 兼项），每条带可执行线索；
- ``actions``     —— 建议动作（加天 / 加赛道 / 取消某人的某项报名）。

``schema`` 字段用于版本化，Java 侧 ``ScheduleFeasibilityService`` 产出同构报告。
"""

from __future__ import annotations

import json
from collections import Counter
from typing import Dict, List, Optional

from .feasibility import analyze_bounds
from .scheduler import ScheduleResult

SCHEMA = "sports-ai/infeasibility-report@1"


def _unplaced_reason(pool: str, bounds: Dict[str, object]) -> str:
    pools = bounds.get("pools") or {}
    info = pools.get(pool) if isinstance(pools, dict) else None
    if isinstance(info, dict) and info.get("shortfall", 0) > 0:
        return "pool_capacity_shortfall"
    return "conflict_or_fragmentation"


def build_report(title: str, meet, result: ScheduleResult,
                 bounds: Optional[Dict[str, object]] = None) -> Dict[str, object]:
    """把「场景 + 求解结果 + 下界」组装成不可解冲突报告。"""
    units = meet.scenario.units
    placements = meet.scenario.placements
    if bounds is None:
        bounds = analyze_bounds(units, placements)

    scene = {
        "athletes": len(meet.athletes),
        "classes": len({a.clazz for a in meet.athletes}),
        "events": len({u.event_id for u in units}),
        "units": len(units),
        "heatTasks": result.tasks_total,
        "days": bounds.get("days"),
        "periodsPerDay": bounds.get("periodsPerDay"),
        "poolLanes": {k: v.get("lanes") for k, v in (bounds.get("pools") or {}).items()},
        "dayMinutes": meet.stats.get("dayMinutes"),
        "trackLanes": meet.stats.get("trackLanes"),
        "fieldLanes": meet.stats.get("fieldLanes"),
    }

    summary = {
        "tasks": result.tasks_total,
        "placed": result.placed_tasks,
        "unplaced": result.tasks_total - result.placed_tasks,
        "placedRatio": round(result.placed_ratio, 4),
        "athleteSlotsTotal": result.total_athlete_slots,
        "athleteSlotsPlaced": result.total_athlete_slots - result.unplaced_athlete_slots,
        "athleteSlotsUnplaced": result.unplaced_athlete_slots,
        "conflictMultiplicity": result.conflict_multiplicity,
        "conflictAthletes": result.conflict_athletes,
        "unplacedUnits": result.unplaced_units,
    }

    # ---- 未排组次（含原因）----
    unplaced_tasks = []
    for t in result.unplaced:
        unplaced_tasks.append({
            "unit": t.unit_key,
            "batch": t.batch,
            "ofBatches": t.n_batches,
            "pool": t.pool,
            "grade": t.grade,
            "duration": t.duration,
            "athleteCount": len(t.athletes),
            "round": "final" if t.round_order == 1 else "prelim",
            "reason": _unplaced_reason(t.pool, bounds),
        })

    # ---- 分类冲突 ----
    conflicts: List[Dict[str, object]] = []

    pools = bounds.get("pools") or {}
    if isinstance(pools, dict):
        for pool, info in pools.items():
            if isinstance(info, dict) and info.get("shortfall", 0) > 0:
                conflicts.append({
                    "type": "capacity_shortfall",
                    "pool": pool,
                    "demandMinutes": info.get("demand"),
                    "supplyMinutes": info.get("supply"),
                    "shortfallMinutes": info.get("shortfall"),
                    "minDaysRequired": info.get("minDays"),
                    "message": f"{pool}容量不足，缺 {info.get('shortfall')} 分钟",
                })

    for o in bounds.get("oversizedUnits") or []:
        conflicts.append({
            "type": "oversized_unit",
            "unit": o.get("unit"),
            "durationMinutes": o.get("duration"),
            "maxWindowCapacity": o.get("maxWindowCapacity"),
            "minSplits": o.get("minSplits"),
            "message": f"单元「{o.get('unit')}」全部时长 {o.get('duration')} 分钟超过单时段容量"
                       f"{o.get('maxWindowCapacity')} 分钟，必须拆成至少 {o.get('minSplits')} 个组次",
        })

    if not bounds.get("cliqueFeasible", True):
        conflicts.append({
            "type": "clique_exceeds_periods",
            "cliqueSize": bounds.get("cliqueLowerBound"),
            "availablePeriods": bounds.get("availablePeriods"),
            "message": f"冲突图最大团 {bounds.get('cliqueLowerBound')} > 可用时段数"
                       f"{bounds.get('availablePeriods')}，结构性不可解（加场地无效，需拆组次或取消报名）",
        })

    # 残留兼项冲突按运动员聚合（给班主任的可执行清单；过多时只列 Top N，其余汇总）
    per_athlete: Dict[int, List[Dict[str, object]]] = {}
    for c in result.conflicts:
        per_athlete.setdefault(int(c["athlete"]), []).append(c)
    ranked = sorted(per_athlete.items(), key=lambda kv: -len(kv[1]))
    for aid, rows in ranked[:15]:
        conflicts.append({
            "type": "athlete_clash",
            "athlete": aid,
            "clashCount": len(rows),
            "detail": [{"period": r["period"], "day": r["day"], "units": r["units"]} for r in rows],
            "message": f"运动员 {aid} 在 {len(rows)} 个时段同时段出现于多个项目",
        })
    if len(ranked) > 15:
        conflicts.append({
            "type": "athlete_clash_summary",
            "totalAthletes": len(ranked),
            "totalClashMultiplicity": sum(len(v) for v in per_athlete.values()),
            "message": f"另有 {len(ranked) - 15} 名运动员存在兼项重叠（详见完整明细接口）",
        })

    # ---- 建议动作 ----
    actions: List[Dict[str, object]] = []
    if isinstance(pools, dict):
        for pool, info in pools.items():
            if isinstance(info, dict) and info.get("shortfall", 0) > 0:
                actions.append({
                    "action": "extend_days",
                    "scope": pool,
                    "needDays": info.get("minDays"),
                    "gain": f"{pool}容量补齐",
                })
                actions.append({
                    "action": "add_lanes",
                    "scope": pool,
                    "currentLanes": info.get("lanes"),
                    "reason": f"或为{pool}增加并发位/场地，可等价于延长天数",
                })
    for aid, rows in ranked[:10]:
        evs = Counter(u for r in rows for u in r["units"])
        actions.append({
            "action": "cancel_entry",
            "athlete": aid,
            "clashCount": len(rows),
            "candidateUnits": [u for u, _ in evs.most_common(3)],
            "reason": "该运动员兼项在其可用时段内无法错开，建议取消其中一项报名（交由班主任确认）",
        })

    return {
        "schema": SCHEMA,
        "title": title,
        "feasible": result.feasible,
        "scene": scene,
        "summary": summary,
        "bounds": bounds,
        "unplacedTasks": unplaced_tasks,
        "conflicts": conflicts,
        "actions": actions,
    }


def dump_report(report: Dict[str, object], path: str) -> str:
    """把报告写为 JSON 文件，返回路径。"""
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    return path
