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
        applyEventDefaults(event);
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
        // 表格2 / 调度参数（允许显式置空以外的赋值）
        if (updated.getTrack() != null) existing.setTrack(updated.getTrack());
        if (updated.getLaneCount() != null) existing.setLaneCount(updated.getLaneCount());
        if (updated.getTeam() != null) existing.setTeam(updated.getTeam());
        if (updated.getTeamMembers() != null) existing.setTeamMembers(updated.getTeamMembers());
        if (updated.getMaxDurationMinutes() != null) existing.setMaxDurationMinutes(updated.getMaxDurationMinutes());
        if (updated.getIntervalMinutes() != null) existing.setIntervalMinutes(updated.getIntervalMinutes());
        if (updated.getScheduleMode() != null) existing.setScheduleMode(updated.getScheduleMode());
        if (updated.getDefaultVenue() != null) existing.setDefaultVenue(updated.getDefaultVenue());
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
                .scheduleMode(isTrack ? "serial" : "parallel")
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

        int teamMembers = parseIntSafe(val(row, 7), 0);
        boolean isTeam = parseYesNo(val(row, 6), teamMembers > 0);

        Event e = Event.builder()
                .code(code)
                .name(name.isEmpty() ? code : name)
                .track(isTrack)
                .laneCount(lanes)
                .team(isTeam)
                .teamMembers(isTeam ? Math.max(teamMembers, isTrack ? 4 : 1) : 0)
                .genderLimit(emptyToNull(val(row, 4)))
                .gradeGroup(emptyToNull(val(row, 5)))
                .scheduleMode(emptyToNull(val(row, 8)))
                .defaultVenue(emptyToNull(val(row, 9)))
                .maxDurationMinutes(nullIfBlankInt(val(row, 10)))
                .intervalMinutes(nullIfBlankInt(val(row, 11)))
                .needHeats(true)
                .maxPerHeat(isTrack ? lanes : 1)
                .scoringType("global")
                .isEnabled(true)
                .sortOrder(0)
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
        if (src.getScheduleMode() != null) target.setScheduleMode(src.getScheduleMode());
        if (src.getDefaultVenue() != null) target.setDefaultVenue(src.getDefaultVenue());
        if (src.getMaxDurationMinutes() != null) target.setMaxDurationMinutes(src.getMaxDurationMinutes());
        if (src.getIntervalMinutes() != null) target.setIntervalMinutes(src.getIntervalMinutes());
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

        if (e.getScheduleMode() == null || e.getScheduleMode().isBlank()) {
            e.setScheduleMode(isTrack ? "serial" : "parallel");
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
            // 表格2 布局：A代码 / B项目 / C是否田径 / D道次（田赛0）
            data.add(java.util.List.of("代码","项目","是否田径","道次","性别","年级组","是否团体","团体人数","调度模式","场地","最大用时(分)","间隔(分)"));
            for (Event e : events) {
                boolean isTrack = !Boolean.FALSE.equals(e.getTrack());
                data.add(java.util.List.of(
                    nz(e.getCode()), nz(e.getName()),
                    isTrack ? "是" : "否",
                    String.valueOf(e.getLaneCount() != null ? e.getLaneCount() : (isTrack ? 8 : 0)),
                    nz(e.getGenderLimit()), nz(e.getGradeGroup()),
                    Boolean.TRUE.equals(e.getTeam()) ? "是" : "否",
                    String.valueOf(e.getTeamMembers() != null ? e.getTeamMembers() : 0),
                    nz(e.getScheduleMode()), nz(e.getDefaultVenue()),
                    e.getMaxDurationMinutes() != null ? String.valueOf(e.getMaxDurationMinutes()) : "",
                    e.getIntervalMinutes() != null ? String.valueOf(e.getIntervalMinutes()) : ""));
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(headCols)
                .sheet("比赛项目").doWrite(data.subList(1, data.size()));
        }
    }

    /** 读取 CSV 文件为 EasyExcel 兼容的行格式（自动识别 UTF-8/GB18030 等编码） */
    private List<Map<Integer, String>> readCsv(MultipartFile file) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        String text = com.sports.common.FileEncoding.decode(file.getBytes());
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