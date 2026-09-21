package com.sports.service.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.event.Event;
import com.sports.repository.event.EventRepository;
import com.sports.service.excel.ExcelService;
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
import com.sports.common.util.FileEncoding;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EventService {

    private final EventRepository eventRepository;
    private final ExcelService excelService;

    /** 仅用于「部分更新」的字段合并；忽略未知属性，避免前端多传键导致 400 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

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
        applyEventDefaults(event);
        event.setCreatedAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        Event saved = eventRepository.save(event);
        log.info("创建项目成功: {}", saved.getName());
        return saved;
    }

    /** 更新项目 */
    /**
     * 部分更新（PATCH 语义）：<b>仅覆盖请求中显式出现的字段</b>。
     *
     * <p>注意：不能按「非空即覆盖」实现 —— Event 的多数字段带 Java 初始化默认值
     * （track=true、laneCount=8、defaultLanes=8 …），Jackson 把请求反序列化成实体时，
     * 未提交的字段同样会拿到默认值而非 null，导致"只改一个字段"的批量修改误伤其它字段
     * （典型：批量改并发把田赛改成径赛）。故此处用 readerForUpdating 只合并请求中出现的键。</p>
     */
    public Event update(Long id, Map<String, Object> body) {
        Event existing = getById(id);

        // 编码唯一性校验（仅当请求确实改了编码）
        Object codeObj = body.get("code");
        if (codeObj != null) {
            String newCode = String.valueOf(codeObj).trim();
            if (!newCode.isEmpty() && !newCode.equals(existing.getCode())) {
                if (eventRepository.existsByCode(newCode)) {
                    throw new IllegalArgumentException("项目编码已存在: " + newCode);
                }
            }
        }

        Map<String, Object> patch = new LinkedHashMap<>(body);
        patch.remove("id");
        patch.remove("createdAt");
        patch.remove("updatedAt");
        patch.remove("deletedAt");
        try {
            objectMapper.updateValue(existing, patch);
        } catch (Exception e) {
            throw new IllegalArgumentException("更新参数解析失败: " + e.getMessage(), e);
        }

        syncDerivedFields(existing);
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

    // ==================== 批量操作（逐条独立，单条失败不影响其余） ====================

    /** 批量新增：返回成功/失败明细 */
    public Map<String, Object> batchCreate(List<Event> items) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Event evt = items.get(i);
            String label = evt.getName() != null ? evt.getName() : ("第" + (i + 1) + "条");
            try {
                Event saved = create(evt);
                created.add(saved.getName());
            } catch (Exception e) {
                errors.add(Map.of("index", i + 1, "name", label,
                        "message", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("success", created.size());
        result.put("failed", errors.size());
        result.put("created", created);
        result.put("errors", errors);
        log.info("批量新增项目: total={}, success={}, failed={}", items.size(), created.size(), errors.size());
        return result;
    }

    /** 批量部分更新（patch 中出现的字段生效，与单条 update 语义一致） */
    public Map<String, Object> batchUpdate(List<Long> ids, Map<String, Object> patch) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        for (Long id : ids) {
            try {
                Event saved = update(id, patch);
                updated.add(saved.getName());
            } catch (Exception e) {
                errors.add(Map.of("id", id, "message",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", ids.size());
        result.put("success", updated.size());
        result.put("failed", errors.size());
        result.put("updated", updated);
        result.put("errors", errors);
        log.info("批量更新项目: total={}, success={}, failed={}", ids.size(), updated.size(), errors.size());
        return result;
    }

    /** 批量启用/禁用 */
    public Map<String, Object> batchStatus(List<Long> ids, Boolean enabled) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> ok = new ArrayList<>();
        for (Long id : ids) {
            try {
                Event saved = updateStatus(id, enabled);
                ok.add(saved.getName());
            } catch (Exception e) {
                errors.add(Map.of("id", id, "message",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", ids.size());
        result.put("success", ok.size());
        result.put("failed", errors.size());
        result.put("errors", errors);
        log.info("批量{}项目: total={}, success={}, failed={}", enabled ? "启用" : "禁用", ids.size(), ok.size(), errors.size());
        return result;
    }

    /** 批量删除（软删除） */
    public Map<String, Object> batchDelete(List<Long> ids) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Long id : ids) {
            try {
                Event evt = eventRepository.findById(id).orElseThrow(
                        () -> new IllegalArgumentException("项目不存在: " + id));
                evt.setDeletedAt(LocalDateTime.now());
                eventRepository.save(evt);
                deleted.add(evt.getName());
            } catch (Exception e) {
                errors.add(Map.of("id", id, "message",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", ids.size());
        result.put("success", deleted.size());
        result.put("failed", errors.size());
        result.put("deleted", deleted);
        result.put("errors", errors);
        log.info("批量删除项目: total={}, success={}, failed={}", ids.size(), deleted.size(), errors.size());
        return result;
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
        String category = (String) preset.get("category");
        boolean isTrack = !"田赛".equals(category);
        int lanes = (Integer) preset.get("defaultLanes");
        int teamMembers = (Integer) preset.getOrDefault("teamMembers", 0);

        Event event = Event.builder()
                .name((String) preset.get("name")).code(code)
                .category(category)
                .distanceType((String) preset.get("distanceType"))
                .genderLimit((String) preset.get("genderLimit"))
                .track(isTrack)
                .laneCount(isTrack ? Math.max(lanes, 1) : 0)
                .defaultLanes(isTrack ? Math.max(lanes, 1) : 1)
                .team(teamMembers > 0)
                .teamMembers(teamMembers)
                .concurrency(isTrack ? Math.max(lanes, 1) : 1)
                .needHeats(isTrack)
                .maxPerHeat(isTrack ? Math.max(lanes, 1) : 1)
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
        boolean table2 = true; // 布局结果（供返回信息使用，需在 try 之外可见）
        try {
            List<Map<Integer, String>> rows;
            if (fn != null && fn.toLowerCase().endsWith(".csv")) {
                rows = readCsv(file);
            } else {
                // headRowNumber(0)：保留表头行，否则 detectTable2Layout 会拿首条数据行误判布局
                rows = com.alibaba.excel.EasyExcel.read(file.getInputStream()).sheet()
                        .headRowNumber(0).doReadSync();
            }
            // 自动识别列布局：表格2（代码/项目/是否田径/道次）或传统模板（项目名称/项目编码/...）
            table2 = detectTable2Layout(rows);
            int rowNum = 1;
            for (Map<Integer, String> row : rows) {
                rowNum++;

                Event event;
                try {
                    event = table2 ? parseTable2Row(row) : parseLegacyRow(row);
                } catch (IllegalArgumentException ex) {
                    continue; // 表头/空行，静默跳过
                }

                try {
                    applyEventDefaults(event);

                    if (eventRepository.existsByCode(event.getCode())) {
                        Event exist = eventRepository.findByCode(event.getCode()).orElseThrow();
                        mergeInto(exist, event);
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
        result.put("layout", table2 ? "table2" : "legacy");
        result.put("errors", errors);
        return result;
    }

    /**
     * 解析「表格2」布局：A代码 / B项目 / C是否田径 / D道次（田赛写0）
     * 可选扩展列：E性别 / F年级组 / G是否团体 / H团体人数 / I调度模式 / J场地 / K最大用时 / L间隔
     */
    private Event parseTable2Row(Map<Integer, String> row) {
        String code = val(row, 0);
        String name = val(row, 1);
        if (code.isEmpty() && name.isEmpty()) throw new IllegalArgumentException("空行");
        if (isHeaderCell(code) || isHeaderCell(name)) throw new IllegalArgumentException("表头");

        boolean isTrack = parseYesNo(val(row, 2), true);
        int lanes = parseIntSafe(val(row, 3), isTrack ? 8 : 0);
        // 田赛强制道次为 0；径赛道次为 0 时回退到默认 8
        if (!isTrack) lanes = 0;
        else if (lanes <= 0) lanes = 8;

        int teamMembers = parseIntSafe(val(row, 12), 0);   // 团体人数（M 列）
        boolean isTeam = parseYesNo(val(row, 11), teamMembers > 0);  // 是否团体（L 列）

        Event e = Event.builder()
                .code(code)
                .name(name.isEmpty() ? code : name)
                .track(isTrack)
                .laneCount(lanes)
                .team(isTeam)
                .teamMembers(isTeam ? Math.max(teamMembers, isTrack ? 4 : 1) : 0)
                // E 列：顺序号；F 列：每组次几人；G 列：捆绑字母；H 列：并行数；I 列：场地编码
                .sortOrder(parseIntSafe(val(row, 4), 0))
                .groupSize(nullIfBlankInt(val(row, 5)))
                .bundleGroup(emptyToNull(val(row, 6)))
                .concurrency(nullIfBlankInt(val(row, 7)))
                .defaultVenueCode(emptyToNull(val(row, 8)))
                .genderLimit(emptyToNull(val(row, 9)))
                .gradeGroup(emptyToNull(val(row, 10)))
                .defaultVenue(emptyToNull(val(row, 13)))
                .maxDurationMinutes(nullIfBlankInt(val(row, 14)))
                .intervalMinutes(nullIfBlankInt(val(row, 15)))
                // Q 列：组次裁判数量；R 列：抽签（随机道次）
                .refereesPerGroup(nullIfBlankInt(val(row, 16)))
                .drawLots(parseYesNo(val(row, 17), false))
                .needHeats(true)
                .maxPerHeat(isTrack ? lanes : 1)
                .scoringType("global")
                .isEnabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (!isTrack) e.setDefaultLanes(1);
        return e;
    }

    /** 解析传统模板布局（项目名称/项目编码/类别/性别限制/跑道数/是否分组/计分规则/校纪录） */
    private Event parseLegacyRow(Map<Integer, String> row) {
        String name = val(row, 0);
        String code = val(row, 1);
        if (name.isEmpty() || code.isEmpty()) throw new IllegalArgumentException("空行或缺少编码");
        if (isHeaderCell(name) || isHeaderCell(code)) throw new IllegalArgumentException("表头");

        String category = val(row, 2);
        int lanes = parseIntSafe(val(row, 4), 8);

        return Event.builder()
                .name(name)
                .code(code)
                .category(emptyToNull(category))
                .genderLimit(emptyToNull(val(row, 3)))
                .track(!"田赛".equals(category))
                .laneCount("田赛".equals(category) ? 0 : lanes)
                .defaultLanes("田赛".equals(category) ? 1 : lanes)
                .needHeats(!"否".equals(val(row, 5)))
                .maxPerHeat(lanes)
                .scoringType(val(row, 6).isEmpty() ? "global" : val(row, 6))
                .record(emptyToNull(val(row, 7)))
                .isEnabled(true)
                .sortOrder(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 已存在项目时合并更新（避免覆盖未提供的字段） */
    private void mergeInto(Event target, Event src) {
        target.setName(src.getName());
        target.setTrack(src.getTrack());
        target.setLaneCount(src.getLaneCount());
        target.setTeam(src.getTeam());
        target.setTeamMembers(src.getTeamMembers());
        if (src.getCategory() != null) target.setCategory(src.getCategory());
        if (src.getGenderLimit() != null) target.setGenderLimit(src.getGenderLimit());
        if (src.getGradeGroup() != null) target.setGradeGroup(src.getGradeGroup());
        if (src.getConcurrency() != null) target.setConcurrency(src.getConcurrency());
        if (src.getGroupSize() != null) target.setGroupSize(src.getGroupSize());
        if (src.getBundleGroup() != null) target.setBundleGroup(src.getBundleGroup());
        if (src.getDefaultVenue() != null) target.setDefaultVenue(src.getDefaultVenue());
        if (src.getMaxDurationMinutes() != null) target.setMaxDurationMinutes(src.getMaxDurationMinutes());
        if (src.getIntervalMinutes() != null) target.setIntervalMinutes(src.getIntervalMinutes());
        if (src.getRefereesPerGroup() != null) target.setRefereesPerGroup(src.getRefereesPerGroup());
        if (src.getDrawLots() != null) target.setDrawLots(src.getDrawLots());
        if (src.getRecord() != null) target.setRecord(src.getRecord());
        target.setNeedHeats(src.getNeedHeats());
        target.setUpdatedAt(LocalDateTime.now());
        syncDerivedFields(target);
    }

    /** 检测是否为「表格2」布局：表头中出现 代码/道次/是否田径，且首列不是"项目名称" */
    private boolean detectTable2Layout(List<Map<Integer, String>> rows) {
        if (rows.isEmpty()) return true;
        Map<Integer, String> head = rows.get(0);
        StringBuilder sb = new StringBuilder();
        for (String v : head.values()) sb.append(v).append('|');
        String h = sb.toString();
        if (h.contains("项目名称") || h.contains("项目编码")) return false;
        return h.contains("代码") || h.contains("道次") || h.contains("是否田径");
    }

    private static String val(Map<Integer, String> row, int idx) {
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Integer nullIfBlankInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static boolean isHeaderCell(String s) {
        if (s == null) return false;
        String t = s.trim();
        return "代码".equals(t) || "项目".equals(t) || "项目名称".equals(t) || "项目编码".equals(t)
                || "是否田径".equals(t) || "道次".equals(t);
    }

    /** Excel 导出用：null → 空串 */
    private static String nz(String s) { return s == null ? "" : s; }

    /** 解析 是/否、true/false、1/0、径赛/田赛 */
    private static boolean parseYesNo(String s, boolean def) {
        if (s == null || s.isBlank()) return def;
        String t = s.trim();
        if ("否".equals(t) || "0".equals(t) || "false".equalsIgnoreCase(t)
                || "no".equalsIgnoreCase(t) || "田赛".equals(t)) return false;
        if ("是".equals(t) || "1".equals(t) || "true".equalsIgnoreCase(t)
                || "yes".equalsIgnoreCase(t) || "径赛".equals(t)) return true;
        return def;
    }

    // ==================== 字段联动与默认值 ====================

    /**
     * 表格2 语义联动：径赛/田赛 决定 类别、道次、调度模式；团体人数决定是否团体赛。
     * 保证 defaultLanes（历史字段）与 laneCount（表格2 字段）始终一致。
     */
    private void syncDerivedFields(Event e) {
        boolean isTrack = !Boolean.FALSE.equals(e.getTrack());

        if (e.getTeamMembers() != null && e.getTeamMembers() > 0) {
            e.setTeam(true);
        }
        if (Boolean.FALSE.equals(e.getTeam())) {
            e.setTeamMembers(0);
        }

        if (isTrack) {
            if (e.getCategory() == null || e.getCategory().isBlank()) e.setCategory("径赛");
            int lanes = e.getLaneCount() != null && e.getLaneCount() > 0 ? e.getLaneCount() : 8;
            e.setLaneCount(lanes);
            e.setDefaultLanes(lanes);
            if (e.getMaxPerHeat() == null) e.setMaxPerHeat(lanes);
        } else {
            // 田赛：不占道次，道次固定为 0
            if (e.getCategory() == null || e.getCategory().isBlank()) e.setCategory("田赛");
            e.setLaneCount(0);
            e.setDefaultLanes(1);
            e.setMaxPerHeat(1);
        }
    }

    /** 新建项目时补齐默认值 */
    private void applyEventDefaults(Event e) {
        if (e.getTrack() == null) {
            // 未显式指定时，按类别推断：田赛→false，其余（含径赛）→true
            e.setTrack(!"田赛".equals(e.getCategory()));
        }
        if (e.getLaneCount() == null) {
            e.setLaneCount(e.getDefaultLanes() != null ? e.getDefaultLanes() : 8);
        }
        if (e.getTeam() == null) e.setTeam(false);
        if (e.getTeamMembers() == null) e.setTeamMembers(0);
        if (e.getAdvanceCount() == null) e.setAdvanceCount(8);
        syncDerivedFields(e);
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
            // 表格2 布局：A代码 / B项目 / C是否田径 / D道次（田赛0）/ … / I项目内并发(并数) /
            //            J场地 / K最大用时 / L间隔 / M顺序号 / N并行捆绑组 / O场地编码
            // 表格2 折中布局（保留全部字段，顺序号/每组次几人/捆绑字母/并行数/场地编码 紧挨排布）：
            // A代码/B项目/C是否田径/D道次/E顺序号/F每组次几人/G捆绑字母/H并行数/I场地编码/
            // J性别/K年级组/L是否团体/M团体人数/N场地/O最大用时(分)/P间隔(分)
            data.add(java.util.List.of("代码","项目","是否田径","道次","顺序号","每组次几人","捆绑字母","并行数","场地编码","性别","年级组","是否团体","团体人数","场地","最大用时(分)","间隔(分)","组次裁判数量","抽签"));
            for (Event e : events) {
                boolean isTrack = !Boolean.FALSE.equals(e.getTrack());
                int concurrency = e.getConcurrency() != null && e.getConcurrency() > 0
                        ? e.getConcurrency()
                        : (isTrack ? (e.getLaneCount() != null && e.getLaneCount() > 0 ? e.getLaneCount() : 8) : 1);
            int groupSize = e.getGroupSize() != null && e.getGroupSize() > 0
                    ? e.getGroupSize()
                    : (isTrack ? (e.getLaneCount() != null && e.getLaneCount() > 0 ? e.getLaneCount() : 8) : 1);
            data.add(java.util.List.of(
                nz(e.getCode()), nz(e.getName()),
                isTrack ? "是" : "否",
                String.valueOf(e.getLaneCount() != null ? e.getLaneCount() : (isTrack ? 8 : 0)),
                e.getSortOrder() != null ? String.valueOf(e.getSortOrder()) : "",
                String.valueOf(groupSize),
                nz(e.getBundleGroup()),
                String.valueOf(concurrency),
                nz(e.getDefaultVenueCode()),
                nz(e.getGenderLimit()), nz(e.getGradeGroup()),
                Boolean.TRUE.equals(e.getTeam()) ? "是" : "否",
                String.valueOf(e.getTeamMembers() != null ? e.getTeamMembers() : 0),
                nz(e.getDefaultVenue()),
                e.getMaxDurationMinutes() != null ? String.valueOf(e.getMaxDurationMinutes()) : "",
                e.getIntervalMinutes() != null ? String.valueOf(e.getIntervalMinutes()) : "",
                e.getRefereesPerGroup() != null ? String.valueOf(e.getRefereesPerGroup()) : "",
                Boolean.TRUE.equals(e.getDrawLots()) ? "是" : "否"));
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(headCols)
                .sheet("比赛项目").doWrite(data.subList(1, data.size()));
        }
    }

    // ==================== JSON 导出 / 导入（全字段往返，含默认值） ====================

    /**
     * 项目字段默认值：JSON/Excel 导入时缺省字段的回填基准；导出时一并给出便于对照与二次编辑。
     * 与 {@link #applyEventDefaults} / {@link #syncDerivedFields} 的口径保持一致。
     */
    public static Map<String, Object> eventDefaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("eventType", "径赛");
        d.put("distanceType", null);
        d.put("isTrack", true);
        d.put("laneCount", 8);
        d.put("defaultLanes", 8);
        d.put("isTeam", false);
        d.put("teamSize", 0);
        d.put("concurrency", 8);
        d.put("groupSize", 8);
        d.put("bundleGroup", null);
        d.put("refereesPerGroup", 0);
        d.put("drawLots", false);
        d.put("maxDurationMinutes", 20);
        d.put("intervalMinutes", 5);
        d.put("needHeats", true);
        d.put("maxPerHeat", 8);
        d.put("maxParticipants", null);
        d.put("advanceCount", 8);
        d.put("scoringType", "global");
        d.put("sortOrder", 0);
        d.put("enabled", true);
        return d;
    }

    /** 单个项目 → 全字段 Map（键与实体 @JsonProperty 对齐，可直接回灌导入） */
    public Map<String, Object> eventToMap(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.getCode());
        m.put("name", e.getName());
        m.put("eventType", e.getCategory());
        m.put("distanceType", e.getDistanceType());
        m.put("isTrack", !Boolean.FALSE.equals(e.getTrack()));
        m.put("laneCount", e.getLaneCount());
        m.put("isTeam", Boolean.TRUE.equals(e.getTeam()));
        m.put("teamSize", e.getTeamMembers());
        m.put("maxDurationMinutes", e.getMaxDurationMinutes());
        m.put("intervalMinutes", e.getIntervalMinutes());
        m.put("concurrency", e.getConcurrency());
        m.put("groupSize", e.getGroupSize());
        m.put("bundleGroup", e.getBundleGroup());
        m.put("refereesPerGroup", e.getRefereesPerGroup());
        m.put("drawLots", Boolean.TRUE.equals(e.getDrawLots()));
        m.put("defaultVenue", e.getDefaultVenue());
        m.put("defaultVenueCode", e.getDefaultVenueCode());
        m.put("gender", e.getGenderLimit());
        m.put("gradeGroup", e.getGradeGroup());
        m.put("defaultLanes", e.getDefaultLanes());
        m.put("needHeats", e.getNeedHeats());
        m.put("maxPerHeat", e.getMaxPerHeat());
        m.put("maxParticipants", e.getMaxParticipants());
        m.put("advanceCount", e.getAdvanceCount());
        m.put("scoringType", e.getScoringType());
        m.put("scoringRules", e.getScoringRules());
        m.put("sortOrder", e.getSortOrder());
        m.put("enabled", e.getIsEnabled());
        m.put("registrationStart", e.getRegistrationStart());
        m.put("registrationEnd", e.getRegistrationEnd());
        m.put("record", e.getRecord());
        m.put("description", e.getRemark());
        return m;
    }

    /** 全量项目 JSON（含 meta / defaults / events），用于导出下载 */
    @Transactional(readOnly = true)
    public Map<String, Object> exportEventsJson() {
        List<Event> events = new ArrayList<>(eventRepository.findAll());
        events.sort(java.util.Comparator.comparingInt(e -> e.getSortOrder() == null ? 0 : e.getSortOrder()));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "sports-events");
        root.put("version", 1);
        root.put("exportedAt", LocalDateTime.now().toString());
        root.put("count", events.size());
        root.put("defaults", eventDefaults());
        root.put("events", events.stream().map(this::eventToMap).toList());
        return root;
    }

    /** JSON 模板：默认值 + 1 条示例（供用户填写后导入） */
    public Map<String, Object> jsonTemplate() {
        Map<String, Object> sample = new LinkedHashMap<>(eventDefaults());
        sample.put("code", "100M");
        sample.put("name", "100米");
        sample.put("eventType", "径赛");
        sample.put("isTrack", true);
        sample.put("laneCount", 8);
        sample.put("concurrency", 8);
        sample.put("groupSize", 8);
        sample.put("refereesPerGroup", 2);
        sample.put("drawLots", false);
        sample.put("maxDurationMinutes", 20);
        sample.put("intervalMinutes", 5);
        sample.put("gender", "M");
        sample.put("gradeGroup", "高一年级");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "sports-events");
        root.put("version", 1);
        root.put("defaults", eventDefaults());
        root.put("events", List.of(sample));
        return root;
    }

    /**
     * JSON 文件导入（全字段往返）。接受两种结构：
     * <ul>
     *   <li>导出文件的完整结构 <code>{ type, version, defaults, events: [...] }</code>；</li>
     *   <li>裸数组 <code>[ {...}, {...} ]</code>。</li>
     * </ul>
     * 按 <code>code</code> 判定：已存在 → 部分覆盖（仅覆盖 JSON 中出现的字段）；不存在 → 新建（缺省字段用默认值）。
     */
    public Map<String, Object> importEventsJson(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请上传 JSON 文件");
        String text;
        try {
            text = com.sports.common.util.FileEncoding.decode(file.getBytes());
        } catch (IOException ex) {
            throw new RuntimeException("读取 JSON 文件失败: " + ex.getMessage());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(text);
            com.fasterxml.jackson.databind.JsonNode arr = root.isArray() ? root : root.path("events");
            if (arr == null || !arr.isArray()) {
                throw new IllegalArgumentException("JSON 结构不正确：应为数组，或含 events 数组的对象");
            }
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = objectMapper.convertValue(n, Map.class);
                items.add(m);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("JSON 解析失败: " + ex.getMessage());
        }
        return upsertEventsFromMaps(items);
    }

    /** 逐条 upsert：单条失败不影响其余，返回成功/失败明细 */
    private Map<String, Object> upsertEventsFromMaps(List<Map<String, Object>> items) {
        int created = 0, updated = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> raw : items) {
            idx++;
            Map<String, Object> item = new LinkedHashMap<>(raw);
            item.remove("id");
            item.remove("createdAt");
            item.remove("updatedAt");
            item.remove("deletedAt");
            Object codeObj = item.get("code");
            String code = codeObj == null ? null : String.valueOf(codeObj).trim();
            try {
                if (code == null || code.isEmpty()) throw new IllegalArgumentException("缺少 code");
                Optional<Event> existOpt = eventRepository.findByCode(code);
                if (existOpt.isPresent()) {
                    Event exist = existOpt.get();
                    objectMapper.updateValue(exist, item);
                    syncDerivedFields(exist);
                    exist.setUpdatedAt(LocalDateTime.now());
                    eventRepository.save(exist);
                    updated++;
                } else {
                    Event evt = objectMapper.convertValue(item, Event.class);
                    evt.setId(null);
                    evt.setCode(code);
                    applyEventDefaults(evt);
                    evt.setCreatedAt(LocalDateTime.now());
                    evt.setUpdatedAt(LocalDateTime.now());
                    eventRepository.save(evt);
                    created++;
                }
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("index", idx);
                err.put("code", code);
                err.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
                errors.add(err);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", items.size());
        res.put("created", created);
        res.put("updated", updated);
        res.put("success", created + updated);
        res.put("failed", errors.size());
        res.put("errors", errors);
        log.info("JSON 导入项目: total={}, created={}, updated={}, failed={}",
                items.size(), created, updated, errors.size());
        return res;
    }

    /** 读取 CSV 文件为 EasyExcel 兼容的行格式（自动识别 UTF-8/GB18030 等编码） */
    private List<Map<Integer, String>> readCsv(MultipartFile file) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        String text = com.sports.common.util.FileEncoding.decode(file.getBytes());
        String[] lines = text.split("\r?\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cols = line.split(",", -1);
            Map<Integer, String> row = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                row.put(i, cols[i].trim());
            }
            rows.add(row);
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
                preset(11L, "4×100米接力", "4X100M", "径赛", "接力", "男子组", 8, 4),
                preset(12L, "4×100米接力(女子)", "4X100F", "径赛", "接力", "女子组", 8, 4),
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
        return preset(id, name, code, category, distanceType, genderLimit, lanes, 0);
    }

    /** teamMembers > 0 表示团体赛（如接力 4 人一队） */
    private Map<String, Object> preset(Long id, String name, String code,
                                        String category, String distanceType,
                                        String genderLimit, int lanes, int teamMembers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("code", code);
        m.put("category", category);
        m.put("distanceType", distanceType);
        m.put("genderLimit", genderLimit);
        m.put("defaultLanes", lanes);
        m.put("teamMembers", teamMembers);
        return m;
    }
}