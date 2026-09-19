#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
机械切片：把 ScheduleService 的 2 个求解方法（fillSolvedFromRules / fillSolvedFromSolver）
迁入新建的 ScheduleSolveComponent。

做法（与前两刀一致）：
  1) 从 ScheduleService 提取方法体（含 javadoc），签名 private -> public；
  2) 把方法体拼接后替换 ScheduleSolveComponent 的 `// __METHODS__` 占位；
  3) 从 ScheduleService 删除这 2 个方法（含 javadoc + 紧邻空行）；
  4) facade 内调用点重命名为 solveComponent.xxx；
  5) facade 构造器内实例化 solveComponent 并声明字段。

注意：newline='' 读写以保留仓库 CRLF 行尾（避免整文件行尾变化被 git 误判）。
"""
import sys

SVC = "sports-backend/src/main/java/com/sports/service/ScheduleService.java"
CMP = "sports-backend/src/main/java/com/sports/service/ScheduleSolveComponent.java"

SIGNATURES = [
    "private void fillSolvedFromRules(",
    "private void fillSolvedFromSolver(",
]


def find_doc_start(lines, sig_idx):
    i = sig_idx - 1
    while i >= 0:
        s = lines[i].strip()
        if s.startswith('*') or s.startswith('/**') or s.startswith('*/') or s == '':
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
                found = True
                print("extracted [%d..%d] %s" % (ds, me, sig))
                break
        if not found:
            print("WARN not found:", sig)

    for ds, me in sorted(delete_ranges, reverse=True):
        del slines[ds:me + 1]

    svc_text = '\n'.join(slines)
    svc_text = svc_text.replace('fillSolvedFromRules(', 'solveComponent.fillSolvedFromRules(')
    svc_text = svc_text.replace('fillSolvedFromSolver(', 'solveComponent.fillSolvedFromSolver(')

    field_old = "    private final ScheduleSelfCheckComponent selfCheckComponent;\n"
    field_new = field_old + "    private final ScheduleSolveComponent solveComponent;\n"
    if field_old not in svc_text:
        print("WARN field anchor not found")
    svc_text = svc_text.replace(field_old, field_new, 1)

    ctor_old = "        this.selfCheckComponent = new ScheduleSelfCheckComponent(lowerBoundEstimator, buildComponent);\n"
    ctor_new = ctor_old + "        this.solveComponent = new ScheduleSolveComponent(scheduleOptimizer, ruleBasedScheduler, geneticAlgorithm, lnsImprover, buildComponent);\n"
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
