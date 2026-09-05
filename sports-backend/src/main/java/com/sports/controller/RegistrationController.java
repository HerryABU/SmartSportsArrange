package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.Registration;
import com.sports.service.RegistrationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 报名管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/registrations")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    @GetMapping
    public ApiResponse<ApiResponse.PageData<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String status) {
        log.info("查询报名列表: page={}, size={}, eventId={}, classId={}, status={}",
                page, size, eventId, classId, status);
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Map<String, Object>> result = registrationService.list(pageable, eventId, classId, status);
        return ApiResponse.page(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Registration> getById(@PathVariable Long id) {
        log.info("查询报名详情: id={}", id);
        return ApiResponse.success(registrationService.getById(id));
    }

    @PostMapping
    public ApiResponse<Registration> create(@RequestBody @Valid Map<String, Long> request) {
        Long athleteId = request.get("athleteId");
        Long eventId = request.get("eventId");
        log.info("创建报名: athleteId={}, eventId={}", athleteId, eventId);
        return ApiResponse.success("报名成功", registrationService.create(athleteId, eventId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        log.info("取消报名: id={}", id);
        registrationService.cancel(id);
        return ApiResponse.success("取消成功", null);
    }

    @PostMapping("/batch")
    public ApiResponse<?> batchRegister(@RequestBody @Valid Map<String, List<Map<String, Long>>> request) {
        List<Map<String, Long>> items = request.get("items");
        if (items == null || items.isEmpty()) {
            return ApiResponse.error(400, "报名列表不能为空");
        }
        log.info("批量报名: count={}", items.size());
        return ApiResponse.success("批量报名成功", registrationService.batchRegister(items));
    }

    @PutMapping("/{id}/approve")
    public ApiResponse<Registration> approve(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {
        String remark = request != null ? request.get("remark") : null;
        log.info("审核报名: id={}, remark={}", id, remark);
        return ApiResponse.success("审核成功", registrationService.approve(id, remark));
    }

    @PutMapping("/{id}/reject")
    public ApiResponse<Registration> reject(@PathVariable Long id) {
        log.info("拒绝报名: id={}", id);
        return ApiResponse.success("已拒绝", registrationService.reject(id));
    }

    @PutMapping("/batch-approve")
    public ApiResponse<?> batchApprove(@RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        log.info("批量通过报名: count={}", ids != null ? ids.size() : 0);
        if (ids != null) {
            for (Long id : ids) registrationService.approve(id, null);
        }
        return ApiResponse.success("批量通过成功", null);
    }

    /** 一键全部通过（当前筛选范围：eventId/classId 可空，仅处理 pending） */
    @PutMapping("/approve-all")
    public ApiResponse<?> approveAll(@RequestBody(required = false) Map<String, Object> body) {
        Long eventId = numOf(body, "eventId");
        Long classId = numOf(body, "classId");
        log.info("一键全部通过: eventId={}, classId={}", eventId, classId);
        int approved = registrationService.approveAll(eventId, classId);
        return ApiResponse.success("全部通过成功", Map.of("approved", approved));
    }

    private Long numOf(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        Object v = body.get(key);
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    @PutMapping("/batch-reject")
    public ApiResponse<?> batchReject(@RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.get("ids");
        log.info("批量拒绝报名: count={}", ids != null ? ids.size() : 0);
        if (ids != null) {
            for (Long id : ids) registrationService.reject(id);
        }
        return ApiResponse.success("批量拒绝成功", null);
    }

    @GetMapping("/statistics")
    public ApiResponse<?> statistics() {
        log.info("获取报名统计");
        return ApiResponse.success(registrationService.statistics());
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        log.info("导出报名数据");
        registrationService.export(response);
    }

    /** 报名表（表格1）导入：班主任(现场/后置)或体育老师(后置)统一入口 */
    @PostMapping("/import-sheet")
    public ApiResponse<?> importSignupSheet(@RequestParam MultipartFile file,
                                            @RequestParam(defaultValue = "offline") String source) {
        log.info("导入报名表: file={}, source={}", file.getOriginalFilename(), source);
        return ApiResponse.success("导入完成", registrationService.importSignupSheet(file, source));
    }

    /** 报名表（表格1）导入模板 */
    @GetMapping("/template")
    public void signupTemplate(HttpServletResponse response) throws IOException {
        log.info("下载报名表模板");
        registrationService.exportSignupTemplate(response);
    }
}
