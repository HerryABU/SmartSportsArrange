package com.sports.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.SystemConfig;
import com.sports.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SystemService {

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 获取全部配置 */
    @Transactional(readOnly = true)
    public Map<String, Object> getAllConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<SystemConfig> configs = systemConfigRepository.findAll();
        for (SystemConfig c : configs) {
            String val = c.getConfigValue();
            if (val != null && (val.startsWith("{") || val.startsWith("["))) {
                try {
                    result.put(c.getConfigKey(), objectMapper.readValue(val, Object.class));
                } catch (Exception e) {
                    result.put(c.getConfigKey(), val);
                }
            } else {
                result.put(c.getConfigKey(), parseValue(val));
            }
        }
        return result;
    }

    public SystemConfig getConfig(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .orElseThrow(() -> new RuntimeException("配置项不存在: " + key));
    }

    public SystemConfig updateConfig(String key, Map<String, Object> body) {
        SystemConfig config = systemConfigRepository.findByConfigKey(key)
                .orElse(SystemConfig.builder().configKey(key).build());
        if (body.containsKey("configValue")) {
            Object v = body.get("configValue");
            config.setConfigValue(v instanceof String ? (String) v : v.toString());
        }
        if (body.containsKey("description")) {
            config.setDescription((String) body.get("description"));
        }
        config.setUpdatedAt(LocalDateTime.now());
        return systemConfigRepository.save(config);
    }

    /** 保存基本设置 */
    public void saveBasic(Map<String, Object> body) {
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            String strVal = val instanceof String ? (String) val : val.toString();
            SystemConfig config = systemConfigRepository.findByConfigKey(key)
                    .orElse(SystemConfig.builder().configKey(key).build());
            config.setConfigValue(strVal);
            config.setUpdatedAt(LocalDateTime.now());
            systemConfigRepository.save(config);
        }
    }

    /** 保存积分规则 */
    public void saveScoring(Map<String, Object> body) {
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            String strVal;
            try {
                strVal = objectMapper.writeValueAsString(val);
            } catch (Exception e) {
                strVal = val.toString();
            }
            SystemConfig config = systemConfigRepository.findByConfigKey(key)
                    .orElse(SystemConfig.builder().configKey(key).build());
            config.setConfigValue(strVal);
            config.setUpdatedAt(LocalDateTime.now());
            systemConfigRepository.save(config);
        }
    }

    /** 获取年级列表 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGrades() {
        SystemConfig config = systemConfigRepository.findByConfigKey("grades")
                .orElse(null);
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
    public Map<String, Object> addGrade(Map<String, Object> body) {
        List<Map<String, Object>> grades = getGrades();
        long maxId = grades.stream().mapToLong(g -> ((Number) g.getOrDefault("id", 0L)).longValue()).max().orElse(0);
        body.put("id", maxId + 1);
        grades.add(body);
        saveGrades(grades);
        return body;
    }

    /** 编辑年级 */
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
        config.setUpdatedAt(LocalDateTime.now());
        systemConfigRepository.save(config);
    }

    private List<Map<String, Object>> getDefaultGrades() {
        return new ArrayList<>(List.of(
                Map.of("id", 1L, "name", "高一年级", "sortOrder", 1),
                Map.of("id", 2L, "name", "高二年级", "sortOrder", 2),
                Map.of("id", 3L, "name", "高三年级", "sortOrder", 3)
        ));
    }

    public Map<String, Object> getRecentLogs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logs", List.of());
        result.put("total", 0);
        return result;
    }

    private Object parseValue(String val) {
        if (val == null) return null;
        if ("true".equalsIgnoreCase(val)) return true;
        if ("false".equalsIgnoreCase(val)) return false;
        try { return Integer.parseInt(val); } catch (NumberFormatException e1) { /* ignore */ }
        try { return Double.parseDouble(val); } catch (NumberFormatException e2) { /* ignore */ }
        return val;
    }
}
