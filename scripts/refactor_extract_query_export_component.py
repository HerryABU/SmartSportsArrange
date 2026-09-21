#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 ScheduleService 抽出 ScheduleQueryExportComponent（查询/保存/清空/导出/结果组装）。

- 用 newline='' 读写，保留仓库 CRLF 行尾（此前 Placement 脚本因 \n anchor 不匹配漏注入字段/构造器）。
- 机械地把 list/save/clear/export 4 个公开方法改为转发到组件；
  buildResult / auditAfterCommit 在 autoSchedule 链路里仍被 facade 直接调用，故组件内为 public，facade 调站点改名。
- 组件方法体已手写落地到 ScheduleQueryExportComponent.java（与 facade 同包，无需互 import）。
"""
import io

FACADE = r"C:\Users\QBZ95\Desktop\tools\codes\sports\sports-backend\src\main\java\com\sports\service\ScheduleService.java"

with io.open(FACADE, "r", encoding="utf-8", newline="") as f:
    src = f.read()

# ---------- 1) 用 4 个委派方法替换 7 方法块 ----------
start_anchor = "    @Transactional(readOnly = true)\r\n    public Map<String, Object> list() {"
config_line = "    // ==================== 配置合并（mergeConfig 已迁入 ScheduleBuildComponent） ====================\r\n"

i = src.index(start_anchor)
j = src.index(config_line)

delegates = (
    "    @Transactional(readOnly = true)\r\n"
    "    public Map<String, Object> list() {\r\n"
    "        return queryExportComponent.list();\r\n"
    "    }\r\n"
    "\r\n"
    "    public Map<String, Object> save(List<Map<String, Object>> items) {\r\n"
    "        return queryExportComponent.save(items);\r\n"
    "    }\r\n"
    "\r\n"
    "    public void clear() {\r\n"
    "        queryExportComponent.clear();\r\n"
    "    }\r\n"
    "\r\n"
    "    public void export(HttpServletResponse response) {\r\n"
    "        queryExportComponent.export(response);\r\n"
    "    }\r\n"
)

src = src[:i] + delegates + "\r\n" + config_line + src[j + len(config_line):]

# ---------- 2) facade 内部调用点改名 ----------
old_build = "        Map<String, Object> result = buildResult();"
new_build = "        Map<String, Object> result = queryExportComponent.buildResult();"
assert src.count(old_build) == 1, "buildResult 调用点数量异常: %d" % src.count(old_build)
src = src.replace(old_build, new_build)

old_audit = '        auditAfterCommit(() -> auditService.record("SCHEDULE_AUTO", "SCHEDULE", null,'
new_audit = '        queryExportComponent.auditAfterCommit(() -> auditService.record("SCHEDULE_AUTO", "SCHEDULE", null,'
assert src.count(old_audit) == 1, "auditAfterCommit 调用点数量异常: %d" % src.count(old_audit)
src = src.replace(old_audit, new_audit)

# ---------- 3) 字段 ----------
field_anchor = "    private final SchedulePlacementComponent placementComponent;\r\n"
field_new = field_anchor + "    private final ScheduleQueryExportComponent queryExportComponent;\r\n"
assert src.count(field_anchor) == 1, "字段 anchor 异常"
src = src.replace(field_anchor, field_new)

# ---------- 4) 构造器接线 ----------
ctor_anchor = ("        this.placementComponent = new SchedulePlacementComponent(arrangementService, arrangementRepository, scheduleRepository, buildComponent);\r\n")
ctor_new = ctor_anchor + ("        this.queryExportComponent = new ScheduleQueryExportComponent(scheduleRepository, eventRepository, arrangementRepository, collaborationService, auditService);\r\n")
assert src.count(ctor_anchor) == 1, "构造器 anchor 异常"
src = src.replace(ctor_anchor, ctor_new)

with io.open(FACADE, "w", encoding="utf-8", newline="") as f:
    f.write(src)

print("OK: facade 已改写为 4 委派 + buildResult/auditAfterCommit 改名 + 字段/构造器接线")
