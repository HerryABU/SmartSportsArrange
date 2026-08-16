package com.sports.service;

import com.alibaba.excel.EasyExcel;
import com.sports.entity.User;
import com.sports.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ExcelService excelService;

    /** 列出所有用户 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return userRepository.findAll().stream().map(this::toMap).collect(Collectors.toList());
    }

    /** 根据ID获取用户 */
    @Transactional(readOnly = true)
    public Map<String, Object> getById(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + id));
        return toMap(u);
    }

    /** 创建用户 */
    public Map<String, Object> create(Map<String, Object> body) {
        String username = (String) body.get("username");
        if (username == null || username.isBlank()) throw new RuntimeException("用户名不能为空");
        if (userRepository.existsByUsername(username)) throw new RuntimeException("用户名已存在: " + username);

        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(
                body.get("password") != null ? (String) body.get("password") : "123456"));
        user.setName((String) body.getOrDefault("realName", body.get("name")));
        user.setPhone((String) body.get("phone"));
        user.setRole(mapRole((String) body.getOrDefault("role", "TEACHER")));
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        user = userRepository.save(user);
        log.info("创建用户成功: {}", user.getUsername());
        return toMap(user);
    }

    /** 更新用户 */
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + id));

        if (body.containsKey("username")) user.setUsername((String) body.get("username"));
        if (body.containsKey("realName")) user.setName((String) body.get("realName"));
        if (body.containsKey("phone")) user.setPhone((String) body.get("phone"));
        if (body.containsKey("role")) user.setRole(mapRole((String) body.get("role")));
        if (body.containsKey("status")) user.setStatus((String) body.get("status"));
        if (body.containsKey("password") && body.get("password") != null
                && !((String) body.get("password")).isBlank()) {
            user.setPassword(passwordEncoder.encode((String) body.get("password")));
        }
        user.setUpdatedAt(LocalDateTime.now());

        user = userRepository.save(user);
        log.info("更新用户成功: {}", user.getUsername());
        return toMap(user);
    }

    /** 删除用户（软删除） */
    public void delete(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + id));
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("删除用户成功: {}", user.getUsername());
    }

    /** 重置密码 */
    public void resetPassword(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + id));
        user.setPassword(passwordEncoder.encode("123456"));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("重置密码成功: {}", user.getUsername());
    }

    /** 从Excel导入用户 */
    public Map<String, Object> importUsers(MultipartFile file) {
        log.info("从Excel导入用户: {}", file.getOriginalFilename());
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        try {
            List<Map<Integer, String>> rows = EasyExcel.read(file.getInputStream())
                    .sheet().doReadSync();
            int rowNum = 1;
            for (Map<Integer, String> row : rows) {
                rowNum++;
                try {
                    String username = row.getOrDefault(0, "");
                    String password = row.getOrDefault(1, "123456");
                    String realName = row.getOrDefault(2, "");
                    String role = row.getOrDefault(3, "TEACHER");
                    String phone = row.getOrDefault(4, "");

                    if (username.isBlank()) continue;

                    if (userRepository.existsByUsername(username.trim())) {
                        // 更新已有用户
                        User existing = userRepository.findByUsername(username.trim()).get();
                        if (!realName.isBlank()) existing.setName(realName.trim());
                        if (!phone.isBlank()) existing.setPhone(phone.trim());
                        if (!role.isBlank()) existing.setRole(mapRole(role.trim()));
                        existing.setUpdatedAt(LocalDateTime.now());
                        userRepository.save(existing);
                    } else {
                        User user = new User();
                        user.setUsername(username.trim());
                        user.setPassword(passwordEncoder.encode(password.isBlank() ? "123456" : password.trim()));
                        user.setName(realName.isBlank() ? null : realName.trim());
                        user.setRole(mapRole(role.isBlank() ? "TEACHER" : role.trim()));
                        user.setPhone(phone.isBlank() ? null : phone.trim());
                        user.setStatus("active");
                        user.setCreatedAt(LocalDateTime.now());
                        user.setUpdatedAt(LocalDateTime.now());
                        userRepository.save(user);
                    }
                    success++;
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", rowNum);
                    err.put("message", e.getMessage());
                    errors.add(err);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", success + errors.size());
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 下载用户导入模板 */
    public void downloadTemplate(HttpServletResponse response) {
        excelService.getTemplate("user", response);
    }

    /** 批量创建用户 */
    public Map<String, Object> batchCreate(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> users = (List<Map<String, String>>) body.get("users");
        int created = 0;
        List<String> skipped = new ArrayList<>();

        for (Map<String, String> u : users) {
            String username = u.get("username");
            if (username == null || username.isBlank()) continue;
            if (userRepository.existsByUsername(username.trim())) {
                skipped.add(username);
                continue;
            }
            User user = new User();
            user.setUsername(username.trim());
            user.setPassword(passwordEncoder.encode(
                    u.get("password") != null ? u.get("password") : "123456"));
            user.setName(u.getOrDefault("realName", u.get("name")));
            user.setRole(mapRole(u.getOrDefault("role", "TEACHER")));
            user.setPhone(u.get("phone"));
            user.setStatus("active");
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            created++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped.size());
        result.put("skippedList", skipped);
        log.info("批量创建用户: 创建{}个, 跳过{}个", created, skipped.size());
        return result;
    }

    /** 前端角色 → 后端角色映射 */
    private String mapRole(String role) {
        if (role == null) return "ROLE_TEACHER";
        return switch (role.toUpperCase()) {
            case "ADMIN", "SUPER_ADMIN" -> "ROLE_SUPER_ADMIN";
            case "TEACHER" -> "ROLE_TEACHER";
            case "CLASS_TEACHER" -> "ROLE_CLASS_TEACHER";
            case "STUDENT" -> "ROLE_STUDENT";
            default -> role.startsWith("ROLE_") ? role : "ROLE_" + role;
        };
    }

    /** 后端角色 → 前端角色映射 */
    private String unmapRole(String role) {
        if (role == null) return "TEACHER";
        return switch (role) {
            case "ROLE_SUPER_ADMIN" -> "ADMIN";
            case "ROLE_TEACHER" -> "TEACHER";
            case "ROLE_CLASS_TEACHER" -> "CLASS_TEACHER";
            case "ROLE_STUDENT" -> "STUDENT";
            default -> role.replace("ROLE_", "");
        };
    }

    private Map<String, Object> toMap(User u) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", u.getId());
        map.put("username", u.getUsername());
        map.put("realName", u.getName());
        map.put("role", unmapRole(u.getRole()));
        map.put("phone", u.getPhone());
        map.put("status", "active".equals(u.getStatus()) ? "ACTIVE" : u.getStatus().toUpperCase());
        map.put("lastLogin", u.getLastLogin());
        return map;
    }
}
