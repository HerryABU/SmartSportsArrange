#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""增量二：把 3 个 SelfCheck 类方法从 ScheduleService 删除，调用点改 selfCheckComponent.xxx，
在 buildComponent 之后接线 ScheduleSelfCheckComponent（facade 构造器内实例化）。"""
import io

SVC = r"C:\Users\QBZ95\Desktop\tools\codes\sports\sports-backend\src\main\java\com\sports\service\ScheduleService.java"

with io.open(SVC, "r", encoding="utf-8", newline="") as f:
    text = f.read()

lines = text.split("\n")

# 1) 逐个方法：定位签名行 -> 括号配平找方法尾 -> 删除（含紧邻上方 javadoc 块，支持单行与多行）
SIGNATURES = [
    "private List<ScheduleVerifier.Row> collectVerifyRows(List<EventSchedule> saved,",
    "private List<ScheduleVerifier.Expected> collectVerifyExpected(List<Unit> units) {",
    "private LowerBoundEstimator.Assessment assessLowerBound(List<Unit> units, List<Window> windows,",
]

def find_method_end(sig):
    si = None
    for i, ln in enumerate(lines):
        if sig in ln:
            si = i
            break
    if si is None:
        return None, None
    depth = 0
    started = False
    end = si
    i = si
    while i < len(lines):
        ln = lines[i]
        for ch in ln:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
                if started and depth == 0:
                    end = i
                    break
        if started and depth == 0:
            break
        i += 1
    # 向上包含 javadoc（多行 /** ... */ 与单行 /** ... */ 均覆盖）
    start = si
    j = si - 1
    while j >= 0:
        s = lines[j].strip()
        if s.startswith("*") or s.startswith("/**") or s == "*/":
            start = j
            j -= 1
        else:
            break
    return start, end

ranges = []
for sig in SIGNATURES:
    s, e = find_method_end(sig)
    if s is None:
        print("WARN not found:", sig)
        continue
    ranges.append((s, e))
    print("delete", sig, "->", s + 1, "..", e + 1)

ranges.sort(reverse=True)
for (s, e) in ranges:
    del lines[s:e + 1]

text2 = "\n".join(lines)

# 2) 调用点重命名（定义已删，剩余均为调用）
for name in ["collectVerifyRows", "collectVerifyExpected", "assessLowerBound"]:
    text2 = text2.replace(name + "(", "selfCheckComponent." + name + "(")

# 3) 接线：在 buildComponent 字段之后插入 selfCheckComponent 字段
text2 = text2.replace(
    "    private final ScheduleBuildComponent buildComponent;\n",
    "    private final ScheduleBuildComponent buildComponent;\n\n    private final ScheduleSelfCheckComponent selfCheckComponent;\n",
    1)

# 4) 接线：在 buildComponent 实例化之后实例化 selfCheckComponent
text2 = text2.replace(
    "        this.buildComponent = new ScheduleBuildComponent(eventRepository, registrationRepository, systemService);\n",
    "        this.buildComponent = new ScheduleBuildComponent(eventRepository, registrationRepository, systemService);\n"
    "        this.selfCheckComponent = new ScheduleSelfCheckComponent(lowerBoundEstimator, buildComponent);\n",
    1)

with io.open(SVC, "w", encoding="utf-8", newline="") as f:
    f.write(text2)

remaining = len(text2.split("\n"))
print("remaining lines:", remaining)
print("selfCheckComponent refs:", text2.count("selfCheckComponent."))
