package com.sports.controller.student;

import com.sports.common.web.ApiResponse;
import com.sports.service.student.ClassTeacherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * 班主任端控制器（M1 修复：业务逻辑下沉至 ClassTeacherService，本类只做参数校验 + 路由委托）。
 * 班主任只能操作自己绑定的班级（校验在 Service 层完成）。
 */
@Slf4j
@RestController
@RequestMapping("/api/class-teacher")
@RequiredArgsConstructor
public class ClassTeacherController {

    private final ClassTeacherService classTeacherService;

    // ===== 导入全班名单 Excel =====
    @PostMapping("/import-roster")
    public ApiResponse<?> importRoster(@RequestParam MultipartFile file,
                                        @RequestParam(required = false) Long classId) {
        return classTeacherService.importRoster(file, classId);
    }

    // ===== 手动添加单个运动员 =====
    @PostMapping("/athlete")
    public ApiResponse<?> addAthlete(@RequestBody Map<String, Object> body) {
        return classTeacherService.addAthlete(body);
    }

    // ===== 获取本班运动员列表 =====
    @GetMapping("/athletes")
    public ApiResponse<?> athletes(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return classTeacherService.athletes(page, size);
    }

    // ===== 仪表盘 =====
    @GetMapping("/dashboard")
    public ApiResponse<?> dashboard() {
        return classTeacherService.dashboard();
    }

    // ===== 为运动员报名项目 =====
    @PostMapping("/register")
    public ApiResponse<?> registerAthlete(@RequestBody Map<String, Object> body) {
        return classTeacherService.registerAthlete(body);
    }

    // ===== 取消报名 =====
    @DeleteMapping("/register/{id}")
    public ApiResponse<?> cancelRegistration(@PathVariable Long id) {
        return classTeacherService.cancelRegistration(id);
    }

    // ===== 报名列表 =====
    @GetMapping("/registrations")
    public ApiResponse<?> registrations() {
        return classTeacherService.registrations();
    }

    // ===== 导出报名表 =====
    @GetMapping("/registrations/export")
    public void exportRegistrations(HttpServletResponse response) throws IOException {
        classTeacherService.exportRegistrations(response);
    }

    // ===== 赛程查看 =====
    @GetMapping("/schedule")
    public ApiResponse<?> schedule() {
        return classTeacherService.schedule();
    }

    // ===== 成绩查看 =====
    @GetMapping("/results")
    public ApiResponse<?> results() {
        return classTeacherService.results();
    }

    // ===== 可用项目列表（供班主任报名使用） =====
    @GetMapping("/events")
    public ApiResponse<?> events() {
        return classTeacherService.events();
    }
}
