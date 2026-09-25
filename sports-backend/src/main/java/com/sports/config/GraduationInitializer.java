package com.sports.config;

import com.sports.service.meet.MeetMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动即按当前届年份重算毕业生标记（幂等）。
 *
 * <p>与 {@code MeetBackfillInitializer(@Order(36))} 衔接：默认届已就位后，立即把历史运动员的
 * {@code graduated} 标记校准到位，使「毕业生 / 进步榜」统计开箱即用。</p>
 */
@Slf4j
@Component
@Order(37)
@RequiredArgsConstructor
public class GraduationInitializer implements CommandLineRunner {

    private final MeetMaintenanceService meetMaintenanceService;

    @Override
    public void run(String... args) {
        meetMaintenanceService.recomputeGraduation();
    }
}
