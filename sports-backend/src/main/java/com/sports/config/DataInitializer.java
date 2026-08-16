package com.sports.config;

import com.sports.entity.User;
import com.sports.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据初始化器 - 创建默认管理员账户
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() == 0) {
            log.info("=== 初始化默认用户 ===");

            // 超级管理员
            User admin = User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .role("ROLE_SUPER_ADMIN")
                    .name("系统管理员")
                    .phone("13800000000")
                    .status("active")
                    .lastLogin(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            userRepository.save(admin);
            log.info("超级管理员: admin / admin123");

            // 体育老师
            User teacher = User.builder()
                    .username("teacher")
                    .password(passwordEncoder.encode("teacher123"))
                    .role("ROLE_TEACHER")
                    .name("张老师")
                    .phone("13800000001")
                    .status("active")
                    .lastLogin(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            userRepository.save(teacher);
            log.info("体育老师: teacher / teacher123");

            // 班主任
            User classTeacher = User.builder()
                    .username("class_teacher")
                    .password(passwordEncoder.encode("class123"))
                    .role("ROLE_CLASS_TEACHER")
                    .name("李老师")
                    .phone("13800000002")
                    .status("active")
                    .lastLogin(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            userRepository.save(classTeacher);
            log.info("班主任: class_teacher / class123");

            // 学生
            User student = User.builder()
                    .username("student")
                    .password(passwordEncoder.encode("student123"))
                    .role("ROLE_STUDENT")
                    .name("王小明")
                    .status("active")
                    .lastLogin(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            userRepository.save(student);
            log.info("学生: student / student123");

            log.info("=== 初始化完成，共创建 " + userRepository.count() + " 个用户 ===");
        }
    }
}
