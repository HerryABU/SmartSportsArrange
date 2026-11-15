package com.sports.service.preset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.preset.ScheduleOrderPreset;
import com.sports.repository.preset.ScheduleOrderPresetRepository;
import com.sports.service.system.SystemService;
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
 * 编排顺序预设方案：命名保存当前日程配置（年级顺序/项目顺序/田赛分组/日期时段），
 * 之后可一键切换应用。快照存整份 meet_schedule JSON，应用时灌回 SystemService.saveMeetSchedule，
 * 复用其去重/裁剪/归一化逻辑，保证「预设」与「当前配置」是同一份口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleOrderPresetService {

    private final ScheduleOrderPresetRepository repository;
    private final SystemService systemService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScheduleOrderPreset p : repository.findAllIncludingDeleted()) {
            out.add(toMap(p));
        }
        return out;
    }

    /** 另存为一套方案；snapshot 为空时以当前 meet_schedule 配置为快照 */
    public Map<String, Object> save(String name, String note, Map<String, Object> snapshot) {
        String trim = name == null ? "" : name.trim();
        if (trim.isEmpty()) throw new IllegalArgumentException("方案名不能为空");
        if (repository.findByName(trim).isPresent()) throw new IllegalArgumentException("方案名已存在：" + trim);
        Map<String, Object> snap = snapshot;
        if (snap == null || snap.isEmpty()) snap = systemService.getMeetSchedule();
        ScheduleOrderPreset p = ScheduleOrderPreset.builder()
                .name(trim)
                .note(note)
                .snapshotJson(writeJson(snap))
                .sortOrder((int) (repository.count() + 1))
                .build();
        return toMap(repository.save(p));
    }

    /** 更新方案元信息 / 快照（重命名时校验重名） */
    public Map<String, Object> update(Long id, String name, String note, Map<String, Object> snapshot) {
        ScheduleOrderPreset p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在：" + id));
        if (name != null && !name.trim().isEmpty()) {
            String trim = name.trim();
            repository.findByName(trim).filter(x -> !x.getId().equals(id))
                    .ifPresent(x -> { throw new IllegalArgumentException("方案名已存在：" + trim); });
            p.setName(trim);
        }
        if (note != null) p.setNote(note);
        if (snapshot != null && !snapshot.isEmpty()) p.setSnapshotJson(writeJson(snapshot));
        return toMap(repository.save(p));
    }

    /** 应用方案：把快照灌回 meet_schedule（复用 SystemService 归一化） */
    public Map<String, Object> apply(Long id) {
        ScheduleOrderPreset p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在：" + id));
        Map<String, Object> snap = readJson(p.getSnapshotJson());
        if (snap.isEmpty()) throw new IllegalStateException("方案快照为空，无法应用：" + p.getName());
        Map<String, Object> applied = systemService.saveMeetSchedule(snap);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appliedId", id);
        out.put("name", p.getName());
        out.put("config", applied);
        return out;
    }

    /** 软删：打删除时间戳，由 @SQLRestriction 过滤；不用批量 UPDATE（SQLite 会拼错别名） */
    public void delete(Long id) {
        ScheduleOrderPreset p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在：" + id));
        p.setDeletedAt(LocalDateTime.now());
        repository.save(p);
    }

    public int restore(Long id) {
        return repository.restore(id);
    }

    private Map<String, Object> toMap(ScheduleOrderPreset p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("note", p.getNote());
        m.put("snapshot", readJson(p.getSnapshotJson()));
        m.put("sortOrder", p.getSortOrder());
        m.put("deleted", p.getDeletedAt() != null);
        return m;
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("方案快照序列化失败：" + e.getMessage());
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("方案快照解析失败，按空处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
