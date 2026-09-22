package com.sports.schedule.rule.inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.system.SystemConfig;
import com.sports.repository.system.SystemConfigRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 规则脚本持久化（形态一）。
 *
 * <p>以系统配置键 {@code rule_scripts} 存放 JSON 数组，与「编排规则 / 积分规则」同源（system_config），
 * 便于随其它配置一起备份与迁移。</p>
 *
 * <p>健壮性：读取时若配置缺失或 JSON 解析失败 → 返回空列表（不抛异常），保证编排流程不因脏配置中断。</p>
 */
@Component
public class RuleScriptStore {

    /** 存储键（system_config.configKey）。 */
    public static final String CONFIG_KEY = "rule_scripts";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SystemConfigRepository repository;

    public RuleScriptStore(SystemConfigRepository repository) {
        this.repository = repository;
    }

    /** 读取全部规则脚本。 */
    public List<RuleScript> load() {
        Optional<SystemConfig> cfg = repository.findByConfigKey(CONFIG_KEY);
        if (cfg.isEmpty() || cfg.get().getConfigValue() == null || cfg.get().getConfigValue().isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<RuleScript> list = MAPPER.readValue(cfg.get().getConfigValue(),
                    new TypeReference<List<RuleScript>>() {
                    });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 覆盖保存全部规则脚本。 */
    public void save(List<RuleScript> scripts) {
        String json;
        try {
            json = MAPPER.writeValueAsString(scripts == null ? List.of() : scripts);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则脚本序列化失败: " + e.getMessage());
        }
        SystemConfig cfg = repository.findByConfigKey(CONFIG_KEY).orElseGet(() -> SystemConfig.builder()
                .configKey(CONFIG_KEY)
                .configType("arrange")
                .description("编排规则脚本（ 形态一：规则注入）")
                .build());
        cfg.setConfigValue(json);
        repository.save(cfg);
    }
}
