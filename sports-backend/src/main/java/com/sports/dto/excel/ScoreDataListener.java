package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.exception.ExcelDataConvertException;
import com.alibaba.excel.read.listener.ReadListener;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.service.excel.ExcelColumnMapping;
import com.sports.service.result.ResultService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 成绩 Excel 导入监听器（表头驱动版）。
 *
 * <p>旧实现按 {@code @ExcelProperty(index)} 固定列号 + Integer 类型字段读所有 Sheet：
 * 真实学校的成绩表只要列序不同（如第 4/5 列是「年级/班级」文本「高一」），
 * EasyExcel 的 IntegerStringConverter 就抛 {@code NumberFormatException}，整次导入被炸掉。</p>
 *
 * <p>新版规则：</p>
 * <ul>
 *   <li><b>全 String 读取</b>：无模型类读 Map&lt;Integer,String&gt;，从根上消除 Integer 转换异常；</li>
 *   <li><b>表头别名定位</b>：每 Sheet 首行按 {@link ExcelColumnMapping} 别名识别列
 *       （任意列序、多余列「年级/班级」自动忽略）；识别不出（&lt;2 核心列）时退回旧固定列序兜底；</li>
 *   <li><b>容错解析</b>：组别/道次「第3组」「3组」「A」「高一」→ 提取数字或回退编排信息；
 *       风速「1.5m/s」→ 提取数值；</li>
 *   <li><b>项目定位</b>：编码优先、名称兜底（成绩表常填「100米」而非「100M」）；</li>
 *   <li><b>运动员定位</b>：号码布 或 学号 或 姓名（与多表导入 processScoreRow 同口径）。</li>
 * </ul>
 */
@Slf4j
public class ScoreDataListener implements ReadListener<Map<Integer, String>> {

    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final AthleteRepository athleteRepository;
    private final ArrangementRepository arrangementRepository;

    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final List<Map<String, Object>> skipped = new ArrayList<>();
    private int successCount = 0;
    private int errorCount = 0;

    // 修复（优化层）：批量落库 + 导入内幂等/冲突判定
    private final List<Result> batch = new ArrayList<>();
    private final Map<String, String> seenRawByKey = new HashMap<>();
    private static final int BATCH_SIZE = 500;

    // ==================== 表头驱动的列定位 ====================

    /** 本监听器关心的列键；顺序即同名列的裁决顺序（先到先得） */
    private static final List<String> SCORE_KEYS = List.of(
            "eventCode", "athleteNumber", "studentId", "athleteName",
            "rawTime", "heat", "lane", "windSpeed", "remark", "grade", "className");
    /** 判定「这行是表头」所需的最少核心列数 */
    private static final int MIN_CORE_COLS = 2;
    private static final List<String> CORE_KEYS =
            List.of("eventCode", "athleteNumber", "studentId", "athleteName", "rawTime");
    /** 无表头/表头不可识别时的兜底列序（与旧 ScoreExcelModel 固定 index 完全一致） */
    private static final Map<String, Integer> FIXED_INDEX = Map.of(
            "eventCode", 0, "athleteNumber", 1, "athleteName", 2,
            "rawTime", 3, "heat", 4, "lane", 5, "windSpeed", 6, "remark", 7);

    private boolean sheetInitialized = false;
    private Integer currentSheetNo = null;
    private boolean headerChecked = false;
    private Map<String, Integer> cols = FIXED_INDEX;

    public ScoreDataListener(ResultRepository resultRepository, EventRepository eventRepository,
                             AthleteRepository athleteRepository, ArrangementRepository arrangementRepository) {
        this.resultRepository = resultRepository;
        this.eventRepository = eventRepository;
        this.athleteRepository = athleteRepository;
        this.arrangementRepository = arrangementRepository;
    }

    @Override
    public void invoke(Map<Integer, String> row, AnalysisContext context) {
        int rowNum = context.readRowHolder().getRowIndex() + 1;
        Integer sheetNo = context.readSheetHolder() != null
                ? context.readSheetHolder().getSheetNo() : null;
        String sheetName = context.readSheetHolder() != null
                ? context.readSheetHolder().getSheetName() : null;

        // B02/U13 修复：成绩导入走 doReadAll() 读取全部 Sheet，而模板与导出表都会带一个
        // 「填写说明」Sheet。说明/辅助 Sheet 直接整表跳过（不产生任何 error）。
        if (isAuxiliarySheet(sheetName)) {
            return;
        }

        // 每 Sheet 独立定位列：换 Sheet 即重置（headRowNumber(0) 下首行即表头行）
        boolean newSheet = !sheetInitialized
                || (sheetNo != null && !sheetNo.equals(currentSheetNo));
        if (newSheet) {
            sheetInitialized = true;
            currentSheetNo = sheetNo;
            headerChecked = false;
            cols = FIXED_INDEX;
        }

        if (!headerChecked) {
            headerChecked = true;
            Map<String, Integer> byHeader = resolveColumnsByHeader(row);
            if (byHeader != null) {
                cols = byHeader;
                log.info("成绩导入: Sheet[{}] 按表头定位列 {}", sheetName, byHeader);
                return; // 表头行消费掉
            }
            cols = FIXED_INDEX;
            log.warn("成绩导入: Sheet[{}] 首行未识别出表头，退回固定列序 "
                    + "(0项目编码/1号码/2姓名/3成绩/4组别/5道次/6风速/7备注)", sheetName);
            // 表头识别失败：本行按数据行处理（无表头文件兼容）
        }
        processRow(row, rowNum, sheetName);
    }

    /**
     * 按表头文本定位列：精确别名匹配（大小写不敏感），一列只归一键、一键只占一列。
     * 核心列（项目编码/号码/学号/姓名/成绩）命中 ≥ {@value MIN_CORE_COLS} 才认定是表头。
     */
    private Map<String, Integer> resolveColumnsByHeader(Map<Integer, String> headerRow) {
        if (headerRow == null || headerRow.isEmpty()) return null;
        Map<String, Integer> found = new LinkedHashMap<>();
        int core = 0;
        for (Map.Entry<Integer, String> cell : headerRow.entrySet()) {
            String text = cell.getValue() == null ? "" : cell.getValue().trim();
            if (text.isEmpty()) continue;
            for (String key : SCORE_KEYS) {
                if (found.containsKey(key)) continue;
                if (matchesAlias(key, text)) {
                    found.put(key, cell.getKey());
                    if (CORE_KEYS.contains(key)) core++;
                    break;
                }
            }
        }
        return core >= MIN_CORE_COLS ? found : null;
    }

    private boolean matchesAlias(String key, String header) {
        for (String alias : ExcelColumnMapping.COLUMN_ALIASES.getOrDefault(key, List.of())) {
            if (alias.equalsIgnoreCase(header)) return true;
        }
        return false;
    }

    // ==================== 数据行处理 ====================

    private void processRow(Map<Integer, String> row, int rowNum, String sheetName) {
        String eventRef = str(row, "eventCode");
        String athleteNumber = str(row, "athleteNumber");
        String studentId = str(row, "studentId");
        String athleteName = str(row, "athleteName");
        String rawTime = str(row, "rawTime");

        // 空行跳过
        boolean blank = isBlank(eventRef) && isBlank(athleteNumber)
                && isBlank(studentId) && isBlank(athleteName);
        if (blank) {
            return;
        }

        // 成绩行必须能定位到运动员（号码布/学号/姓名）；否则视为说明/汇总行，计入 skipped 而非 error，
        // 既不污染错误清单，也不静默丢弃。
        if (isBlank(athleteNumber) && isBlank(studentId) && isBlank(athleteName)) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("row", rowNum);
            skip.put("sheet", sheetName);
            skip.put("value", eventRef);
            skip.put("reason", "无运动员标识（说明行或汇总行）");
            skipped.add(skip);
            return;
        }

        try {
            // 查找项目：编码优先、名称兜底
            Event event = null;
            if (!isBlank(eventRef)) {
                String ref = eventRef.trim();
                event = eventRepository.findByCode(ref)
                        .or(() -> eventRepository.findByNameAndIsEnabledTrue(ref))
                        .orElseThrow(() -> new RuntimeException(
                                "项目 '" + ref + "' 不存在（编码与名称均未匹配）"));
            }

            // 查找运动员：号码布/学号 优先，姓名兜底
            Athlete athlete = null;
            if (!isBlank(athleteNumber) || !isBlank(studentId)) {
                // B02/U02：按「号码布 或 学号」匹配——导入模板「填写说明」明确写了
                // 「运动员号码：填号码布编号；也可填学号（系统按号码/学号匹配运动员）」，
                // 但旧实现只调 findByNumber，学号分支从未生效。
                // 而号码布是「系统按规则生成」的，未生成时 number 全空、导出列会回填学号，
                // 于是导出的成绩表回导时报「号码簿 '102002' 不存在」——文档与实现对不上。
                String ref = !isBlank(athleteNumber) ? athleteNumber.trim() : studentId.trim();
                athlete = athleteRepository.findByNumber(ref)
                        .or(() -> athleteRepository.findByStudentId(ref))
                        .orElseThrow(() -> new RuntimeException(
                                "号码布/学号 '" + ref + "' 不存在（该列可填号码布编号或学号）"));
            } else {
                List<Athlete> byName = athleteRepository.findByName(athleteName.trim());
                if (byName.size() == 1) {
                    athlete = byName.get(0);
                } else if (byName.isEmpty()) {
                    throw new RuntimeException("运动员 '" + athleteName + "' 不存在");
                } else {
                    throw new RuntimeException("运动员 '" + athleteName + "' 存在 " + byName.size()
                            + " 个重名，请改用号码布编号或学号填写「运动员号码」列");
                }
            }

            if (event == null || athlete == null) {
                throw new RuntimeException("项目或运动员信息不完整");
            }

            String key = event.getId() + "_" + athlete.getId();
            String inRaw = rawTime != null ? rawTime.trim() : "";

            // 修复（优化层）：批量导入性能 + 导入内幂等/冲突判定。原实现逐行 save 且依赖「DB 实时落库」做去重；
            // 现用内存 seenRawByKey 维护本次导入已处理的 (项目,运动员)→成绩 映射，既支持批量 saveAll，
            // 又能在文件内做幂等/冲突判定（不依赖未刷盘的批次）。跨导入幂等仍由 DB 查询兜底。
            if (seenRawByKey.containsKey(key)) {
                String exRaw = seenRawByKey.get(key);
                if (exRaw.equals(inRaw)) {
                    successCount++;
                    log.info("成绩已存在且一致，跳过（一致性校验幂等）: {} / {}", event.getCode(), athlete.getNumber());
                    return;
                }
                throw new RuntimeException("成绩冲突: 运动员 '" + athlete.getName() + "' 在项目 '"
                        + event.getName() + "' 已有成绩 " + exRaw + "，导入值 " + inRaw);
            }

            // B02/U02：一致性校验式去重——已存在且成绩一致则跳过（幂等，可作一致性校验），
            // 成绩不一致则明确报冲突，便于审计发现差异。
            Optional<Result> existing = resultRepository
                    .findByEventIdAndAthleteId(event.getId(), athlete.getId());
            if (existing.isPresent()) {
                Result ex = existing.get();
                String exRaw = ex.getRawTime() != null ? ex.getRawTime().trim() : "";
                if (exRaw.equals(inRaw)) {
                    successCount++;
                    log.info("成绩已存在且一致，跳过（一致性校验幂等）: {} / {}", event.getCode(), athlete.getNumber());
                    return;
                }
                throw new RuntimeException("成绩冲突: 运动员 '" + athlete.getName() + "' 在项目 '"
                        + event.getName() + "' 已有成绩 " + exRaw + "，导入值 " + inRaw);
            }

            // R-2 严密性：DNF/DNS/DSQ 等非完赛标记——模板填写说明承诺支持，但此前被当成畸形时间
            // 静默转 null 仍以 valid 落库，导致弃赛者被算作「有效成绩」且名次排到冠军前。
            // 现识别为独立状态（dnf/dns/dsq），自动退出计分（findAllValid 只取 valid）并排在项目末尾。
            String nonFinishStatus = ResultService.normalizeNonFinishStatus(rawTime);
            // R-3 严密性：非完赛标记已归一为 dnf/dns/dsq（timeSeconds=null）；
            // 否则走 resolveTimeSeconds——空白时间留空待补录，非空白但无法解析或越界一律抛异常，
            // 由外层统一计入 errors，杜绝「畸形时间静默以 valid 落库」的旧问题。
            Double timeSeconds = nonFinishStatus != null ? null : resolveTimeSeconds(rawTime);

            // 组别/道次容错解析（「第3组」→3、「高一」「A」→null）+ 编排信息回退
            Integer heat = parseIntTolerant(str(row, "heat"));
            Integer lane = parseIntTolerant(str(row, "lane"));
            if (heat == null || lane == null) {
                Optional<Arrangement> arr = arrangementRepository
                        .findByEventIdAndAthleteId(event.getId(), athlete.getId());
                if (arr.isPresent()) {
                    if (heat == null) heat = arr.get().getHeat();
                    if (lane == null) lane = arr.get().getLane();
                }
            }

            Result result = Result.builder()
                    .event(event)
                    .athlete(athlete)
                    .heat(heat)
                    .lane(lane)
                    .rawTime(inRaw)
                    .timeSeconds(timeSeconds)
                    .status(nonFinishStatus != null ? nonFinishStatus : "valid")
                    .remark(str(row, "remark"))
                    .enteredAt(java.time.LocalDateTime.now())
                    .createdAt(java.time.LocalDateTime.now())
                    .updatedAt(java.time.LocalDateTime.now())
                    .build();

            // 备注含破纪录标记（破纪录/纪录/记录/record，不区分大小写）→ 标记为破纪录成绩，
            // 使「破纪录」可由成绩导入 备注 列直接带入，records 榜与团体加分据此计算。
            if (isRecordRemark(result.getRemark())) {
                result.setIsRecord(true);
            }

            String wind = str(row, "windSpeed");
            if (!isBlank(wind)) {
                Double w = parseDoubleTolerant(wind);
                if (w != null) {
                    result.setWindSpeed(w);
                }
            }

            // 修复（优化层）：批量累积，达到阈值后一次性 saveAll，避免逐条 save 的 N 次写库开销
            batch.add(result);
            seenRawByKey.put(key, inRaw);
            successCount++;
            if (batch.size() >= BATCH_SIZE) {
                flush();
            }

        } catch (Exception e) {
            errorCount++;
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("row", rowNum);
            error.put("sheet", sheetName);
            error.put("message", e.getMessage());
            errors.add(error);
            log.warn("行 {} 导入成绩失败: {}", rowNum, e.getMessage());
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush();
        log.info("成绩导入完成: 成功 {} 条, 失败 {} 条, 跳过(说明/汇总行) {} 条",
                successCount, errorCount, skipped.size());
    }

    /**
     * 安全网：单元格级转换异常（如极少数无法字符串化的特殊类型）只跳过该行并计错，
     * 绝不再炸掉整次导入（旧实现的痛点正是「一个坏单元格 → 全部失败」）。
     */
    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
        if (exception instanceof ExcelDataConvertException ce) {
            errorCount++;
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("row", (ce.getRowIndex() != null ? ce.getRowIndex() : -1) + 1);
            error.put("message", "第 " + (ce.getColumnIndex() != null ? ce.getColumnIndex() + 1 : "?")
                    + " 列单元格格式无法解析，已跳过该行: " + ce.getCellData());
            errors.add(error);
            log.warn("成绩导入: 行 {} 单元格转换失败已跳过: {}", ce.getRowIndex(), ce.getCellData());
            return;
        }
        throw exception;
    }

    // ==================== 工具方法 ====================

    /** 按定位好的列取单元格值（trim；列未识别出返回 null） */
    private String str(Map<Integer, String> row, String key) {
        Integer idx = cols.get(key);
        if (idx == null) return null;
        String v = row.get(idx);
        return v == null ? null : v.trim();
    }

    /** 容错整数解析：提取首个数字串（「第3组」→3、「3组」→3）；纯文本（「高一」「A」）→ null */
    private static final Pattern INT_TOKEN = Pattern.compile("\\d+");

    private static Integer parseIntTolerant(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        Matcher m = INT_TOKEN.matcher(t);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 容错浮点解析：提取首个数值串（「1.5m/s」→1.5）；无法提取 → null */
    private static final Pattern DOUBLE_TOKEN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private static Double parseDoubleTolerant(String s) {
        if (s == null) return null;
        Matcher m = DOUBLE_TOKEN.matcher(s.trim());
        if (m.find()) {
            try {
                return Double.parseDouble(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 修复（优化层）：将累积的 Result 批次一次性落库（避免逐条 save 的 N 次写库开销） */
    private void flush() {
        if (!batch.isEmpty()) {
            resultRepository.saveAll(batch);
            batch.clear();
        }
    }

    public int getSuccessCount() { return successCount; }
    public int getErrorCount() { return errorCount; }
    public List<Map<String, Object>> getErrors() { return errors; }
    public List<Map<String, Object>> getSkipped() { return skipped; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** 备注是否为破纪录标记（破纪录/破记录/新纪录/新记录/纪录/记录/record，不区分大小写） */
    private static boolean isRecordRemark(String remark) {
        if (remark == null || remark.isBlank()) return false;
        String r = remark.trim().toLowerCase();
        return r.contains("破纪录") || r.contains("破记录")
                || r.contains("新纪录") || r.contains("新记录")
                || r.contains("纪录") || r.contains("记录")
                || r.contains("record");
    }

    /**
     * 辅助 Sheet 判定：模板/导出表里的「填写说明」Sheet 不是数据。
     * 只按明确的辅助语义匹配，避免误伤以项目编码命名的数据 Sheet（如 M100 / LJM）。
     */
    private static boolean isAuxiliarySheet(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        return n.contains("说明") || n.contains("模板")
                || n.equalsIgnoreCase("README") || n.equalsIgnoreCase("Notes");
    }

    /** 时间解析：支持 12.34 / 2:35.67 / 2:35 等格式 */
    private Double parseTimeToSeconds(String time) {
        if (time == null || time.isBlank()) return null;
        time = time.trim();
        try {
            if (time.contains(":")) {
                String[] parts = time.split(":");
                int minutes = Integer.parseInt(parts[0]);
                double seconds = Double.parseDouble(parts[1]);
                return minutes * 60.0 + seconds;
            }
            return Double.parseDouble(time);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * R-3 严密性：解析成绩时间并做边界校验。
     * 返回 null 仅当原始时间为空——属合法情形（留待后续补录，仍以 valid 落库）。
     * 非空白但无法解析为数值/时间（如 "12.5.3"），或超出合理区间（<=0 或 >100000 秒），
     * 一律抛 RuntimeException，由 invoke 外层的 try/catch 统一计入 errors，
     * 而非像旧实现那样被 parseTimeToSeconds 静默转 null 后以 valid 落库。
     */
    private Double resolveTimeSeconds(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return null;
        }
        Double secs = parseTimeToSeconds(rawTime);
        if (secs == null) {
            throw new RuntimeException("成绩格式非法: '" + rawTime.trim()
                    + "' 既不是合法时间（如 12.34 / 2:35.67），也不是 DNF/DNS/DSQ 标记");
        }
        if (secs <= 0.0 || secs > 100000.0) {
            throw new RuntimeException("成绩超出合理范围: " + secs + " 秒（须 >0 且 <=100000）");
        }
        return secs;
    }
}
