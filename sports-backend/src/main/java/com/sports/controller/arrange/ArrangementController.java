package com.sports.controller.arrange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.common.web.ApiResponse;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.audit.AuditService;
import com.sports.service.arrange.ConflictService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.sports.common.util.ExportNaming;
import com.sports.schedule.rule.style.L1Rule;
import com.sports.service.system.SystemService;

/**
 * 编排控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/arrange")
@RequiredArgsConstructor
public class ArrangementController {

    private final ArrangementService arrangementService;
    private final ConflictService conflictService;
    private final AuditService auditService;
    private final com.sports.service.system.SystemService systemService;
    private final com.sports.schedule.rule.inject.RuleInjectionService ruleInjectionService;
    private final ObjectMapper objectMapper;

    @PostMapping("/events/{eventId}")
    public ApiResponse<?> executeArrangement(
            @PathVariable Long eventId,
            @RequestBody Map<String, Object> config) {
        log.info("执行编排: eventId={}, config={}", eventId, config);
        Object r = arrangementService.executeArrangement(eventId, config);
        auditService.record("ARRANGE", "EVENT", eventId, "执行编排: " + config);
        return ApiResponse.success("编排成功", r);
    }

    @PostMapping("/preview")
    public ApiResponse<?> previewArrangement(@RequestBody Map<String, Object> config) {
        log.info("预览编排: config={}", config);
        return ApiResponse.success(arrangementService.previewArrangement(config));
    }

    /** L1「自定义规则」可选款型目录（供前端「选择哪一款」动态渲染：id / 名称 / 说明） */
    @GetMapping("/l1-rules")
    public ApiResponse<?> l1Rules() {
        return ApiResponse.success(com.sports.schedule.rule.style.L1Rule.catalog());
    }

    // ==================== L1 规则注入（形态一：伪代码 / 脚本） ====================

    /** 规则脚本列表 + 各引擎可用性（内置伪代码必然可用；JSR-223 引擎缺失时提示引入依赖）。 */
    @GetMapping("/rule-scripts")
    public ApiResponse<?> listRuleScripts() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("scripts", ruleInjectionService.list());
        data.put("engines", ruleInjectionService.engines());
        return ApiResponse.success(data);
    }

    /** 覆盖保存规则脚本。body: {scripts:[{id,name,engine,enabled,source}]} */
    @PutMapping("/rule-scripts")
    public ApiResponse<?> saveRuleScripts(@RequestBody Map<String, Object> body) {
        Object raw = body.get("scripts");
        List<com.sports.schedule.rule.inject.RuleScript> scripts = raw == null
                ? List.of()
                : objectMapper.convertValue(raw, new com.fasterxml.jackson.core.type.TypeReference<
                        List<com.sports.schedule.rule.inject.RuleScript>>() {
                });
        List<com.sports.schedule.rule.inject.RuleScript> saved = ruleInjectionService.save(scripts);
        auditService.record("ARRANGE_RULE_SCRIPTS_SAVE", "SYSTEM", null, "保存规则脚本 " + saved.size() + " 条");
        log.info("保存规则脚本: {} 条", saved.size());
        return ApiResponse.success("规则脚本已保存", saved);
    }

    /** 试运行规则脚本（不落库）。body: {script:{...}, context:{...}} */
    @PostMapping("/rule-scripts/test")
    public ApiResponse<?> testRuleScript(@RequestBody Map<String, Object> body) {
        com.sports.schedule.rule.inject.RuleScript script = objectMapper.convertValue(
                body.getOrDefault("script", Map.of()), com.sports.schedule.rule.inject.RuleScript.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = body.get("context") instanceof Map
                ? (Map<String, Object>) body.get("context") : Map.of();
        return ApiResponse.success(ruleInjectionService.test(script,
                com.sports.schedule.rule.inject.RuleContext.of(ctx)));
    }

    @GetMapping("/events/{eventId}")
    public ApiResponse<?> viewArrangement(@PathVariable Long eventId) {
        log.info("查看编排结果: eventId={}", eventId);
        return ApiResponse.success(arrangementService.viewArrangement(eventId));
    }

    @PutMapping("/events/{eventId}")
    public ApiResponse<?> manualAdjust(
            @PathVariable Long eventId,
            @RequestBody List<Map<String, Object>> adjustments) {
        log.info("手动调整编排: eventId={}, adjustments={}", eventId, adjustments);
        Object r = arrangementService.manualAdjust(eventId, adjustments);
        // U16：人工调整必须留痕——这是最容易被后续自动重排「冲掉」的操作，没有审计就无法追责
        auditService.record("ARRANGE_MANUAL_ADJUST", "EVENT", eventId,
                "手动调整编排 " + (adjustments != null ? adjustments.size() : 0) + " 条: " + adjustments);
        return ApiResponse.success("调整成功", r);
    }

    /** U12/B18：锁定/解锁单条编排（锁定后自动重排不覆盖） */
    @PutMapping("/{arrangementId}/lock")
    public ApiResponse<?> setLock(@PathVariable Long arrangementId,
                                  @RequestParam(defaultValue = "true") boolean locked) {
        log.info("锁定/解锁编排: id={}, locked={}", arrangementId, locked);
        Object r = arrangementService.setLock(arrangementId, locked);
        auditService.record(locked ? "LOCK" : "UNLOCK", "ARRANGEMENT", arrangementId,
                locked ? "锁定编排项" : "解锁编排项");
        return ApiResponse.success(locked ? "已锁定" : "已解锁", r);
    }

    @DeleteMapping("/events/{eventId}")
    public ApiResponse<Void> clearArrangement(@PathVariable Long eventId) {
        log.info("清除编排: eventId={}", eventId);
        arrangementService.clearArrangement(eventId);
        // U16：清空编排是破坏性操作（人事后最难复原），必须留痕
        auditService.record("ARRANGE_CLEAR", "EVENT", eventId, "清空该项目的全部赛次编排");
        return ApiResponse.success("清除成功", null);
    }

    @PostMapping("/batch")
    public ApiResponse<?> batchArrange(@RequestBody List<Long> eventIds) {
        log.info("批量编排: eventIds={}", eventIds);
        Object r = arrangementService.batchArrange(eventIds);
        auditService.record("ARRANGE_BATCH", "EVENT", null,
                "批量编排 " + (eventIds != null ? eventIds.size() : 0) + " 个项目: " + eventIds + ", 结果=" + r);
        return ApiResponse.success("批量编排成功", r);
    }

    @PostMapping("/events/{eventId}/rollback")
    public ApiResponse<?> rollback(@PathVariable Long eventId) {
        log.info("回滚编排: eventId={}", eventId);
        Object r = arrangementService.rollback(eventId);
        auditService.record("ARRANGE_ROLLBACK", "EVENT", eventId, "回滚编排: " + r);
        return ApiResponse.success("回滚成功", r);
    }

    // ==================== 预赛淘汰（径赛 needHeats） ====================

    /** 生成预赛编排 */
    @PostMapping("/events/{eventId}/preliminary")
    public ApiResponse<?> generatePreliminary(@PathVariable Long eventId,
                                              @RequestBody Map<String, Object> config) {
        String grade = (String) config.get("grade");
        String gender = (String) config.get("gender");
        log.info("生成预赛编排: eventId={}, grade={}, gender={}", eventId, grade, gender);
        Object r = arrangementService.generatePreliminary(eventId, grade, gender);
        auditService.record("ARRANGE_PRELIMINARY", "EVENT", eventId,
                "生成预赛编排: grade=" + grade + ", gender=" + gender);
        return ApiResponse.success("预赛编排成功", r);
    }

    /** 录入预赛成绩 items: [{athleteId, time}] */
    @PostMapping("/events/{eventId}/prelim-results")
    public ApiResponse<?> savePrelimResults(@PathVariable Long eventId,
                                            @RequestBody Map<String, Object> body) {
        String grade = (String) body.get("grade");
        String gender = (String) body.get("gender");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        log.info("录入预赛成绩: eventId={}, grade={}, gender={}, {}条", eventId, grade, gender,
                items != null ? items.size() : 0);
        Object r = arrangementService.savePrelimResults(eventId, grade, gender,
                items != null ? items : List.of());
        // U16：预赛成绩是晋级计算的输入，改动会连带改变决赛名单，必须留痕
        auditService.record("PRELIM_RESULT_SAVE", "EVENT", eventId,
                "录入预赛成绩 " + (items != null ? items.size() : 0) + " 条: grade=" + grade + ", gender=" + gender);
        return ApiResponse.success("预赛成绩已保存", r);
    }

    /** 预赛淘汰「立刻计算」并生成决赛编排 */
    @PostMapping("/events/{eventId}/qualify")
    public ApiResponse<?> computeQualifiers(@PathVariable Long eventId,
                                            @RequestBody Map<String, Object> body) {
        String grade = (String) body.get("grade");
        String gender = (String) body.get("gender");
        Integer advanceCount = body.get("advanceCount") instanceof Number n
                ? n.intValue() : null;
        log.info("预赛淘汰计算: eventId={}, grade={}, gender={}, advanceCount={}",
                eventId, grade, gender, advanceCount);
        Object r = arrangementService.computeQualifiers(eventId, grade, gender, advanceCount);
        auditService.record("ARRANGE_QUALIFY", "EVENT", eventId,
                "二次编排/晋级计算: grade=" + grade + ", gender=" + gender + ", advanceCount=" + advanceCount
                        + ", 结果=" + r);
        return ApiResponse.success("晋级计算完成", r);
    }

    /** 查看晋级名单 */
    @GetMapping("/events/{eventId}/qualifiers")
    public ApiResponse<?> viewQualifiers(@PathVariable Long eventId,
                                         @RequestParam(required = false) String grade,
                                         @RequestParam(required = false) String gender) {
        log.info("查看晋级名单: eventId={}, grade={}, gender={}", eventId, grade, gender);
        return ApiResponse.success(arrangementService.viewQualifiers(eventId, grade, gender));
    }

    // ==================== 裁判分配（智能编排产出 + 手工调整） ====================

    /** 查看某项目全部组次裁判分配（含裁判姓名） */
    @GetMapping("/events/{eventId}/referees")
    public ApiResponse<?> viewReferees(@PathVariable Long eventId) {
        log.info("查看裁判分配: eventId={}", eventId);
        return ApiResponse.success(arrangementService.getRefereeAssignments(eventId));
    }

    /**
     * 手工调整某组次裁判。body: {grade, gender, round, heat, refereeIds:[id...]}
     * 重新执行编排（POST /events/{eventId}）会按「组次裁判数量」重新自动分配并覆盖此调整。
     */
    @PutMapping("/events/{eventId}/referees/heat")
    public ApiResponse<?> adjustHeatReferees(@PathVariable Long eventId,
                                             @RequestBody Map<String, Object> body) {
        String grade = (String) body.get("grade");
        String gender = (String) body.get("gender");
        String round = (String) body.get("round");
        int heat = body.get("heat") instanceof Number n ? n.intValue() : 0;
        @SuppressWarnings("unchecked")
        List<Long> refereeIds = (List<Long>) body.get("refereeIds");
        log.info("手工调整裁判: eventId={}, grade={}, gender={}, round={}, heat={}, ids={}",
                eventId, grade, gender, round, heat, refereeIds);
        Object r = arrangementService.updateHeatReferees(eventId, grade, gender, round, heat,
                refereeIds != null ? refereeIds : List.of());
        auditService.record("ARRANGE_REFEREE_ADJUST", "EVENT", eventId,
                "手工调整裁判: grade=" + grade + ", gender=" + gender + ", round=" + round
                        + ", heat=" + heat + ", ids=" + refereeIds);
        return ApiResponse.success("裁判调整成功", r);
    }

    @GetMapping("/events/{eventId}/export")
    public void exportLaneSheet(@PathVariable Long eventId, HttpServletResponse response) throws IOException {
        log.info("导出道次表: eventId={}", eventId);
        arrangementService.exportLaneSheet(eventId, response);
    }

    /**
     * 编排自检（对抗式校验）：独立读取已落库编排，核对硬约束，返回 {valid, violations, checks}。
     * 与编排引擎互为「生成 vs 校验」关系——可作为编排后的质量门禁或手动复检入口。
     */
    @GetMapping("/events/{eventId}/verify")
    public ApiResponse<?> verifyArrangement(@PathVariable Long eventId) {
        log.info("编排自检: eventId={}", eventId);
        return ApiResponse.success("自检完成", arrangementService.verifyArrangement(eventId));
    }

    // ==================== 裁判编排开关 ====================

    /** 是否启用裁判编排（关闭后编排不分配裁判，裁判池为空同样自动跳过） */
    @GetMapping("/referee-arrange-enabled")
    public ApiResponse<?> getRefereeArrangeEnabled() {
        return ApiResponse.success(Map.of("enabled", systemService.isRefereeArrangeEnabled()));
    }

    /** 设置是否启用裁判编排 */
    @PutMapping("/referee-arrange-enabled")
    public ApiResponse<?> setRefereeArrangeEnabled(@RequestBody Map<String, Object> body) {
        boolean enabled = body.get("enabled") != null && Boolean.TRUE.equals(body.get("enabled"));
        systemService.setRefereeArrangeEnabled(enabled);
        auditService.record("ARRANGE_REFEREE_TOGGLE", "SYSTEM", null, "裁判编排开关: " + enabled);
        log.info("裁判编排开关: {}", enabled);
        return ApiResponse.success(enabled ? "已启用裁判编排" : "已关闭裁判编排", Map.of("enabled", enabled));
    }

    // ==================== 裁判工作安排（裁判视图） ====================

    /** 裁判工作安排表：按裁判聚合其全部分配（含未分配裁判） */
    @GetMapping("/referee-board")
    public ApiResponse<?> refereeBoard() {
        return ApiResponse.success(arrangementService.getRefereeBoard());
    }

    // ==================== 预留模拟空位（项目级编排）+ 两阶段重排 ====================

    /** 查看某项目全部预留模拟空位 */
    @GetMapping("/events/{eventId}/reservations")
    public ApiResponse<?> listReservations(@PathVariable Long eventId) {
        return ApiResponse.success(arrangementService.listReservations(eventId));
    }

    /** 新增单个预留空位。body: {grade, gender, round, heat, lane, scheduledTime, note, kind} */
    @PostMapping("/events/{eventId}/reservations")
    public ApiResponse<?> addReservation(@PathVariable Long eventId,
                                         @RequestBody Map<String, Object> body) {
        log.info("新增预留空位: eventId={}, body={}", eventId, body);
        Object r = arrangementService.addReservation(eventId, body);
        auditService.record("ARRANGE_RESERVE_ADD", "EVENT", eventId, "新增预留模拟空位: " + body);
        return ApiResponse.success("已预留", r);
    }

    /** 便捷预留：为某组次自动预留 count 个空道。body: {grade, gender, round, heat, count, scheduledTime, note} */
    @PostMapping("/events/{eventId}/reservations/reserve")
    public ApiResponse<?> reserveSlots(@PathVariable Long eventId,
                                       @RequestBody Map<String, Object> body) {
        log.info("批量预留模拟空位: eventId={}, body={}", eventId, body);
        Object r = arrangementService.reserveSlots(eventId, body);
        auditService.record("ARRANGE_RESERVE_SLOTS", "EVENT", eventId, "批量预留模拟空位: " + body);
        return ApiResponse.success("预留成功", r);
    }

    /** 删除某条预留空位 */
    @DeleteMapping("/reservations/{id}")
    public ApiResponse<Void> deleteReservation(@PathVariable Long id) {
        log.info("删除预留空位: id={}", id);
        arrangementService.deleteReservation(id);
        auditService.record("ARRANGE_RESERVE_DEL", "RESERVATION", id, "删除预留模拟空位");
        return ApiResponse.success("已删除", null);
    }

    /**
     * 两阶段编排·第二阶段：全部预赛完成后一次性重排全部决赛。
     * 遍历 needHeats 项目 → 已录预赛成绩的 年级×性别 切片 → 重算晋级并生成决赛。
     */
    @PostMapping("/finals/rebuild-all")
    public ApiResponse<?> rebuildAllFinals() {
        log.info("全部预赛完成后重排全部决赛");
        Object r = arrangementService.rebuildAllFinals();
        auditService.record("ARRANGE_FINALS_REBUILD_ALL", "EVENT", null, "重排全部决赛: " + r);
        return ApiResponse.success("决赛重排完成", r);
    }

    /**
     * 全量编排导出（含预赛与决赛），作为 arrange_result.json 的程序化来源与统一数据源（B01/U01/B17）。
     * 二次编排后调用本接口即可得到含决赛的完整编排 JSON。
     */
    @GetMapping("/export-all")
    public void exportAllArrangement(HttpServletResponse response) throws IOException {
        log.info("导出全量编排(JSON,含决赛)");
        Map<String, Object> data = arrangementService.exportAllArrangement();
        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");
        // U11/B13：文件名带「阶段 + 版本 + 生成时间」；含决赛即二次编排后
        boolean afterSecond = data.get("finalRoundCount") instanceof Number n && n.intValue() > 0;
        String fileName = "arrange_result_" + com.sports.common.util.ExportNaming.stage(afterSecond)
                + "_v" + com.sports.common.util.ExportNaming.appVersion()
                + "_" + com.sports.common.util.ExportNaming.stamp() + ".json";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20"));
        objectMapper.writeValue(response.getWriter(), data);
    }

    /**
     * B06 / U05：兼项冲突检测。返回同一运动员在相近时间参加的不同项目冲突清单，
     * 附严重度分级、根因类型与可执行调整建议。
     */
    @GetMapping("/conflicts")
    public ApiResponse<?> conflicts() {
        log.info("查询兼项冲突");
        List<Map<String, Object>> list = conflictService.detectConflicts();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("summary", conflictService.summary(list));
        data.put("list", list);
        return ApiResponse.success("兼项冲突检测完成", data);
    }

    /**
     * B06 / U05：兼项冲突清单导出（Excel），供现场按建议调表。
     */
    @GetMapping("/conflicts/export")
    public void exportConflicts(HttpServletResponse response) throws IOException {
        log.info("导出兼项冲突清单");
        conflictService.exportConflicts(response);
    }
}
