package com.sports.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.entity.AuditLog;
import com.sports.entity.SystemConfig;
import com.sports.repository.AuditLogRepository;
import com.sports.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SystemService {

    private final SystemConfigRepository systemConfigRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource;
    private final GradeService gradeService;

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

    // ==================== 秩序册自动生成开关 ====================

    private static final String ORDER_BOOK_AUTO_KEY = "order_book.auto_generate";

    /** 是否启用「生成预赛/编排后自动生成秩序册(Word)」 */
    @Transactional(readOnly = true)
    public boolean isOrderBookAutoGenerate() {
        return systemConfigRepository.findByConfigKey(ORDER_BOOK_AUTO_KEY)
                .map(c -> "true".equalsIgnoreCase(String.valueOf(c.getConfigValue()).trim()))
                .orElse(false);
    }

    /** 设置「生成预赛/编排后自动生成秩序册(Word)」开关 */
    public void setOrderBookAutoGenerate(boolean enabled) {
        SystemConfig config = systemConfigRepository.findByConfigKey(ORDER_BOOK_AUTO_KEY)
                .orElse(SystemConfig.builder().configKey(ORDER_BOOK_AUTO_KEY).build());
        config.setConfigValue(String.valueOf(enabled));
        config.setConfigType("bool");
        config.setDescription("生成预赛/编排后自动生成秩序册(Word)");
        config.setUpdatedAt(LocalDateTime.now());
        systemConfigRepository.save(config);
        log.info("秩序册自动生成开关已更新: {}", enabled);
    }

    // ==================== 裁判编排开关 ====================

    private static final String REFEREE_ARRANGE_KEY = "arrange.referee_enabled";

    /**
     * 是否启用「裁判编排」——编排时是否按项目「组次裁判数量」自动分配裁判。
     * <p>默认<b>开启</b>；关闭后编排照常进行但<b>不分配裁判</b>（裁判池为空时亦自动跳过，不影响编排）。</p>
     */
    @Transactional(readOnly = true)
    public boolean isRefereeArrangeEnabled() {
        return systemConfigRepository.findByConfigKey(REFEREE_ARRANGE_KEY)
                .map(c -> !"false".equalsIgnoreCase(String.valueOf(c.getConfigValue()).trim()))
                .orElse(true);
    }

    /** 设置「裁判编排」开关 */
    public void setRefereeArrangeEnabled(boolean enabled) {
        SystemConfig config = systemConfigRepository.findByConfigKey(REFEREE_ARRANGE_KEY)
                .orElse(SystemConfig.builder().configKey(REFEREE_ARRANGE_KEY).build());
        config.setConfigValue(String.valueOf(enabled));
        config.setConfigType("bool");
        config.setDescription("启用裁判编排：编排时按项目「组次裁判数量」自动分配裁判");
        config.setUpdatedAt(LocalDateTime.now());
        systemConfigRepository.save(config);
        log.info("裁判编排开关已更新: {}", enabled);
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

    // ==================== 年级管理（委托 GradeService，M1 拆分）====================

    /** 获取年级列表（委托 GradeService） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGrades() {
        return gradeService.getGrades();
    }

    /** 新增年级（委托 GradeService） */
    @Transactional
    public Map<String, Object> addGrade(Map<String, Object> body) {
        return gradeService.addGrade(body);
    }

    /** 编辑年级（委托 GradeService） */
    @Transactional
    public Map<String, Object> editGrade(Long id, Map<String, Object> body) {
        return gradeService.editGrade(id, body);
    }

    /** 删除年级（委托 GradeService） */
    @Transactional
    public void deleteGrade(Long id) {
        gradeService.deleteGrade(id);
    }

    /**
     * 最近操作日志（M3 修复：原为永远返回空的占位实现，前端「系统日志」页恒空）。
     * <p>现接入 {@code audit_log} 表（由 {@code AuditService.record} 在导入、编排、成绩修改、
     * 锁定等关键动作时写入），返回最近 50 条，前端系统日志页得以展示真实记录。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getRecentLogs() {
        List<AuditLog> recent = auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50));
        List<Map<String, Object>> logs = recent.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("action", a.getAction());
            m.put("targetType", a.getTargetType());
            m.put("targetId", a.getTargetId());
            m.put("detail", a.getDetail());
            m.put("operator", a.getOperator());
            m.put("createdAt", a.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logs", logs);
        result.put("total", logs.size());
        return result;
    }

    // ==================== 健康检查 ====================

    /** 健康检查详情（数据库/磁盘/内存/JVM） */
    @Transactional(readOnly = true)
    public Map<String, Object> getHealthDetail() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "UP");
        r.put("time", LocalDateTime.now().toString());

        // 数据库连接
        try (Connection conn = dataSource.getConnection()) {
            var md = conn.getMetaData();
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("status", "UP");
            db.put("type", detectDbType(md.getURL()));
            db.put("product", md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            db.put("url", md.getURL());
            int tableCount = 0;
            try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) tableCount++;
            }
            db.put("tableCount", tableCount);
            r.put("database", db);
        } catch (Exception e) {
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
            r.put("database", db);
            r.put("status", "DOWN");
        }

        // 磁盘空间
        File root = new File("./data").exists() ? new File("./data") : new File(".");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("total", total);
        disk.put("free", free);
        disk.put("used", total - free);
        disk.put("totalLabel", humanSize(total));
        disk.put("freeLabel", humanSize(free));
        r.put("disk", disk);

        // JVM 内存
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("max", rt.maxMemory());
        mem.put("total", rt.totalMemory());
        mem.put("free", rt.freeMemory());
        mem.put("used", rt.totalMemory() - rt.freeMemory());
        mem.put("usedLabel", humanSize(rt.totalMemory() - rt.freeMemory()));
        mem.put("maxLabel", humanSize(rt.maxMemory()));
        r.put("memory", mem);

        r.put("javaVersion", System.getProperty("java.version"));
        r.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        return r;
    }

    private String detectDbType(String url) {
        if (url == null) return "unknown";
        String l = url.toLowerCase();
        if (l.contains("mysql")) return "mysql";
        if (l.contains("h2")) return "h2";
        if (l.contains("sqlite")) return "sqlite";
        return "unknown";
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    // ==================== 应用运行配置（服务端口等，重启生效）====================

    private static final String APP_CONFIG_FILE = "./data/app-config.json";

    /** 读取应用运行配置（含默认值兜底） */
    @Transactional(readOnly = true)
    public Map<String, Object> getAppConfig() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("port", 8080);
        File cfg = new File(APP_CONFIG_FILE);
        if (cfg.exists()) {
            try {
                Map<String, Object> saved = objectMapper.readValue(cfg, new TypeReference<Map<String, Object>>() {});
                if (saved != null) def.putAll(saved);
            } catch (Exception e) {
                log.warn("读取应用运行配置失败，使用默认值", e);
            }
        }
        return def;
    }

    /** 保存应用运行配置（写入 data/app-config.json，重启后生效） */
    public Map<String, Object> saveAppConfig(Map<String, Object> body) {
        File cfg = new File(APP_CONFIG_FILE);
        File parent = cfg.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try {
            objectMapper.writeValue(cfg, body);
        } catch (Exception e) {
            throw new RuntimeException("保存应用运行配置失败: " + e.getMessage());
        }
        log.info("应用运行配置已保存: {}", body);
        return getAppConfig();
    }

    // ==================== 运动会日程配置（meet_schedule）====================
    //
    // 全部可配置，严禁硬编码：
    //   startDate / days / dayConfigs[]  —— 运动会日期与每天的时段（每天可不同）
    //   gradeOrder                       —— 年级出场顺序（缺省取 grades 配置按 sortOrder 排序）
    //   trackSlots / fieldSlots          —— 并发位数：1=串行，n=同时进行 n 个项目（取代原 trackMode/fieldMode）
    //   eventOrder                       —— 自定义项目编排顺序（eventId 有序列表，田赛+径赛混排）
    //   fieldGroups                      —— 田赛分组：同组项目安排在同一时段并行
    //   defaultDurationMinutes           —— 每个项目最大时间（同时作为压缩上限）
    //   defaultIntervalMinutes           —— 项目间隔时间
    //   minIntervalMinutes               —— 项目间隔下限（不小于此值，避免项目紧贴导致现场不可行）
    //   compressionWarnRatio             —— 压缩告警阈值（被压到预计用时的 1/ratio 以下时写 warnings 告警）
    //   finalMinGapMinutes               —— 预赛→决赛最小间隔（B07/U06，默认 45 分钟，落在建议的 45~60 区间）

    /** 读取运动会日程配置（含默认值兜底） */
    @Transactional(readOnly = true)
    public Map<String, Object> getMeetSchedule() {
        Map<String, Object> def = defaultMeetSchedule();
        // 旧「串行/并行」→ 并发位数 平滑迁移（serial→1、parallel→2）。
        // 必须在 deepMerge 之前基于“库里真实存过的键”判断，否则默认值会掩盖旧键。
        applyLegacyModeMigration(def);
        Map<String, Object> saved = readJsonConfig("meet_schedule", def);
        // 缺省字段回退到默认值，避免旧配置缺键
        for (Map.Entry<String, Object> e : def.entrySet()) {
            if (!saved.containsKey(e.getKey()) || saved.get(e.getKey()) == null) {
                saved.put(e.getKey(), e.getValue());
            }
        }
        // 未显式自定义出场顺序（存空）时，实时跟随「年级管理」的 sortOrder；
        // gradeOrderCustom 供前端判断：true = 已显式定制（可编辑），false = 跟随年级设置（只读）
        boolean storedEmpty = saved.get("gradeOrder") == null
                || ((List<?>) saved.get("gradeOrder")).isEmpty();
        if (storedEmpty) {
            saved.put("gradeOrder", getGradeOrder());
        }
        saved.put("gradeOrderCustom", !storedEmpty);
        return saved;
    }

    /**
     * 旧「串行 / 并行」→ 新「并发位数」平滑迁移。
     *
     * <p>仅当库中<b>存在旧键且未保存过新键</b>时才换算，避免默认值覆盖用户已配置的位数：
     * 径赛 trackMode（serial→1、parallel→2）写入 trackSlots；
     * 田赛 fieldMode（serial→1、parallel→2）写入 fieldSlots。</p>
     */
    private void applyLegacyModeMigration(Map<String, Object> def) {
        SystemConfig config = systemConfigRepository.findByConfigKey("meet_schedule").orElse(null);
        if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) return;
        try {
            Map<String, Object> raw = objectMapper.readValue(config.getConfigValue(),
                    new TypeReference<Map<String, Object>>() {});
            if (raw == null) return;
            if (raw.get("trackSlots") == null && raw.get("trackMode") != null) {
                def.put("trackSlots", "parallel".equalsIgnoreCase(String.valueOf(raw.get("trackMode")).trim()) ? 2 : 1);
            }
            if (raw.get("fieldSlots") == null && raw.get("fieldMode") != null) {
                def.put("fieldSlots", "serial".equalsIgnoreCase(String.valueOf(raw.get("fieldMode")).trim()) ? 1 : 2);
            }
        } catch (Exception e) {
            log.warn("解析 meet_schedule 旧串并行配置失败，按默认并发位数处理: {}", e.getMessage());
        }
    }

    /** 保存运动会日程配置 */
    public Map<String, Object> saveMeetSchedule(Map<String, Object> body) {
        // 天数为显式配置时，自动补齐/裁剪 dayConfigs 使其与 days 一致
        int days = intOf(body.get("days"), 0);
        if (days > 0) {
            List<Map<String, Object>> dayConfigs = castList(body.get("dayConfigs"));
            String startDate = strOf(body.get("startDate"), null);
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (int d = 1; d <= days; d++) {
                final int dayIndex = d;
                Map<String, Object> existing = dayConfigs.stream()
                        .filter(x -> intOf(x.get("day"), -1) == dayIndex)
                        .findFirst().orElse(null);
                Map<String, Object> dayCfg = existing != null
                        ? new LinkedHashMap<>(existing)
                        : defaultDayConfig(dayIndex);
                dayCfg.put("day", dayIndex);
                if (startDate != null && !startDate.isBlank()) {
                    dayCfg.putIfAbsent("date", shiftDate(startDate, d - 1));
                }
                if (dayCfg.get("slots") == null) {
                    dayCfg.put("slots", defaultSlots());
                }
                normalized.add(dayCfg);
            }
            body.put("dayConfigs", normalized);
        }
        // 年级出场顺序去冻结：若提交的顺序与「年级管理」当前推导一致（=未自定义），
        // 归一化为空，使后续在「年级管理」调整 sortOrder 依然能实时传导到赛程。
        if (body.containsKey("gradeOrder") && body.get("gradeOrder") instanceof List<?> list) {
            List<String> incoming = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) incoming.add(String.valueOf(o).trim());
            }
            List<String> derived = getGradeOrder();
            body.put("gradeOrder", incoming.equals(derived) ? new ArrayList<String>() : incoming);
        }
        writeJsonConfig("meet_schedule", body);
        log.info("运动会日程配置已保存");
        return getMeetSchedule();
    }

    /**
     * 年级出场顺序（名称列表）。按 grades 配置的 sortOrder 升序，
     * 未配置 sortOrder 时保持录入顺序 —— 管理员可在「年级管理」中自由调整，不硬编码。
     */
    @Transactional(readOnly = true)
    public List<String> getGradeOrder() {
        return gradeService.getGradeOrder();
    }

    private Map<String, Object> defaultMeetSchedule() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("meetName", "校园田径运动会");
        def.put("startDate", java.time.LocalDate.now().plusDays(30).toString());
        def.put("days", 2);
        List<Map<String, Object>> dayConfigs = new ArrayList<>();
        dayConfigs.add(defaultDayConfig(1));
        dayConfigs.add(defaultDayConfig(2));
        def.put("dayConfigs", dayConfigs);
        def.put("gradeOrder", new ArrayList<>());
        // 并发位数（取代原 串行/并行 开关）：1 = 串行（同一时刻只进行 1 个项目）；n = 同时进行 n 个项目
        def.put("trackSlots", 1);
        def.put("fieldSlots", 2);
        // 自定义项目编排顺序：eventId 有序列表（田赛 + 径赛混排）；空 = 按项目排序号
        def.put("eventOrder", new ArrayList<>());
        // 田赛分组：同组田赛安排在「同一时段」并行进行，形如 [{name, eventIds:[...]}]
        def.put("fieldGroups", new ArrayList<>());
        def.put("defaultDurationMinutes", 30);
        def.put("defaultIntervalMinutes", 5);
        // 单组用时 / 田赛每人次用时，用于估算项目时长
        def.put("heatMinutes", 6);
        def.put("fieldPerAthleteMinutes", 3);
        // B05/U07：项目间隔下限（避免紧贴）+ 压缩告警阈值（被压到预计用时的 1/ratio 以下即告警）
        def.put("minIntervalMinutes", 5);
        def.put("compressionWarnRatio", 1.5);
        // B07/U06：预赛→决赛最小间隔（默认 45 分钟，给成绩确认/晋级公布/决赛检录留出时间）
        def.put("finalMinGapMinutes", 45);
        // 场地：名称 + 编码（编码用于标识/展示；并数上限取决于场地数量，故场地需先录全）
        List<Map<String, Object>> venues = new ArrayList<>();
        venues.add(defaultVenue("田径场", "TRACK"));
        venues.add(defaultVenue("田赛A区", "FIELD_A"));
        venues.add(defaultVenue("田赛B区", "FIELD_B"));
        def.put("venues", venues);
        return def;
    }

    private Map<String, Object> defaultVenue(String name, String code) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("code", code);
        return v;
    }

    private Map<String, Object> defaultDayConfig(int day) {
        Map<String, Object> dayCfg = new LinkedHashMap<>();
        dayCfg.put("day", day);
        dayCfg.put("date", null);
        dayCfg.put("slots", defaultSlots());
        return dayCfg;
    }

    /** 默认时段：上午 08:00-11:30，下午 14:00-17:30 */
    private List<Map<String, Object>> defaultSlots() {
        List<Map<String, Object>> slots = new ArrayList<>();
        slots.add(slot("AM", "上午", "08:00", "11:30"));
        slots.add(slot("PM", "下午", "14:00", "17:30"));
        return slots;
    }

    private Map<String, Object> slot(String key, String name, String start, String end) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("name", name);
        s.put("start", start);
        s.put("end", end);
        return s;
    }

    /** 起始日期 + 偏移天数 → 日期字符串 */
    private String shiftDate(String startDate, int offset) {
        try {
            return java.time.LocalDate.parse(startDate).plusDays(offset).toString();
        } catch (Exception e) {
            return startDate;
        }
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String strOf(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object v) {
        return v instanceof List ? new ArrayList<>((List<Map<String, Object>>) v) : new ArrayList<>();
    }

    // ==================== 编排规则（arrange_rule）====================

    /** 读取编排规则（含默认值兜底） */
    @Transactional(readOnly = true)
    public Map<String, Object> getArrangeRule() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("hard_constraints", new LinkedHashMap<>(Map.of(
                "ban_cross_grade", true, "gender_separate", true)));
        Map<String, Object> soft = new LinkedHashMap<>();
        soft.put("ban_same_class_same_lane", false);
        soft.put("prefer_diff_heat", true);
        soft.put("prefer_diff_lane", true);
        soft.put("scramble_across_classes", false);
        soft.put("center_best_athletes", false);
        soft.put("same_class_max_per_heat", 3);
        def.put("soft_constraints", soft);
        def.put("algorithm_params", new LinkedHashMap<>(Map.of(
                "max_attempts", 1000, "timeout_seconds", 30, "optimization_rounds", 3)));
        return readJsonConfig("arrange_rule", def);
    }

    /** 保存编排规则 */
    public Map<String, Object> saveArrangeRule(Map<String, Object> body) {
        writeJsonConfig("arrange_rule", body);
        log.info("编排规则已保存");
        return getArrangeRule();
    }

    // ==================== 积分规则（scoring_rule）====================

    /** 读取积分规则（含默认值兜底） */
    @Transactional(readOnly = true)
    public Map<String, Object> getScoringRule() {
        Map<String, Object> def = new LinkedHashMap<>();
        Map<String, Object> rankScores = new LinkedHashMap<>();
        rankScores.put("1", 9); rankScores.put("2", 7); rankScores.put("3", 6);
        rankScores.put("4", 5); rankScores.put("5", 4); rankScores.put("6", 3);
        rankScores.put("7", 2); rankScores.put("8", 1);
        def.put("rank_scores", rankScores);
        def.put("tie_handling", "same_rank");
        def.put("record_bonus_enabled", false);
        def.put("record_bonus", 10);
        def.put("participation_score_enabled", false);
        def.put("participation_score", 1);
        def.put("relay_multiplier", 2.0);
        def.put("team_score_type", "class");
        def.put("team_score_sort", "total_score");
        return readJsonConfig("scoring_rule", def);
    }

    /** 保存积分规则 */
    public Map<String, Object> saveScoringRule(Map<String, Object> body) {
        writeJsonConfig("scoring_rule", body);
        log.info("积分规则已保存");
        return getScoringRule();
    }

    // ==================== 通用 JSON 配置读写 ====================

    private Map<String, Object> readJsonConfig(String key, Map<String, Object> defaults) {
        SystemConfig config = systemConfigRepository.findByConfigKey(key).orElse(null);
        if (config == null || config.getConfigValue() == null) return defaults;
        try {
            Map<String, Object> saved = objectMapper.readValue(config.getConfigValue(),
                    new TypeReference<Map<String, Object>>() {});
            if (saved == null) return defaults;
            // 深度合并：以默认结构为底，已保存值覆盖
            return deepMerge(defaults, saved);
        } catch (Exception e) {
            log.warn("解析配置失败: key={}, 使用默认值", key, e);
            return defaults;
        }
    }

    private void writeJsonConfig(String key, Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("配置序列化失败: " + e.getMessage());
        }
        SystemConfig config = systemConfigRepository.findByConfigKey(key)
                .orElse(SystemConfig.builder().configKey(key).build());
        config.setConfigValue(json);
        config.setConfigType("rule");
        config.setUpdatedAt(LocalDateTime.now());
        systemConfigRepository.save(config);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object baseVal = result.get(e.getKey());
            Object newVal = e.getValue();
            if (baseVal instanceof Map && newVal instanceof Map) {
                result.put(e.getKey(), deepMerge((Map<String, Object>) baseVal, (Map<String, Object>) newVal));
            } else if (newVal != null) {
                result.put(e.getKey(), newVal);
            }
        }
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
