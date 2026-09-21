package com.sports.service;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.Referee;
import com.sports.entity.User;
import com.sports.repository.RefereeRepository;
import com.sports.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 裁判花名册服务：CRUD + Excel 批量导入/模板下载。
 * <p>导入单元格中「专长项目」支持 [a,b，c] 写法（中英文逗号混合），统一落库为标准 JSON 数组字符串。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RefereeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RefereeRepository refereeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 裁判账号默认密码（可配置：sports.default-password，缺省 123456） */
    @Value("${sports.default-password:123456}")
    private String defaultPassword;
    /** 裁判登录账号角色 */
    private static final String REFEREE_ROLE = "ROLE_REFEREE";

    // ==================== 裁判登录账号 ====================

    /**
     * 为某裁判开通登录账号（角色 ROLE_REFEREE）并回填 {@code referee.userId}。
     * <p>body 可选：username（默认取手机号，其次 ref{id}）、password（默认 123456）。已开通则幂等返回。</p>
     */
    public Map<String, Object> openAccount(Long refereeId, Map<String, Object> body) {
        Referee referee = refereeRepository.findById(refereeId)
                .orElseThrow(() -> new RuntimeException("裁判不存在: " + refereeId));

        String password = body != null && body.get("password") != null
                ? String.valueOf(body.get("password")).trim() : defaultPassword;
        if (password.isBlank()) password = defaultPassword;

        String requested = body != null && body.get("username") != null
                ? String.valueOf(body.get("username")).trim() : null;
        String username = requested != null && !requested.isBlank()
                ? requested
                : (referee.getPhone() != null && !referee.getPhone().isBlank()
                        ? referee.getPhone().trim()
                        : "ref" + referee.getId());

        Map<String, Object> res = new LinkedHashMap<>();
        // 已关联账号：幂等返回（不改密码）
        if (referee.getUserId() != null) {
            Optional<User> u = userRepository.findById(referee.getUserId());
            if (u.isPresent()) {
                res.put("refereeId", referee.getId());
                res.put("name", referee.getName());
                res.put("userId", u.get().getId());
                res.put("username", u.get().getUsername());
                res.put("created", false);
                res.put("message", "该裁判已开通账号");
                return res;
            }
        }
        // 用户名冲突时自动追加裁判 id 去重
        if (userRepository.existsByUsername(username)) {
            username = username + "_" + referee.getId();
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(REFEREE_ROLE);
        user.setName(referee.getName());
        user.setPhone(referee.getPhone());
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        user = userRepository.save(user);

        referee.setUserId(user.getId());
        referee.setUpdatedAt(LocalDateTime.now());
        refereeRepository.save(referee);

        res.put("refereeId", referee.getId());
        res.put("name", referee.getName());
        res.put("userId", user.getId());
        res.put("username", user.getUsername());
        res.put("password", password);
        res.put("created", true);
        log.info("为裁判开通账号成功: {} -> {}", referee.getName(), username);
        return res;
    }

    /** 批量为尚未开通账号的裁判开通账号（用户名取手机号，其次 ref{id}；默认密码 123456） */
    public Map<String, Object> openAccountsForAll() {
        List<Map<String, Object>> accounts = new ArrayList<>();
        int created = 0;
        for (Referee r : refereeRepository.findAll()) {
            if (r.getUserId() != null) continue;
            try {
                Map<String, Object> a = openAccount(r.getId(), Map.of());
                if (Boolean.TRUE.equals(a.get("created"))) {
                    accounts.add(a);
                    created++;
                }
            } catch (Exception e) {
                log.warn("裁判 {} 开通账号失败: {}", r.getName(), e.getMessage());
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("created", created);
        res.put("accounts", accounts);
        return res;
    }

    /** 列出所有未删除裁判 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return refereeRepository.findAll().stream().map(this::toMap).collect(Collectors.toList());
    }

    /** 根据 ID 获取裁判详情 */
    @Transactional(readOnly = true)
    public Map<String, Object> getById(Long id) {
        Referee r = refereeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("裁判不存在: " + id));
        return toMap(r);
    }

    /** 创建裁判 */
    public Map<String, Object> create(Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new RuntimeException("裁判姓名不能为空");
        if (refereeRepository.existsByName(name.trim())) throw new RuntimeException("裁判已存在: " + name);

        Referee referee = new Referee();
        referee.setName(name.trim());
        referee.setPhone((String) body.get("phone"));
        referee.setSpecialties(resolveSpecialties(body.get("specialties")));
        referee.setStatus("active");
        referee.setCreatedAt(LocalDateTime.now());
        referee.setUpdatedAt(LocalDateTime.now());

        referee = refereeRepository.save(referee);
        log.info("创建裁判成功: {}", referee.getName());
        return toMap(referee);
    }

    /** 更新裁判 */
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        Referee referee = refereeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("裁判不存在: " + id));

        if (body.containsKey("name")) referee.setName(((String) body.get("name")).trim());
        if (body.containsKey("phone")) referee.setPhone((String) body.get("phone"));
        if (body.containsKey("specialties")) referee.setSpecialties(resolveSpecialties(body.get("specialties")));
        if (body.containsKey("status")) referee.setStatus((String) body.get("status"));
        referee.setUpdatedAt(LocalDateTime.now());

        referee = refereeRepository.save(referee);
        log.info("更新裁判成功: {}", referee.getName());
        return toMap(referee);
    }

    /** 删除裁判（软删除） */
    public void delete(Long id) {
        Referee referee = refereeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("裁判不存在: " + id));
        referee.setDeletedAt(LocalDateTime.now());
        refereeRepository.save(referee);
        log.info("删除裁判成功: {}", referee.getName());
    }

    /** 从 Excel 批量导入裁判（按姓名 upsert） */
    public Map<String, Object> importReferees(MultipartFile file) {
        log.info("从 Excel 导入裁判: {}", file.getOriginalFilename());
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        try {
            List<Map<Integer, String>> rows = EasyExcel.read(file.getInputStream())
                    .sheet().doReadSync();
            int rowNum = 1;
            for (Map<Integer, String> row : rows) {
                rowNum++;
                try {
                    String name = row.getOrDefault(0, "");
                    String phone = row.getOrDefault(1, "");
                    String specialties = row.getOrDefault(2, "");

                    if (name.isBlank()) continue; // 跳过空行/表头

                    Optional<Referee> existingOpt = refereeRepository.findByName(name.trim());
                    if (existingOpt.isPresent()) {
                        Referee existing = existingOpt.get();
                        if (!phone.isBlank()) existing.setPhone(phone.trim());
                        if (!specialties.isBlank()) existing.setSpecialties(normalizeSpecialties(specialties));
                        existing.setUpdatedAt(LocalDateTime.now());
                        refereeRepository.save(existing);
                    } else {
                        Referee referee = new Referee();
                        referee.setName(name.trim());
                        referee.setPhone(phone.isBlank() ? null : phone.trim());
                        referee.setSpecialties(specialties.isBlank() ? null : normalizeSpecialties(specialties));
                        referee.setStatus("active");
                        referee.setCreatedAt(LocalDateTime.now());
                        referee.setUpdatedAt(LocalDateTime.now());
                        refereeRepository.save(referee);
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
            throw new RuntimeException("读取 Excel 文件失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", success + errors.size());
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 下载裁判导入模板 */
    public void downloadTemplate(HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            String fileName = URLEncoder.encode("裁判导入模板", "UTF-8").replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");

            List<List<String>> head = new ArrayList<>();
            head.add(List.of("姓名"));
            head.add(List.of("电话"));
            head.add(List.of("专长项目（可选，如 [立定跳远,拔河]，支持中文逗号）"));

            EasyExcel.write(response.getOutputStream())
                    .head(head)
                    .sheet("裁判导入模板")
                    .doWrite(Collections.emptyList());
        } catch (IOException e) {
            throw new RuntimeException("生成裁判模板失败: " + e.getMessage());
        }
    }

    // ==================== 专长项目（JSON 列表）解析 ====================

    /**
     * 将前端传入的 specialties 统一解析为标准 JSON 数组字符串。
     * 支持三种输入：
     * <ul>
     *   <li>List&lt;String&gt;（前端数组）→ 直接序列化</li>
     *   <li>已是 JSON 数组字符串（如 ["a","b"]）→ 保留</li>
     *   <li>[a,b，c] 列表语法（中英文逗号混合）→ 归一化为标准 JSON</li>
     * </ul>
     * 空/无效输入返回 null。
     */
    private String resolveSpecialties(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            List<String> items = list.stream()
                    .map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
            return items.isEmpty() ? null : writeJson(items);
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : normalizeSpecialties(s);
        }
        return null;
    }

    /**
     * 将 [a,b，c] 或 a,b，c 写法归一化为标准 JSON 数组字符串 ["a","b","c"]。
     * 兼容中英文逗号与方括号包裹。
     */
    private String normalizeSpecialties(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // 去外层方括号
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 中英文逗号混合切分
        List<String> items = Arrays.stream(s.split("[,，]"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
        return items.isEmpty() ? null : writeJson(items);
    }

    private String writeJson(List<String> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (IOException e) {
            throw new RuntimeException("序列化专长项目失败: " + e.getMessage());
        }
    }

    /** 将 JSON 数组字符串解析为 List（用于接口输出） */
    private List<String> parseSpecialties(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> toMap(Referee r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("name", r.getName());
        map.put("phone", r.getPhone());
        List<String> specs = parseSpecialties(r.getSpecialties());
        map.put("specialties", specs);
        map.put("specialtiesText", String.join("，", specs));
        map.put("status", "active".equals(r.getStatus()) ? "ACTIVE" : r.getStatus().toUpperCase());
        map.put("userId", r.getUserId());
        map.put("hasAccount", r.getUserId() != null);
        map.put("createdAt", r.getCreatedAt());
        map.put("updatedAt", r.getUpdatedAt());
        return map;
    }
}
