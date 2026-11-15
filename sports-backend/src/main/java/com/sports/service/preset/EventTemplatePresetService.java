package com.sports.service.preset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.preset.EventTemplatePreset;
import com.sports.repository.preset.EventTemplatePresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目预设模板方案：把一组预设项目命名保存为一套，支持多套切换。
 * 首次调用 list() 时若为空，自动铺一套「系统默认」模板（口径等同原 Events.vue 硬编码 21 条），
 * 保证任何环境（含全新库/CI）都有模板可用，且内置项不可改名/删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EventTemplatePresetService {

    private final EventTemplatePresetRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EventTemplatePreset p : repository.findAllIncludingDeleted()) {
            out.add(toMap(p));
        }
        return out;
    }

    /**
     * 首次访问幂等铺内置方案。放在 @Transactional 方法里由 Controller 首次调用触发，
     * 避免加启动初始化器（新建实体靠 ddl-auto:update 自动建表，启动时尚未建表会冲突）。
     */
    public List<Map<String, Object>> listWithDefaults() {
        if (repository.count() == 0) {
            try {
                EventTemplatePreset builtin = EventTemplatePreset.builder()
                        .name("系统默认")
                        .note("内置田径常用项目模板（跑步/跳跃/投掷/接力）")
                        .templatesJson(objectMapper.writeValueAsString(defaultTemplates()))
                        .sortOrder(0)
                        .builtin(true)
                        .build();
                repository.save(builtin);
            } catch (Exception e) {
                log.warn("铺内置项目模板失败：{}", e.getMessage());
            }
        }
        return list();
    }

    public Map<String, Object> save(String name, String note, List<Map<String, Object>> templates) {
        String trim = name == null ? "" : name.trim();
        if (trim.isEmpty()) throw new IllegalArgumentException("方案名不能为空");
        if (repository.findByName(trim).isPresent()) throw new IllegalArgumentException("方案名已存在：" + trim);
        EventTemplatePreset p = EventTemplatePreset.builder()
                .name(trim)
                .note(note)
                .templatesJson(writeJson(templates == null ? new ArrayList<>() : templates))
                .sortOrder((int) (repository.count() + 1))
                .builtin(false)
                .build();
        return toMap(repository.save(p));
    }

    public Map<String, Object> update(Long id, String name, String note, List<Map<String, Object>> templates) {
        EventTemplatePreset p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在：" + id));
        if (Boolean.TRUE.equals(p.getBuiltin())) throw new IllegalArgumentException("内置方案不可修改");
        if (name != null && !name.trim().isEmpty()) {
            String trim = name.trim();
            repository.findByName(trim).filter(x -> !x.getId().equals(id))
                    .ifPresent(x -> { throw new IllegalArgumentException("方案名已存在：" + trim); });
            p.setName(trim);
        }
        if (note != null) p.setNote(note);
        if (templates != null) p.setTemplatesJson(writeJson(templates));
        return toMap(repository.save(p));
    }

    public void delete(Long id) {
        EventTemplatePreset p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在：" + id));
        if (Boolean.TRUE.equals(p.getBuiltin())) throw new IllegalArgumentException("内置方案不可删除");
        p.setDeletedAt(LocalDateTime.now());
        repository.save(p);
    }

    public int restore(Long id) {
        return repository.restore(id);
    }

    private Map<String, Object> toMap(EventTemplatePreset p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("note", p.getNote());
        m.put("templates", readJson(p.getTemplatesJson()));
        m.put("builtin", Boolean.TRUE.equals(p.getBuiltin()));
        m.put("sortOrder", p.getSortOrder());
        m.put("deleted", p.getDeletedAt() != null);
        return m;
    }

    private String writeJson(List<Map<String, Object>> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("模板序列化失败：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> readJson(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("模板解析失败，按空处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 与前端历史硬编码 21 条口径一致：跑步 10 + 跳跃 4 + 投掷 4 + 接力 3（含混合组） */
    private static List<Map<String, Object>> defaultTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();
        String[][] rows = {
                {"100米", "径赛", "男子组", "跑步类"}, {"100米", "径赛", "女子组", "跑步类"},
                {"200米", "径赛", "男子组", "跑步类"}, {"200米", "径赛", "女子组", "跑步类"},
                {"400米", "径赛", "男子组", "跑步类"}, {"400米", "径赛", "女子组", "跑步类"},
                {"800米", "径赛", "男子组", "跑步类"}, {"800米", "径赛", "女子组", "跑步类"},
                {"1500米", "径赛", "男子组", "跑步类"}, {"1500米", "径赛", "女子组", "跑步类"},
                {"跳高", "田赛", "男子组", "跳跃类"}, {"跳高", "田赛", "女子组", "跳跃类"},
                {"跳远", "田赛", "男子组", "跳跃类"}, {"跳远", "田赛", "女子组", "跳跃类"},
                {"铅球", "田赛", "男子组", "投掷类"}, {"铅球", "田赛", "女子组", "投掷类"},
                {"实心球", "田赛", "男子组", "投掷类"}, {"实心球", "田赛", "女子组", "投掷类"},
                {"4×100米接力", "径赛", "男子组", "接力类"}, {"4×100米接力", "径赛", "女子组", "接力类"},
                {"4×100米接力", "径赛", "混合组", "接力类"}
        };
        for (String[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r[0]);
            m.put("eventType", r[1]);
            m.put("gender", r[2]);
            m.put("category", r[3]);
            list.add(m);
        }
        return list;
    }
}
