package com.sports.config;

import com.sports.repository.UserRepository;
import com.sports.service.SetupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器
 * 1. 从建站向导安装配置重建管理员/站点（MySQL 场景重启后生效）；
 * 2. 升级兼容：已有用户但无安装标记时补写标记（视为已安装）。
 * 全新安装时不再自动创建默认账号，由建站向导负责创建管理员。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final SetupService setupService;

    @Override
    public void run(String... args) {
        // 1. 从安装配置重建（MySQL 场景重启后）
        setupService.ensureInstalledData();

        // 2. 升级兼容：已有用户但无安装标记 → 补写标记（视为已安装，不触发向导）
        if (!setupService.isInstalled() && userRepository.count() > 0) {
            setupService.markInstalled();
            log.info("检测到已有用户数据，已补写安装标记");
        }
    }
}

