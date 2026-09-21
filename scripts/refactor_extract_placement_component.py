#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
机械切片：把 ScheduleService 的 6 个放置/落库/道次方法
（applySolved / placeOne / placeBatch / warnTrackOccupancy / saveSchedule / autoArrangeFor）
迁入新建的 SchedulePlacementComponent。

做法（与 Solve 组件一致，并加固 javadoc 扫描）：
  1) 提取方法体（含 javadoc），签名 private -> public；
  2) 拼接后替换 SchedulePlacementComponent 的 `// __METHODS__` 占位；
  3) 从 ScheduleService 删除这些方法（含 javadoc + 紧邻空行）；
  4) facade 内调用点（placeOne / placeBatch / warnTrackOccupancy）重命名为 placementComponent.xxx；
  5) facade 构造器内实例化 placementComponent 并声明字段。

加固：find_doc_start 向上扫描 javadoc 时，遇到空行即停止（不跨空行），
避免把前置的孤立 javadoc 块一并圈入（Solve 抽取时踩过的坑）。
注意 newline='' 读写，保留仓库 CRLF 行尾。
"""
import sys

SVC = "sports-backend/src/main/java/com/sports/service/ScheduleService.java"
CMP = "sports-backend/src/main/java/com/sports/service/SchedulePlacementComponent.java"

SIGNATURES = [
    "private boolean applySolved(",
    "private void placeOne(",
    "private void placeBatch(",
    "private void warnTrackOccupancy(",
    "private void saveSchedule(",
    "private int autoArrangeFor(",
]


def find_doc_start(lines, sig_idx):
    # 向上扫描 javadoc，遇到空行或非 javadoc 注释即停止（不跨空行，避免误吞前置孤立 javadoc）
    i = sig_idx - 1
    while i >= 0:
        s = lines[i].strip()
        if s.startswith('*') or s.startswith('/**') or s.startswith('*/'):
            i -= 1
        else:
            break
    return i + 1


def find_method_end(lines, sig_idx):
    depth = 0
    i = sig_idx
    started = False
    while i < len(lines):
        for ch in lines[i]:
            if ch == '(':
                depth += 1
                started = True
            elif ch == ')':
                depth -= 1
        if started and depth == 0:
            break
        i += 1
    j = i
    while j < len(lines) and '{' not in lines[j]:
        j += 1
    depth = 0
    k = j
    while k < len(lines):
        for ch in lines[k]:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
        if depth == 0:
            return k
        k += 1
    return k


def main():
    with open(SVC, encoding='utf-8', newline='') as f:
        svc = f.read()
    slines = svc.split('\n')

    extracted = []
    delete_ranges = []
    for sig in SIGNATURES:
        found = False
        for idx, line in enumerate(slines):
            if line.strip().startswith(sig):
                ds = find_doc_start(slines, idx)
                me = find_method_end(slines, idx)
                block = slines[ds:me + 1]
                sig_local = idx - ds
                block[sig_local] = block[sig_local].replace('private ', 'public ', 1)
                extracted.append('\n'.join(block))
                delete_ranges.append((ds, me))
                print("extracted [%d..%d] %s" % (ds, me, sig))
                found = True
                break
        if not found:
            print("WARN not found:", sig)

    for ds, me in sorted(delete_ranges, reverse=True):
        # 删除方法后，若上方紧邻空行也一并清掉，避免遗留多余空行
        del slines[ds:me + 1]

    svc_text = '\n'.join(slines)
    svc_text = svc_text.replace('placeOne(', 'placementComponent.placeOne(')
    svc_text = svc_text.replace('placeBatch(', 'placementComponent.placeBatch(')
    svc_text = svc_text.replace('warnTrackOccupancy(', 'placementComponent.warnTrackOccupancy(')

    field_old = "    private final ScheduleSolveComponent solveComponent;\n"
    field_new = field_old + "    private final SchedulePlacementComponent placementComponent;\n"
    if field_old not in svc_text:
        print("WARN field anchor not found")
    svc_text = svc_text.replace(field_old, field_new, 1)

    ctor_old = ("        this.solveComponent = new ScheduleSolveComponent(scheduleOptimizer, ruleBasedScheduler, geneticAlgorithm, lnsImprover, buildComponent,\n"
                "                lnsRounds, lnsRoundMillis, gaPopulation, gaGenerations, gaMutationRate, gaIndividualMillis);\n")
    ctor_new = ctor_old + "        this.placementComponent = new SchedulePlacementComponent(arrangementService, arrangementRepository, scheduleRepository, buildComponent);\n"
    if ctor_old not in svc_text:
        print("WARN ctor anchor not found")
    svc_text = svc_text.replace(ctor_old, ctor_new, 1)

    with open(SVC, 'w', encoding='utf-8', newline='') as f:
        f.write(svc_text)

    with open(CMP, encoding='utf-8', newline='') as f:
        cmp_text = f.read()
    methods_text = '\n\n'.join(extracted)
    if '    // __METHODS__' not in cmp_text:
        print("WARN placeholder not found in component")
    cmp_text = cmp_text.replace('    // __METHODS__', methods_text)
    with open(CMP, 'w', encoding='utf-8', newline='') as f:
        f.write(cmp_text)

    print("done. facade lines now", len(slines), "methods extracted", len(extracted))


if __name__ == '__main__':
    main()
