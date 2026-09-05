package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.NumberRuleService;
import com.sports.service.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;
    private final NumberRuleService numberRuleService;

    // ---- 配置 ----

    @GetMapping("/config")
    public ApiResponse<?> getAllConfig() {
        log.info("获取全部系统配置");
        return ApiResponse.success(systemService.getAllConfig());
    }

    @GetMapping("/config/{key}")
    public ApiResponse<?> getConfig(@PathVariable String key) {
        log.info("获取系统配置: key={}", key);
        return ApiResponse.success(systemService.getConfig(key));
    }

    @PutMapping("/config/{key}")
    public ApiResponse<?> updateConfig(@PathVariable String key, @RequestBody Map<String, Object> body) {
        log.info("更新系统配置: key={}", key);
        return ApiResponse.success("配置更新成功", systemService.updateConfig(key, body));
    }

    @PutMapping("/config/basic")
    public ApiResponse<?> saveBasic(@RequestBody Map<String, Object> body) {
        log.info("保存基本设置");
        systemService.saveBasic(body);
        return ApiResponse.success("基本设置保存成功", null);
    }

    @PutMapping("/config/scoring")
    public ApiResponse<?> saveScoring(@RequestBody Map<String, Object> body) {
        log.info("保存积分规则");
        systemService.saveScoring(body);
        return ApiResponse.success("积分规则保存成功", null);
    }

    // ---- 号码簿规则（完全自定义） ----

    @GetMapping("/number-rule")
    public ApiResponse<?> getNumberRule() {
        return ApiResponse.success(numberRuleService.getNumberRule());
    }

    @PutMapping("/number-rule")
    public ApiResponse<?> saveNumberRule(@RequestBody Map<String, Object> body) {
        log.info("保存号码簿规则: {}", body);
        return ApiResponse.success("号码簿规则保存成功", numberRuleService.saveNumberRule(body));
    }

    @PostMapping("/number-rule/preview")
    public ApiResponse<?> previewNumberRule(@RequestBody Map<String, Object> body) {
        String template = (String) body.getOrDefault("template", "{grade}{class}{seq:02d}");
        String gradeName = (String) body.getOrDefault("grade", "高一年级");
        String className = (String) body.getOrDefault("className", "高一1班");
        int seq = body.get("seq") instanceof Number n ? n.intValue() : 1;
        boolean autoPadZero = !"false".equals(String.valueOf(body.getOrDefault("auto_pad_zero", true)));
        String number = numberRuleService.preview(template, gradeName, className, seq, autoPadZero);
        return ApiResponse.success(Map.of("number", number));
    }

    /** 号码簿 · 按名单顺序重排（年级顺序 → 班级顺序 → 名单顺序，班级内序号从 1 重编） */
    @PostMapping("/number-rule/reassign")
    public ApiResponse<?> reassignNumbers(@RequestBody(required = false) Map<String, Object> body) {
        String grade = body != null && body.get("grade") != null
                ? body.get("grade").toString().trim() : "";
        log.info("号码簿按名单顺序重排: grade={}", grade);
        return ApiResponse.success("号码簿重排完成",
                numberRuleService.reassignNumbers(grade.isBlank() ? null : grade));
    }

    // ---- 编排规则（完全自定义） ----

    @GetMapping("/arrange-rule")
    public ApiResponse<?> getArrangeRule() {
        return ApiResponse.success(systemService.getArrangeRule());
    }

    @PutMapping("/arrange-rule")
    public ApiResponse<?> saveArrangeRule(@RequestBody Map<String, Object> body) {
        log.info("保存编排规则: {}", body);
        return ApiResponse.success("编排规则保存成功", systemService.saveArrangeRule(body));
    }

    // ---- 运动会日程配置（日期/时段/年级顺序/串行并行，全部可配置） ----

    @GetMapping("/meet-schedule")
    public ApiResponse<?> getMeetSchedule() {
        return ApiResponse.success(systemService.getMeetSchedule());
    }

    @PutMapping("/meet-schedule")
    public ApiResponse<?> saveMeetSchedule(@RequestBody Map<String, Object> body) {
        log.info("保存运动会日程配置: {}", body);
        return ApiResponse.success("运动会日程配置保存成功", systemService.saveMeetSchedule(body));
    }

    /** 年级出场顺序（按 sortOrder 升序，管理员可调） */
    @GetMapping("/grade-order")
    public ApiResponse<?> getGradeOrder() {
        return ApiResponse.success(systemService.getGradeOrder());
    }

    // ---- 积分规则（完全自定义） ----

    @GetMapping("/scoring-rule")
    public ApiResponse<?> getScoringRule() {
        return ApiResponse.success(systemService.getScoringRule());
    }

    @PutMapping("/scoring-rule")
    public ApiResponse<?> saveScoringRule(@RequestBody Map<String, Object> body) {
        log.info("保存积分规则: {}", body);
        return ApiResponse.success("积分规则保存成功", systemService.saveScoringRule(body));
    }

    // ---- 应用运行配置（服务端口，重启生效） ----

    @GetMapping("/app-config")
    public ApiResponse<?> getAppConfig() {
        return ApiResponse.success(systemService.getAppConfig());
    }

    @PutMapping("/app-config")
    public ApiResponse<?> saveAppConfig(@RequestBody Map<String, Object> body) {
        log.info("保存应用运行配置: {}", body);
        return ApiResponse.success("应用运行配置保存成功（重启后生效）", systemService.saveAppConfig(body));
    }

    // ---- 年级 ----

    @GetMapping("/grades")
    public ApiResponse<?> getGrades() {
        log.info("查询年级列表");
        return ApiResponse.success(systemService.getGrades());
    }

    @PostMapping("/grades")
    public ApiResponse<?> addGrade(@RequestBody Map<String, Object> body) {
        log.info("新增年级: {}", body.get("name"));
        return ApiResponse.success("新增成功", systemService.addGrade(body));
    }

    @PutMapping("/grades/{id}")
    public ApiResponse<?> editGrade(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        log.info("编辑年级: id={}", id);
        return ApiResponse.success("编辑成功", systemService.editGrade(id, body));
    }

    @DeleteMapping("/grades/{id}")
    public ApiResponse<?> deleteGrade(@PathVariable Long id) {
        log.info("删除年级: id={}", id);
        systemService.deleteGrade(id);
        return ApiResponse.success("删除成功", null);
    }

    // ---- 健康检查 ----

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of(
                "status", "UP",
                "service", "Sports Meet System",
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    /** 健康检查详情（管理员面板展示） */
    @GetMapping("/health-detail")
    public ApiResponse<?> healthDetail() {
        return ApiResponse.success(systemService.getHealthDetail());
    }

    @GetMapping("/logs")
    public ApiResponse<?> getLogs() {
        log.info("获取近期日志");
        return ApiResponse.success(systemService.getRecentLogs());
    }
}
