package com.sports.service;

import com.sports.entity.Event;
import com.sports.repository.EventRepository;
import com.sports.service.ExcelService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EventService {

    private final EventRepository eventRepository;
    private final ExcelService excelService;

    /** 查询所有启用的项目 */
    @Transactional(readOnly = true)
    public List<Event> list() {
        return eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
    }

    /** 查询启用的项目，支持筛选 */
    @Transactional(readOnly = true)
    public List<Event> list(String grade, String gender, String eventType) {
        List<Event> all = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        return all.stream()
                .filter(e -> grade == null || grade.isBlank() || grade.equals(e.getGradeGroup()))
                .filter(e -> gender == null || gender.isBlank() || gender.equals(e.getGenderLimit()))
                .filter(e -> eventType == null || eventType.isBlank() || eventType.equals(e.getCategory()))
                .toList();
    }

    /** 根据ID查询 */
    @Transactional(readOnly = true)
    public Event getById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
    }

    /** 创建项目 */
    public Event create(Event event) {
        if (eventRepository.existsByCode(event.getCode())) {
            throw new IllegalArgumentException("项目编码已存在: " + event.getCode());
        }
        if (event.getDefaultLanes() == null) event.setDefaultLanes(8);
        if (event.getNeedHeats() == null) event.setNeedHeats(true);
        if (event.getMaxPerHeat() == null) event.setMaxPerHeat(8);
        if (event.getIsEnabled() == null) event.setIsEnabled(true);
        if (event.getSortOrder() == null) event.setSortOrder(0);
        if (event.getScoringType() == null) event.setScoringType("global");
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        Event saved = eventRepository.save(event);
        log.info("创建项目成功: {}", saved.getName());
        return saved;
    }

    /** 更新项目 */
    public Event update(Long id, Event updated) {
        Event existing = getById(id);
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getCode() != null && !updated.getCode().equals(existing.getCode())) {
            if (eventRepository.existsByCode(updated.getCode()))
                throw new IllegalArgumentException("项目编码已存在: " + updated.getCode());
            existing.setCode(updated.getCode());
        }
        if (updated.getCategory() != null) existing.setCategory(updated.getCategory());
        if (updated.getDistanceType() != null) existing.setDistanceType(updated.getDistanceType());
        if (updated.getGenderLimit() != null) existing.setGenderLimit(updated.getGenderLimit());
        if (updated.getDefaultLanes() != null) existing.setDefaultLanes(updated.getDefaultLanes());
        if (updated.getNeedHeats() != null) existing.setNeedHeats(updated.getNeedHeats());
        if (updated.getMaxPerHeat() != null) existing.setMaxPerHeat(updated.getMaxPerHeat());
        if (updated.getAdvanceCount() != null) existing.setAdvanceCount(updated.getAdvanceCount());
        if (updated.getScoringType() != null) existing.setScoringType(updated.getScoringType());
        if (updated.getScoringRules() != null) existing.setScoringRules(updated.getScoringRules());
        if (updated.getSortOrder() != null) existing.setSortOrder(updated.getSortOrder());
        if (updated.getIsEnabled() != null) existing.setIsEnabled(updated.getIsEnabled());
        if (updated.getRegistrationStart() != null) existing.setRegistrationStart(updated.getRegistrationStart());
        if (updated.getRegistrationEnd() != null) existing.setRegistrationEnd(updated.getRegistrationEnd());
        if (updated.getRecord() != null) existing.setRecord(updated.getRecord());
        if (updated.getRemark() != null) existing.setRemark(updated.getRemark());
        if (updated.getGradeGroup() != null) existing.setGradeGroup(updated.getGradeGroup());
        if (updated.getMaxParticipants() != null) existing.setMaxParticipants(updated.getMaxParticipants());
        existing.setUpdatedAt(LocalDateTime.now());
        Event saved = eventRepository.save(existing);
        log.info("更新项目成功: {}", saved.getName());
        return saved;
    }

    /** 启用/禁用项目 */
    public Event updateStatus(Long id, Boolean enabled) {
        Event event = getById(id);
        event.setIsEnabled(enabled);
        event.setUpdatedAt(LocalDateTime.now());
        Event saved = eventRepository.save(event);
        log.info("{}项目: {}", enabled ? "启用" : "禁用", saved.getName());
        return saved;
    }

    /** 删除（软删除） */
    public void delete(Long id) {
        Event event = getById(id);
        event.setDeletedAt(LocalDateTime.now());
        eventRepository.save(event);
        log.info("删除项目成功: {}", event.getName());
    }

    /** 获取预设模板，支持分类过滤 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPresets(Map<String, Object> categoryFilter) {
        List<Map<String, Object>> all = buildPresets();
        if (categoryFilter != null && categoryFilter.containsKey("category")) {
            String cat = (String) categoryFilter.get("category");
            return all.stream().filter(p -> cat.equals(p.get("category"))).toList();
        }
        return all;
    }

    /** 从预设创建 */
    public Event createFromPreset(Long presetId) {
        List<Map<String, Object>> presets = buildPresets();
        Map<String, Object> preset = presets.stream()
                .filter(p -> p.get("id").equals(presetId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("预设模板不存在: " + presetId));
        String code = (String) preset.get("code");
        if (eventRepository.existsByCode(code))
            throw new RuntimeException("项目编码已存在: " + code);
        Event event = Event.builder()
                .name((String) preset.get("name")).code(code)
                .category((String) preset.get("category"))
                .distanceType((String) preset.get("distanceType"))
                .genderLimit((String) preset.get("genderLimit"))
                .defaultLanes((Integer) preset.get("defaultLanes"))
                .needHeats(!"田赛".equals(preset.get("category")))
                .maxPerHeat((Integer) preset.get("defaultLanes"))
                .advanceCount(8).scoringType("global").isEnabled(true)
                .sortOrder(presetId.intValue())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        Event saved = eventRepository.save(event);
        log.info("从预设创建项目成功: {}", saved.getName());
        return saved;
    }

    /** 导入项目（Excel/CSV） */
    public Map<String, Object> importEvents(MultipartFile file) {
        String fn = file.getOriginalFilename();
        log.info("导入项目: {}", fn);
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        try {
            List<Map<Integer, String>> rows;
            if (fn != null && fn.toLowerCase().endsWith(".csv")) {
                rows = readCsv(file);
            } else {
                rows = com.alibaba.excel.EasyExcel.read(file.getInputStream()).sheet().doReadSync();
            }
            int rowNum = 1;
            for (Map<Integer, String> row : rows) {
                rowNum++;
                try {
                    String name = row.getOrDefault(0, "");
                    String code = row.getOrDefault(1, "");
                    String category = row.getOrDefault(2, "");
                    String genderLimit = row.getOrDefault(3, "");
                    String lanesStr = row.getOrDefault(4, "8");
                    String needHeatsStr = row.getOrDefault(5, "是");
                    String scoringType = row.getOrDefault(6, "global");
                    String record = row.getOrDefault(7, "");

                    if (name.isBlank()) continue;
                    if (code.isBlank()) continue;

                    Event event = Event.builder()
                        .name(name.trim())
                        .code(code.trim())
                        .category(category.isBlank() ? null : category.trim())
                        .genderLimit(genderLimit.isBlank() ? null : genderLimit.trim())
                        .defaultLanes(parseIntSafe(lanesStr, 8))
                        .needHeats(!"否".equals(needHeatsStr.trim()))
                        .maxPerHeat(parseIntSafe(lanesStr, 8))
                        .scoringType(scoringType.isBlank() ? "global" : scoringType.trim())
                        .record(record.isBlank() ? null : record.trim())
                        .isEnabled(true)
                        .sortOrder(0)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                    if (eventRepository.existsByCode(event.getCode())) {
                        Event exist = eventRepository.findByCode(event.getCode()).orElseThrow();
                        exist.setName(event.getName());
                        exist.setCategory(event.getCategory());
                        exist.setGenderLimit(event.getGenderLimit());
                        exist.setDefaultLanes(event.getDefaultLanes());
                        exist.setNeedHeats(event.getNeedHeats());
                        exist.setScoringType(event.getScoringType());
                        exist.setRecord(event.getRecord());
                        exist.setUpdatedAt(LocalDateTime.now());
                        eventRepository.save(exist);
                    } else {
                        eventRepository.save(event);
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

    private int parseIntSafe(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defaultVal; }
    }

    /** 导出项目 */
    public void exportEvents(HttpServletResponse response) throws IOException {
        java.util.List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "比赛项目_" + java.time.LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        String enc = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);
        try (java.io.OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> data = new java.util.ArrayList<>();
            data.add(java.util.List.of("项目名称","项目编码","类别","性别限制","跑道数","是否分组","计分规则","校纪录"));
            for (Event e : events) {
                data.add(java.util.List.of(e.getName(), e.getCode(), e.getCategory(),
                    e.getGenderLimit(), String.valueOf(e.getDefaultLanes()),
                    e.getNeedHeats() ? "是" : "否", e.getScoringType(),
                    e.getRecord() != null ? e.getRecord() : ""));
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(headCols)
                .sheet("比赛项目").doWrite(data.subList(1, data.size()));
        }
    }

    /** 读取 CSV 文件为 EasyExcel 兼容的行格式 */
    private List<Map<Integer, String>> readCsv(MultipartFile file) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] cols = line.split(",", -1);
                Map<Integer, String> row = new HashMap<>();
                for (int i = 0; i < cols.length; i++) {
                    row.put(i, cols[i].trim());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // -------- presets --------
    private List<Map<String, Object>> buildPresets() {
        return List.of(
                preset(1L, "100米", "100M", "径赛", "短跑", "男子组", 8),
                preset(2L, "100米(女子)", "100F", "径赛", "短跑", "女子组", 8),
                preset(3L, "200米", "200M", "径赛", "短跑", "男子组", 8),
                preset(4L, "200米(女子)", "200F", "径赛", "短跑", "女子组", 8),
                preset(5L, "400米", "400M", "径赛", "中长跑", "男子组", 8),
                preset(6L, "400米(女子)", "400F", "径赛", "中长跑", "女子组", 8),
                preset(7L, "800米", "800M", "径赛", "中长跑", "男子组", 8),
                preset(8L, "800米(女子)", "800F", "径赛", "中长跑", "女子组", 8),
                preset(9L, "1500米", "1500M", "径赛", "长跑", "男子组", 12),
                preset(10L, "1500米(女子)", "1500F", "径赛", "长跑", "女子组", 12),
                preset(11L, "4×100米接力", "4X100M", "径赛", "接力", "男子组", 8),
                preset(12L, "4×100米接力(女子)", "4X100F", "径赛", "接力", "女子组", 8),
                preset(13L, "跳高", "JH_M", "田赛", "跳跃", "男子组", 1),
                preset(14L, "跳高(女子)", "JH_F", "田赛", "跳跃", "女子组", 1),
                preset(15L, "跳远", "TY_M", "田赛", "跳跃", "男子组", 1),
                preset(16L, "跳远(女子)", "TY_F", "田赛", "跳跃", "女子组", 1),
                preset(17L, "铅球", "QQ_M", "田赛", "投掷", "男子组", 1),
                preset(18L, "铅球(女子)", "QQ_F", "田赛", "投掷", "女子组", 1),
                preset(19L, "实心球", "SXQ_M", "田赛", "投掷", "男子组", 1),
                preset(20L, "实心球(女子)", "SXQ_F", "田赛", "投掷", "女子组", 1)
        );
    }

    private Map<String, Object> preset(Long id, String name, String code,
                                        String category, String distanceType,
                                        String genderLimit, int lanes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("code", code);
        m.put("category", category);
        m.put("distanceType", distanceType);
        m.put("genderLimit", genderLimit);
        m.put("defaultLanes", lanes);
        return m;
    }
}