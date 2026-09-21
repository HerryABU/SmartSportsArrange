package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 学生端控制器（M1 修复：业务逻辑下沉至 StudentService，本类只做路由委托）。
 */
@Slf4j
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    // ===== 首页 =====
    @GetMapping("/home")
    public ApiResponse<?> home() {
        return studentService.home();
    }

    // ===== 项目浏览 =====
    @GetMapping("/events")
    public ApiResponse<?> events() {
        return studentService.events();
    }

    // ===== 我的赛程 =====
    @GetMapping("/schedule")
    public ApiResponse<?> schedule() {
        return studentService.schedule();
    }

    // ===== 我的成绩 =====
    @GetMapping("/results")
    public ApiResponse<?> results() {
        return studentService.results();
    }
}
