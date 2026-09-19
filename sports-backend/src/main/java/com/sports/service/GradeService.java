package com.sports.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.SystemConfig;
import com.sports.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 年级管理（从 {@code SystemService} 拆出，M1）。
 *
 * <p>年级以 JSON 数组存于 {@code system_config(key="grades")} 中。该职责与系统其余配置
 * （编排规则 / 积分规则 / 应用运行配置 / 运动会日程）无耦合，只是被「运动会日程」的
 * {@code gradeOrder} 反向引用（按年级 sortOrder 推导出场顺序）。因此本类对外仍暴露
 * {@link #getGradeOrder()}，由 {@code SystemService} 委托调用，公开签名不变。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeService {

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;

    /** 获取年级列表 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGrades() {
        SystemConfig config = systemConfigRepository.findByConfigKey("grades").orElse(null);
        if (config == null || config.getConfigValue() == null) {
            return getDefaultGrades();
        }
        try {
            return objectMapper.readValue(config.getConfigValue(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return getDefaultGrades();
        }
    }

    /** 新增年级 */
    @Transactional
    public Map<String, Object> addGrade(Map<String, Object> body) {
        List<Map<String, Object>> grades = getGrades();
        long maxId = grades.stream().mapToLong(g -> ((Number) g.getOrDefault("id", 0L)).longValue()).max().orElse(0);
        body.put("id", maxId + 1);
        grades.add(body);
        saveGrades(grades);
        return body;
    }

    /** 编辑年级 */
    @Transactional
    public Map<String, Object> editGrade(Long id, Map<String, Object> body) {
        List<Map<String, Object>> grades = getGrades();
        for (Map<String, Object> g : grades) {
            if (id.equals(((Number) g.get("id")).longValue())) {
                g.putAll(body);
                saveGrades(grades);
                return g;
            }
        }
        throw new RuntimeException("年级不存在: " + id);
    }

    /** 删除年级 */
    @Transactional
    public void deleteGrade(Long id) {
        List<Map<String, Object>> grades = getGrades();
        grades.removeIf(g -> id.equals(((Number) g.get("id")).longValue()));
        saveGrades(grades);
    }

    private void saveGrades(List<Map<String, Object>> grades) {
        SystemConfig config = systemConfigRepository.findByConfigKey("grades")
                .orElse(SystemConfig.builder().configKey("grades").build());
        try {
            config.setConfigValue(objectMapper.writeValueAsString(grades));
        } catch (Exception e) {
            log.error("保存年级失败", e);
        }
        config.setUpdatedAt(java.time.LocalDateTime.now());
        systemConfigRepository.save(config);
    }

    private List<Map<String, Object>> getDefaultGrades() {
        return new ArrayList<>(List.of(
                Map.of("id", 1L, "name", "高一年级", "sortOrder", 1),
                Map.of("id", 2L, "name", "高二年级", "sortOrder", 2),
                Map.of("id", 3L, "name", "高三年级", "sortOrder", 3)
        ));
    }

    /**
     * 年级出场顺序（名称列表）。按 grades 配置的 sortOrder 升序，
     * 未配置 sortOrder 时保持录入顺序 —— 管理员可在「年级管理」中自由调整，不硬编码。
     */
    @Transactional(readOnly = true)
    public List<String> getGradeOrder() {
        List<Map<String, Object>> grades = getGrades();
        List<Map<String, Object>> sorted = new ArrayList<>(grades);
        sorted.sort(Comparator.comparingInt(g -> intOf(g.get("sortOrder"), Integer.MAX_VALUE)));
        List<String> names = new ArrayList<>();
        for (Map<String, Object> g : sorted) {
            String n = strOf(g.get("name"), null);
            if (n != null && !n.isBlank()) names.add(n);
        }
        return names;
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    private static String strOf(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }
}
