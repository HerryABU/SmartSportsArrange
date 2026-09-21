package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.service.ValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据一致性校验接口（U10 / B15）
 */
@Slf4j
@RestController
@RequestMapping("/api/validate")
@RequiredArgsConstructor
public class ValidationController {

    private final ValidationService validationService;

    @GetMapping("/report")
    public ApiResponse<?> report() {
        log.info("生成数据一致性校验报告");
        Map<String, Object> report = validationService.validateDataConsistency();
        return ApiResponse.success(report);
    }
}
