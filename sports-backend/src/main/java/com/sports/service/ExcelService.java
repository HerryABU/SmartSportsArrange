package com.sports.service;

import com.alibaba.excel.EasyExcel;
import com.sports.dto.excel.*;
import com.sports.entity.*;
import com.sports.repository.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Excel 导入导出服务 — 重构版 V3.0
 * 支持：模板下载、导入预览（智能列映射+多Sheet）、带映射导入、批量导入、导出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelService {

    private final AthleteRepository athleteRepository;
    private final RegistrationRepository registrationRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final ArrangementRepository arrangementRepository;

    // ==================== 列别名映射表 ====================

    /** 列别名：标准字段 → 可能的列名列表 */
    static final Map<String, List<String>> COLUMN_ALIASES = new LinkedHashMap<>();

    /** 每种类型的可用字段：fieldName → 中文标签 */
    static final Map<String, Map<String, String>> TYPE_FIELDS = new LinkedHashMap<>();

    static {
        initColumnAliases();
        initTypeFields();
    }

    private static void initColumnAliases() {
        COLUMN_ALIASES.put("name",         List.of("姓名","名字","name","运动员名称","学生姓名","选手","学生"));
        COLUMN_ALIASES.put("gender",       List.of("性别","sex","gender","男女"));
        COLUMN_ALIASES.put("grade",        List.of("年级","grade","年段","年级名称"));
        COLUMN_ALIASES.put("className",    List.of("班级","class","班别","班级名称","班","班号"));
        COLUMN_ALIASES.put("number",       List.of("号码簿","号码布","号码","number","参赛号","编号","号码牌"));
        COLUMN_ALIASES.put("studentId",    List.of("学号","studentId","学籍号"));
        COLUMN_ALIASES.put("idCard",       List.of("身份证号","身份证","idCard"));
        COLUMN_ALIASES.put("birthDate",    List.of("出生日期","生日","birthDate"));
        COLUMN_ALIASES.put("emergencyContact", List.of("紧急联系人","联系人","emergencyContact"));
        COLUMN_ALIASES.put("emergencyPhone",   List.of("紧急联系电话","联系电话","电话","phone"));
        COLUMN_ALIASES.put("healthStatus",     List.of("健康状况","健康","healthStatus"));
        COLUMN_ALIASES.put("remark",       List.of("备注","remark","说明","描述"));
        COLUMN_ALIASES.put("eventCode",    List.of("项目编码","项目代码","code","eventCode"));
        COLUMN_ALIASES.put("eventName",    List.of("项目名称","项目","eventName"));
        COLUMN_ALIASES.put("athleteNumber",List.of("运动员号码","号码","运动员编号","athleteNumber"));
        COLUMN_ALIASES.put("athleteName",  List.of("运动员姓名","姓名","运动员","athleteName"));
        COLUMN_ALIASES.put("rawTime",      List.of("成绩","时间","result","rawTime","比赛成绩","用时"));
        COLUMN_ALIASES.put("heat",         List.of("组别","组","heat","组号","轮次"));
        COLUMN_ALIASES.put("lane",         List.of("道次","道","lane","跑道"));
        COLUMN_ALIASES.put("windSpeed",    List.of("风速","windSpeed"));
        COLUMN_ALIASES.put("rank",         List.of("名次","rank","排名","第几名"));
        COLUMN_ALIASES.put("score",        List.of("积分","score","point","得分"));
        COLUMN_ALIASES.put("classCode",    List.of("班级编码","班级编号","classCode"));
        COLUMN_ALIASES.put("teacherName",  List.of("班主任","teacherName","班主任姓名"));
        COLUMN_ALIASES.put("username",     List.of("用户名","账号","username","用户名"));
        COLUMN_ALIASES.put("password",     List.of("密码","password"));
        COLUMN_ALIASES.put("realName",     List.of("姓名","真实姓名","realName"));
        COLUMN_ALIASES.put("role",         List.of("角色","role","身份"));
        COLUMN_ALIASES.put("phone",        List.of("电话","手机号","手机","phone"));
        COLUMN_ALIASES.put("category",     List.of("类别","类型","category","项目类别"));
        COLUMN_ALIASES.put("genderLimit",  List.of("性别限制","性别","genderLimit"));
        COLUMN_ALIASES.put("defaultLanes", List.of("跑道数","道数","lanes","defaultLanes"));
        COLUMN_ALIASES.put("scoringType",  List.of("计分方式","计分规则","scoringType"));
        COLUMN_ALIASES.put("record",       List.of("校纪录","纪录","record"));
    }

    private static void initTypeFields() {
        Map<String, String> athleteFields = new LinkedHashMap<>();
        athleteFields.put("name","姓名"); athleteFields.put("gender","性别");
        athleteFields.put("grade","年级"); athleteFields.put("className","班级");
        athleteFields.put("studentId","学号"); athleteFields.put("number","号码布编号");
        athleteFields.put("idCard","身份证号"); athleteFields.put("birthDate","出生日期");
        athleteFields.put("emergencyContact","紧急联系人");
        athleteFields.put("emergencyPhone","紧急联系电话");
        athleteFields.put("healthStatus","健康状况"); athleteFields.put("remark","备注");
        TYPE_FIELDS.put("athlete", athleteFields);
        TYPE_FIELDS.put("score", new LinkedHashMap<>(Map.of(
            "eventCode","项目编码","athleteNumber","运动员号码","athleteName","运动员姓名",
            "rawTime","成绩","heat","组别","lane","道次","windSpeed","风速","remark","备注")));
        TYPE_FIELDS.put("registration", new LinkedHashMap<>(Map.of(
            "eventCode","项目编码","athleteNumber","运动员号码","athleteName","运动员姓名",
            "grade","年级","className","班级","remark","备注")));
        TYPE_FIELDS.put("class", new LinkedHashMap<>(Map.of(
            "name","班级名称","code","班级编码","grade","年级","teacherName","班主任")));
        TYPE_FIELDS.put("user", new LinkedHashMap<>(Map.of(
            "username","用户名","password","密码","realName","姓名","role","角色","phone","电话")));
        TYPE_FIELDS.put("event", new LinkedHashMap<>(Map.of(
            "name","项目名称","code","项目编码","category","类别","genderLimit","性别限制",
            "defaultLanes","跑道数","scoringType","计分规则","record","校纪录")));
    }

    /** 智能匹配列名→标准字段 */
    private static String matchColumnName(String colName) {
        if (colName == null || colName.isBlank()) return null;
        String s = colName.trim().toLowerCase().replaceAll("[\\s\\-_/（）()]", "");
        for (Map.Entry<String, List<String>> e : COLUMN_ALIASES.entrySet()) {
            for (String alias : e.getValue()) {
                String a = alias.toLowerCase().replaceAll("[\\s\\-_/（）()]", "");
                if (s.equals(a) || s.contains(a) || a.contains(s))
                    return e.getKey();
            }
        }
        return null;
    }

    private static String getFieldLabel(String type, String field) {
        Map<String, String> fields = TYPE_FIELDS.getOrDefault(type, TYPE_FIELDS.get("athlete"));
        return fields.getOrDefault(field, field);
    }

    // ==================== 模板下载 ====================

    public void getTemplate(String type, HttpServletResponse response) {
        String t = type != null ? type.toLowerCase() : "";
        String fileName;
        List<List<String>> sheet = new ArrayList<>();

        switch (t) {
            case "athlete" -> {
                fileName = "运动员导入模板.xlsx";
                sheet.add(List.of("姓名","性别","年级","班级","学号","号码布编号",
                        "身份证号","出生日期","紧急联系人","紧急联系电话","健康状况","备注"));
                sheet.add(List.of("张三","男","高一年级","高一1班","2024001","010101",
                        "","","张父","13900139000","良好",""));
            }
            case "score" -> {
                fileName = "成绩导入模板.xlsx";
                sheet.add(List.of("项目编码","运动员号码","运动员姓名","成绩","组别","道次","风速","备注"));
                sheet.add(List.of("100M","010101","张三","12.34","1","3","",""));
            }
            case "registration" -> {
                fileName = "报名导入模板.xlsx";
                sheet.add(List.of("项目编码","运动员号码","运动员姓名","年级","班级","备注"));
                sheet.add(List.of("100M","010101","张三","高一年级","高一1班",""));
            }
            case "class" -> {
                fileName = "班级导入模板.xlsx";
                sheet.add(List.of("班级名称","班级编码","年级","班主任"));
                sheet.add(List.of("高一1班","G1-01","高一年级","张老师"));
            }
            case "user" -> {
                fileName = "用户导入模板.xlsx";
                sheet.add(List.of("用户名","密码","姓名","角色","电话"));
                sheet.add(List.of("teacher01","123456","张老师","TEACHER","13800138000"));
            }
            case "event" -> {
                // 表格2 布局：A代码 / B项目 / C是否田径 / D道次（田赛=0）
                fileName = "项目表导入模板_表格2.xlsx";
                sheet.add(List.of("代码","项目","是否田径","道次","性别","年级组","是否团体","团体人数",
                        "调度模式","场地","最大用时(分)","间隔(分)"));
                sheet.add(List.of("100M","100米","是","8","男子组","高一年级","否","0","serial","田径场","20","10"));
                sheet.add(List.of("4X100M","4×100米接力","是","8","男子组","高一年级","是","4","serial","田径场","30","15"));
                sheet.add(List.of("TY_F","跳远(女子)","否","0","女子组","高一年级","否","0","parallel","田赛A区","90","10"));
            }
            default -> {
                fileName = "导入模板.xlsx";
                sheet.add(List.of("请指定模板类型"));
            }
        }

        setExcelResponse(response, fileName);
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> headCols = sheet.get(0).stream().map(List::of).collect(Collectors.toList());
            EasyExcel.write(out).head(headCols).sheet("Sheet1").doWrite(sheet.size() > 1 ? sheet.subList(1, sheet.size()) : List.of());
        } catch (IOException e) {
            throw new RuntimeException("模板下载失败: " + e.getMessage());
        }
    }

    // ==================== 导入预览（智能列映射 + 多Sheet + 详细预览） ====================

    public Map<String, Object> previewImport(MultipartFile file) {
        String filename = file.getOriginalFilename();
        log.info("预览导入: {}", filename);
        String type = detectType(filename);

        List<Map<String, Object>> sheets = new ArrayList<>();
        // 多 Sheet 预览：依次读取前若干个 sheet
        for (int si = 0; si < 10; si++) {
            List<Map<Integer, String>> rows;
            try (InputStream in = file.getInputStream()) {
                rows = EasyExcel.read(in).sheet(si).headRowNumber(0).doReadSync();
            } catch (IOException e) {
                throw new RuntimeException("预览失败: " + e.getMessage());
            } catch (Exception e) {
                // 无更多 sheet
                if (si == 0) throw new RuntimeException("读取Excel失败: " + e.getMessage());
                break;
            }

            if (rows.isEmpty()) {
                if (si == 0) break;
                break;
            }

            Map<Integer, String> headerRow = rows.get(0);
            List<String> headers = new ArrayList<>();
            int maxCol = headerRow.keySet().stream().max(Integer::compareTo).orElse(-1);
            for (int c = 0; c <= maxCol; c++) headers.add(headerRow.getOrDefault(c, ""));

            // 智能列映射
            Map<String, String> suggestedMappings = new LinkedHashMap<>();
            Map<String, String> mappingLabels = new LinkedHashMap<>();
            Map<String, List<String>> fieldOptions = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String field = matchColumnName(headers.get(c));
                if (field != null) {
                    suggestedMappings.put(String.valueOf(c), field);
                    mappingLabels.put(field, getFieldLabel(type, field));
                    fieldOptions.computeIfAbsent(field, k -> new ArrayList<>()).add(headers.get(c));
                }
            }

            Map<String, String> availableFields = TYPE_FIELDS.getOrDefault(type, TYPE_FIELDS.get("athlete"));

            // 预览数据行（最多100行）
            List<List<String>> previewRows = new ArrayList<>();
            int rowsToShow = Math.min(rows.size(), 100);
            for (int r = 0; r < rowsToShow; r++) {
                Map<Integer, String> row = rows.get(r);
                List<String> rowData = new ArrayList<>();
                for (int c = 0; c <= maxCol; c++) rowData.add(row.getOrDefault(c, ""));
                previewRows.add(rowData);
            }

            Map<String, Object> sheetInfo = new LinkedHashMap<>();
            sheetInfo.put("index", si); sheetInfo.put("name", "Sheet" + (si + 1));
            sheetInfo.put("headers", headers);
            sheetInfo.put("previewRows", previewRows);
            sheetInfo.put("totalRows", rows.size());
            sheetInfo.put("previewCount", rowsToShow);
            sheetInfo.put("suggestedMappings", suggestedMappings);
            sheetInfo.put("mappingLabels", mappingLabels);
            sheetInfo.put("fieldOptions", fieldOptions);
            sheetInfo.put("availableFields", availableFields);
            sheets.add(sheetInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", filename);
        result.put("fileSize", file.getSize());
        result.put("type", type);
        result.put("sheets", sheets);
        return result;
    }

    private String detectType(String filename) {
        if (filename == null) return "athlete";
        String l = filename.toLowerCase();
        if (l.contains("score")||l.contains("成绩")) return "score";
        else if (l.contains("registration")||l.contains("报名")) return "registration";
        else if (l.contains("class")||l.contains("班级")) return "class";
        else if (l.contains("user")||l.contains("用户")) return "user";
        else if (l.contains("event")||l.contains("项目")) return "event";
        return "athlete";
    }

    // ==================== 带列映射的导入 ====================

    @Transactional
    public Map<String, Object> importWithMapping(MultipartFile file, Map<String, Object> mapping) {
        String type = toStringSafe(mapping.getOrDefault("type", "athlete"));
        int sheetIndex = toIntSafe(mapping.getOrDefault("sheetIndex", 0));
        boolean hasHeader = toBoolSafe(mapping.getOrDefault("hasHeader", true));

        @SuppressWarnings("unchecked")
        Map<String, String> columnMap;
        Object cmObj = mapping.get("columnMap");
        if (cmObj instanceof Map) {
            columnMap = (Map<String, String>) cmObj;
        } else if (cmObj instanceof String) {
            // JSON string from multipart form
            try {
                columnMap = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue((String) cmObj, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                throw new RuntimeException("columnMap 解析失败: " + e.getMessage());
            }
        } else {
            columnMap = Map.of();
        }

        int startRow = hasHeader ? 1 : 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        int success = 0;
        List<Map<Integer, String>> rows = List.of();

        try (InputStream in = file.getInputStream()) {
            rows = EasyExcel.read(in).sheet(sheetIndex).headRowNumber(0).doReadSync();

            for (int r = startRow; r < rows.size(); r++) {
                Map<Integer, String> row = rows.get(r);
                try {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : columnMap.entrySet()) {
                        int col = Integer.parseInt(e.getKey());
                        String field = e.getValue();
                        String val = row.getOrDefault(col, "");
                        if (val != null && !val.isBlank()) values.put(field, val.trim());
                    }
                    processRow(type, values);
                    success++;
                } catch (Exception ex) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", r + 1);
                    err.put("message", ex.getMessage());
                    errors.add(err);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size() - startRow);
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        log.info("映射导入完成: type={}, 成功{}条, 失败{}条", type, success, errors.size());
        return result;
    }

    @Transactional
    protected void processRow(String type, Map<String, String> values) {
        switch (type) {
            case "athlete" -> processAthleteRow(values);
            case "score" -> processScoreRow(values);
            case "registration" -> processRegistrationRow(values);
            case "class" -> processClassRow(values);
            case "event" -> processEventRow(values);
            default -> throw new RuntimeException("不支持的导入类型: " + type);
        }
    }

    private void processAthleteRow(Map<String, String> v) {
        String name = v.get("name");
        if (name == null) throw new RuntimeException("姓名为空");

        ClassInfo classInfo = null;
        String className = v.get("className");
        if (className != null) {
            classInfo = classInfoRepository.findByName(className).orElse(null);
            if (classInfo == null) throw new RuntimeException("班级不存在: " + className);
        }

        String gender = mapGender(v.get("gender"));

        Athlete athlete = Athlete.builder()
                .name(name).gender(gender)
                .grade(v.get("grade"))
                .classInfo(classInfo)
                .number(v.get("number"))
                .studentId(v.get("studentId"))
                .idCard(v.get("idCard"))
                .emergencyContact(v.get("emergencyContact"))
                .emergencyPhone(v.get("emergencyPhone") != null ? v.get("emergencyPhone") : v.get("phone"))
                .healthStatus(v.get("healthStatus"))
                .remark(v.get("remark"))
                .status("normal").build();

        if (v.get("birthDate") != null) {
            try { athlete.setBirthDate(java.time.LocalDate.parse(v.get("birthDate"))); }
            catch (Exception e) { log.warn("日期格式错误: {}", v.get("birthDate")); }
        }

        athleteRepository.save(athlete);
    }

    private void processScoreRow(Map<String, String> v) {
        String eventCode = v.get("eventCode");
        Event event = eventCode != null ? eventRepository.findByCode(eventCode)
                .orElseThrow(() -> new RuntimeException("项目编码不存在: " + eventCode)) : null;

        String athleteNumber = v.get("athleteNumber");
        Athlete athlete = athleteNumber != null ? athleteRepository.findByNumber(athleteNumber)
                .orElseThrow(() -> new RuntimeException("号码簿不存在: " + athleteNumber)) : null;

        if (event == null || athlete == null) throw new RuntimeException("缺少项目或运动员信息");

        if (resultRepository.existsByEventIdAndAthleteId(event.getId(), athlete.getId()))
            throw new RuntimeException("已有成绩记录");

        Integer heat = parseIntSafe(v.get("heat"));
        Integer lane = parseIntSafe(v.get("lane"));
        if (heat == null || lane == null) {
            Optional<Arrangement> arr = arrangementRepository.findByEventIdAndAthleteId(event.getId(), athlete.getId());
            if (arr.isPresent()) { heat = arr.get().getHeat(); lane = arr.get().getLane(); }
        }

        Result result = Result.builder()
                .event(event).athlete(athlete).heat(heat).lane(lane)
                .rawTime(v.get("rawTime"))
                .timeSeconds(parseTimeToSeconds(v.get("rawTime")))
                .status("valid").remark(v.get("remark"))
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        if (v.get("windSpeed") != null) {
            try { result.setWindSpeed(Double.parseDouble(v.get("windSpeed"))); }
            catch (NumberFormatException ignored) {}
        }

        resultRepository.save(result);
    }

    private void processRegistrationRow(Map<String, String> v) {
        String eventCode = v.get("eventCode");
        Event event = eventCode != null ? eventRepository.findByCode(eventCode)
                .orElseThrow(() -> new RuntimeException("项目编码不存在: " + eventCode)) : null;

        String athleteNumber = v.get("athleteNumber");
        Athlete athlete = athleteNumber != null ? athleteRepository.findByNumber(athleteNumber)
                .orElseThrow(() -> new RuntimeException("号码簿不存在: " + athleteNumber)) : null;

        if (event == null || athlete == null) throw new RuntimeException("缺少项目或运动员信息");

        if (registrationRepository.existsByAthleteIdAndEventId(athlete.getId(), event.getId()))
            throw new RuntimeException("该运动员已报名此项目");

        Registration reg = Registration.builder()
                .athlete(athlete).event(event).status("approved")
                .registrationTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        registrationRepository.save(reg);
    }

    private void processClassRow(Map<String, String> v) {
        String name = v.get("name");
        if (name == null) throw new RuntimeException("班级名称为空");
        if (classInfoRepository.existsByName(name)) throw new RuntimeException("班级已存在: " + name);

        ClassInfo ci = ClassInfo.builder()
                .name(name).code(v.get("code")).grade(v.get("grade"))
                .teacherName(v.get("teacherName"))
                .isParticipating(true).build();
        classInfoRepository.save(ci);
    }

    private void processEventRow(Map<String, String> v) {
        String name = v.get("name");
        String code = v.get("code");
        if (name == null || code == null) throw new RuntimeException("项目名称和编码不能为空");
        if (eventRepository.existsByCode(code)) throw new RuntimeException("项目编码已存在: " + code);

        Event event = Event.builder()
                .name(name).code(code)
                .category(v.get("category"))
                .genderLimit(v.get("genderLimit"))
                .defaultLanes(parseIntSafe(v.get("defaultLanes"), 8))
                .scoringType(v.get("scoringType") != null ? v.get("scoringType") : "global")
                .record(v.get("record"))
                .isEnabled(true).sortOrder(0).build();
        eventRepository.save(event);
    }

    // ==================== 直接导入（兼容旧接口） ====================

    @Transactional
    public Map<String, Object> importAthletes(MultipartFile file) {
        log.info("Excel导入运动员: {}", file.getOriginalFilename());
        List<Athlete> athletes = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            AthleteDataListener listener = new AthleteDataListener(classInfoRepository, athletes, errors);
            EasyExcel.read(in, AthleteExcelModel.class, listener).sheet().doRead();
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }
        for (Athlete a : athletes) {
            try {
                if (a.getNumber() != null && athleteRepository.findByNumber(a.getNumber()).isPresent()) {
                    errors.add(Map.of("message", "号码簿已存在: " + a.getNumber()));
                    continue;
                }
                athleteRepository.save(a);
            } catch (Exception e) {
                errors.add(Map.of("message", e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", athletes.size() + errors.size());
        result.put("success", athletes.size());
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    @Transactional
    public Map<String, Object> importScores(MultipartFile file) {
        log.info("Excel导入成绩: {}", file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            ScoreDataListener listener = new ScoreDataListener(
                    resultRepository, eventRepository, athleteRepository, arrangementRepository);
            EasyExcel.read(in, ScoreExcelModel.class, listener).sheet().doRead();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", listener.getSuccessCount() + listener.getErrorCount());
            result.put("success", listener.getSuccessCount());
            result.put("failed", listener.getErrorCount());
            result.put("errors", listener.getErrors());
            return result;
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> importRegistrations(MultipartFile file) {
        log.info("Excel导入报名: {}", file.getOriginalFilename());
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            List<Map<Integer, String>> rows = EasyExcel.read(in).sheet().headRowNumber(1).doReadSync();
            for (int i = 0; i < rows.size(); i++) {
                Map<Integer, String> row = rows.get(i);
                try {
                    String eventCode = row.getOrDefault(0, "");
                    String athleteNumber = row.getOrDefault(1, "");
                    Event event = eventRepository.findByCode(eventCode.trim())
                            .orElseThrow(() -> new RuntimeException("项目编码不存在: " + eventCode));
                    Athlete athlete = athleteRepository.findByNumber(athleteNumber.trim())
                            .orElseThrow(() -> new RuntimeException("号码簿不存在: " + athleteNumber));
                    if (!registrationRepository.existsByAthleteIdAndEventId(athlete.getId(), event.getId())) {
                        Registration reg = Registration.builder()
                                .athlete(athlete).event(event).status("approved")
                                .registrationTime(LocalDateTime.now())
                                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
                        registrationRepository.save(reg);
                        success++;
                    }
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", i + 2);
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

    // ==================== 导出 ====================

    /** 导出运动员 */
    public void exportAthletes(HttpServletResponse response) {
        List<Athlete> list = athleteRepository.findAll();
        setExcelResponse(response, "运动员信息_" + dateStr() + ".xlsx");
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("姓名","性别","年级","班级","学号","号码布编号","身份证号",
                    "出生日期","紧急联系人","紧急联系电话","健康状况","备注"));
            for (Athlete a : list) {
                data.add(List.of(
                    n(a.getName()), mapGenderToCn(a.getGender()), n(a.getGrade()),
                    a.getClassInfo() != null ? n(a.getClassInfo().getName()) : "",
                    n(a.getStudentId()), n(a.getNumber()), n(a.getIdCard()),
                    a.getBirthDate() != null ? a.getBirthDate().toString() : "",
                    n(a.getEmergencyContact()), n(a.getEmergencyPhone()),
                    n(a.getHealthStatus()), n(a.getRemark())));
            }
            List<List<String>> head = data.get(0).stream().map(List::of).collect(Collectors.toList());
            EasyExcel.write(out).head(head).sheet("运动员信息").doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
        log.info("导出运动员信息: 共{}条", list.size());
    }

    /** 导出道次表 */
    public void exportArrangement(Long eventId, HttpServletResponse response) {
        List<Arrangement> arrangements = arrangementRepository.findByEventIdOrderByHeatAscLaneAsc(eventId);
        String eventName = arrangements.isEmpty() ? "未知" : arrangements.get(0).getEvent().getName();
        setExcelResponse(response, eventName + "_道次表_" + dateStr() + ".xlsx");
        List<List<String>> data = new ArrayList<>();
        data.add(List.of("组号","道次","运动员姓名","号码簿","班级","年级","性别"));
        for (Arrangement a : arrangements) {
            Athlete ath = a.getAthlete();
            data.add(List.of(String.valueOf(a.getHeat()), String.valueOf(a.getLane()),
                    n(ath.getName()), n(ath.getNumber()),
                    ath.getClassInfo() != null ? n(ath.getClassInfo().getName()) : "",
                    n(ath.getGrade()), mapGenderToCn(ath.getGender())));
        }
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> head = data.get(0).stream().map(List::of).collect(Collectors.toList());
            EasyExcel.write(out).head(head).sheet("道次表").doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
    }

    /** 导出秩序册Excel */
    public void exportOrderBook(HttpServletResponse response) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();
        setExcelResponse(response, "秩序册_" + dateStr() + ".xlsx");

        try (OutputStream out = response.getOutputStream()) {
            // Sheet1: 项目列表
            List<List<String>> eventData = new ArrayList<>();
            eventData.add(List.of("序号","项目编码","项目名称","类别","性别限制","跑道数","校纪录"));
            int idx = 1;
            for (Event e : events) {
                eventData.add(List.of(String.valueOf(idx++), n(e.getCode()), n(e.getName()),
                        n(e.getCategory()), n(e.getGenderLimit()),
                        String.valueOf(e.getDefaultLanes() != null ? e.getDefaultLanes() : 8),
                        n(e.getRecord())));
            }

            // Sheet2: 参赛班级
            List<List<String>> classData = new ArrayList<>();
            classData.add(List.of("序号","班级名称","年级","班主任","学生人数"));
            idx = 1;
            for (ClassInfo c : classes) {
                classData.add(List.of(String.valueOf(idx++), n(c.getName()), n(c.getGrade()),
                        n(c.getTeacherName()), String.valueOf(c.getStudentCount() != null ? c.getStudentCount() : 0)));
            }

            // 写入Excel（多Sheet）
            com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).build();
            com.alibaba.excel.write.metadata.WriteSheet sheet1 = EasyExcel.writerSheet(0, "项目列表")
                    .head(eventData.get(0).stream().map(List::of).collect(Collectors.toList())).build();
            com.alibaba.excel.write.metadata.WriteSheet sheet2 = EasyExcel.writerSheet(1, "参赛班级")
                    .head(classData.get(0).stream().map(List::of).collect(Collectors.toList())).build();
            writer.write(eventData.subList(1, eventData.size()), sheet1);
            writer.write(classData.subList(1, classData.size()), sheet2);
            writer.finish();
        } catch (IOException e) {
            throw new RuntimeException("导出秩序册失败: " + e.getMessage());
        }
        log.info("导出秩序册: {} 个项目, {} 个班级", events.size(), classes.size());
    }

    /** 导出成绩册Excel */
    public void exportResultBook(HttpServletResponse response) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        setExcelResponse(response, "成绩册_" + dateStr() + ".xlsx");

        try (OutputStream out = response.getOutputStream()) {
            com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).build();

            // 每个项目一个Sheet
            List<List<String>> summaryData = new ArrayList<>();
            summaryData.add(List.of("项目名称","项目编码","参赛人数","第一名","第二名","第三名"));

            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                List<Result> results = resultRepository.findByEventIdOrderByTotalRankAsc(e.getId());
                if (results.isEmpty()) continue;

                List<List<String>> sheet = new ArrayList<>();
                sheet.add(List.of("排名","运动员","号码簿","班级","年级","成绩","积分","备注"));
                for (Result r : results) {
                    Athlete a = r.getAthlete();
                    sheet.add(List.of(
                        r.getTotalRank() != null ? String.valueOf(r.getTotalRank()) : "-",
                        n(a.getName()), n(a.getNumber()),
                        a.getClassInfo() != null ? n(a.getClassInfo().getName()) : "",
                        n(a.getGrade()),
                        n(r.getRawTime()),
                        r.getScore() != null ? String.format("%.1f", r.getScore()) : "-",
                        Boolean.TRUE.equals(r.getIsRecord()) ? "破纪录" : ""));
                }

                // 汇总
                String gold = "", silver = "", bronze = "";
                for (Result r : results) {
                    if (r.getTotalRank() == null) continue;
                    String athleteStr = n(r.getAthlete().getName()) + "(" + n(r.getRawTime()) + ")";
                    if (r.getTotalRank() == 1) gold = athleteStr;
                    else if (r.getTotalRank() == 2) silver = athleteStr;
                    else if (r.getTotalRank() == 3) bronze = athleteStr;
                }
                summaryData.add(List.of(n(e.getName()), n(e.getCode()),
                        String.valueOf(results.size()), gold, silver, bronze));

                com.alibaba.excel.write.metadata.WriteSheet ws = EasyExcel.writerSheet(i, shortSheetName(e.getName()))
                        .head(sheet.get(0).stream().map(List::of).collect(Collectors.toList())).build();
                writer.write(sheet.subList(1, sheet.size()), ws);
            }

            // 汇总Sheet
            com.alibaba.excel.write.metadata.WriteSheet summarySheet = EasyExcel.writerSheet(events.size(), "成绩汇总")
                    .head(summaryData.get(0).stream().map(List::of).collect(Collectors.toList())).build();
            writer.write(summaryData.subList(1, summaryData.size()), summarySheet);
            writer.finish();
        } catch (IOException e) {
            throw new RuntimeException("导出成绩册失败: " + e.getMessage());
        }
        log.info("导出成绩册: 共{}个项目", events.size());
    }

    /** Sheet名限制31字符 */
    private String shortSheetName(String name) {
        if (name == null) return "Sheet";
        return name.length() > 28 ? name.substring(0, 28) : name;
    }

    // ==================== 工具方法 ====================

    private void setExcelResponse(HttpServletResponse response, String fileName) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + encoded + ";filename*=UTF-8''" + encoded);
    }

    private String dateStr() {
        return LocalDateTime.now().toString().replace(":", "-").substring(0, 19);
    }

    private static String n(String s) { return s != null ? s : ""; }

    private static String toStringSafe(Object v) {
        return v != null ? v.toString() : "";
    }

    private static int toIntSafe(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean toBoolSafe(Object v) {
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return !("false".equals(s) || "0".equals(s) || "no".equals(s));
    }

    private static String mapGender(String v) {
        if (v == null) return null;
        return switch (v.trim()) {
            case "男","M","m","male","Male","男子","男生" -> "M";
            case "女","F","f","female","Female","女子","女生" -> "F";
            default -> v.trim();
        };
    }

    private static String mapGenderToCn(String v) {
        if (v == null) return "";
        return switch (v.trim()) {
            case "M","男","男子" -> "男";
            case "F","女","女子" -> "女";
            default -> v;
        };
    }

    private static Integer parseIntSafe(String s) { return parseIntSafe(s, null); }
    private static Integer parseIntSafe(String s, Integer defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static Double parseTimeToSeconds(String time) {
        if (time == null || time.isBlank()) return null;
        time = time.trim();
        try {
            if (time.contains(":")) {
                String[] parts = time.split(":");
                if (parts.length == 2)
                    return Integer.parseInt(parts[0]) * 60.0 + Double.parseDouble(parts[1]);
                if (parts.length == 3)
                    return Integer.parseInt(parts[0]) * 3600.0 + Integer.parseInt(parts[1]) * 60.0 + Double.parseDouble(parts[2]);
            }
            return Double.parseDouble(time);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
