package com.sports.service.excel;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.dto.excel.*;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventReferee;
import com.sports.entity.event.EventSchedule;
import com.sports.entity.referee.Referee;
import com.sports.entity.registration.Registration;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.venue.VenueRepository;
import com.sports.repository.referee.RefereeRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
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
import com.sports.common.util.ExportNaming;
import com.sports.common.util.RoundLabelUtil;
import com.sports.service.clazz.GradeService;
import com.sports.service.event.EventService;

/**
 * Excel 导入导出服务 — 重构版 V3.0
 * 支持：模板下载、导入预览（智能列映射+多Sheet）、带映射导入、批量导入、导出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelService {

    private static final ObjectMapper OB_MAPPER = new ObjectMapper();

    private final AthleteRepository athleteRepository;
    private final RegistrationRepository registrationRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository scheduleRepository;
    private final EventRefereeRepository eventRefereeRepository;
    private final RefereeRepository refereeRepository;
    private final VenueRepository venueRepository;
    /** 年级表导入需要写系统年级配置（与「班级表」同属基础数据） */
    private final GradeService gradeService;

    // ==================== 模板下载 ====================

    /** 多表模板的类型别名：一个工作簿内含多张表。 */
    private static final java.util.Set<String> MULTI_WORKBOOK_ALIASES =
            java.util.Set.of("multiworkbook", "multi-workbook", "multisheet", "multi");

    public void getTemplate(String type, HttpServletResponse response) {
        String t = type != null ? type.toLowerCase() : "";
        if (MULTI_WORKBOOK_ALIASES.contains(t)) {
            getMultiWorkbookTemplate(response);
            return;
        }
        TemplateSpec spec = buildTemplate(t);
        setExcelResponse(response, spec.fileName());
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> sheet = spec.rows();
            List<List<String>> headCols = sheet.get(0).stream().map(List::of).collect(Collectors.toList());
            List<List<String>> dataRows = sheet.size() > 1 ? sheet.subList(1, sheet.size()) : List.of();
            // B14/U19：新增「填写说明」Sheet，说明字段规则（尤其号码布由系统生成，可留空）
            List<List<String>> notes = templateNotes(t);
            try (com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).build()) {
                com.alibaba.excel.write.metadata.WriteSheet s1 =
                        EasyExcel.writerSheet(0, "数据").head(headCols).build();
                writer.write(dataRows, s1);
                if (!notes.isEmpty()) {
                    com.alibaba.excel.write.metadata.WriteSheet s2 =
                            EasyExcel.writerSheet(1, "填写说明")
                                    .head(List.of(List.of("字段"), List.of("填写说明"))).build();
                    writer.write(notes, s2);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("模板下载失败: " + e.getMessage());
        }
    }

    /**
     * 多表导入模板：<b>一个工作簿内含多张表</b>（年级表 / 班级表 / 全名单表 / 运动项目表 / 报名表 / 成绩表）。
     *
     * <p>Sheet 名与表头都按多表导入的识别口径命名（见 {@code SheetTypeResolver}），
     * 因此管理员照此填写即可一次性导入，无需逐表说明类型。</p>
     */
    public void getMultiWorkbookTemplate(HttpServletResponse response) {
        setExcelResponse(response, "多表导入模板.xlsx");
        // 刻意不含「成绩表」：成绩在编排/比赛之后录入，且其样本行必须引用已存在的号码/学号，
        // 放进来会让「下载即导入」出现一条必然失败的行（样板数据不自洽）。需要时自行加一张成绩表即可，
        // 多表导入同样支持（按表头自动识别）。
        String[][] sheets = {
                {"grade", "年级表"}, {"class", "班级表"}, {"roster", "全名单表"},
                {"eventsimple", "运动项目表"}, {"signup", "报名表"}};
        try (OutputStream out = response.getOutputStream();
             com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).build()) {
            int idx = 0;
            for (String[] pair : sheets) {
                // 直接复用单表模板的行定义，避免「单表模板」与「多表模板」两份口径漂移
                List<List<String>> rows = buildTemplate(pair[0]).rows();
                List<List<String>> headCols = rows.get(0).stream().map(List::of).collect(Collectors.toList());
                List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
                writer.write(dataRows, EasyExcel.writerSheet(idx++, pair[1]).head(headCols).build());
            }
            List<List<String>> notes = new ArrayList<>();
            notes.add(List.of("用法", "本工作簿含多张表：年级表/班级表/全名单表/运动项目表/报名表。"
                    + "系统按「Sheet 名 + 表头」自动识别每张表的类型。"));
            notes.add(List.of("顺序", "Sheet 的先后不影响结果：导入按依赖顺序处理（年级→班级→名单→项目→报名→成绩）。"));
            notes.add(List.of("不用的表", "用不到的表请整表删除；空表会被自动跳过，不影响其它表。"));
            notes.add(List.of("重跑", "同一份工作簿可重复导入：已存在的数据会计入「跳过」而不算失败。"));
            notes.add(List.of("成绩表", "成绩在编排之后录入。若要与本工作簿一起导入，自行增加一张「成绩表」Sheet 即可"
                    + "（表头：项目编码/运动员号码/运动员姓名/成绩/组别/道次/风速/备注）。"));
            writer.write(notes, EasyExcel.writerSheet(idx, "填写说明")
                    .head(List.of(List.of("字段"), List.of("填写说明"))).build());
        } catch (IOException e) {
            throw new RuntimeException("多表模板下载失败: " + e.getMessage());
        }
    }

    /** 模板规格：文件名 + 行（第 0 行为表头）。 */
    private record TemplateSpec(String fileName, List<List<String>> rows) {
    }

    /**
     * 各类型的模板行定义（<b>唯一来源</b>）：单表下载与多表工作簿模板都从这里取，
     * 保证两种模板的列序/示例永远一致——否则「照多表模板填的」会按单表模板的列序被读错。
     */
    private TemplateSpec buildTemplate(String t) {
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
                sheet.add(List.of("项目编码","运动员号码","运动员姓名","年级","班级","团队标识号","备注"));
                sheet.add(List.of("100M","010101","张三","高一年级","高一1班","A组",""));
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
            case "eventsimple" -> {
                // 运动项目表（7列精简模板）：项目代码/名称/每组人数/每批组数/项目类型/场地号/每批所需时间
                // 复用 Event 现有字段，不新增列；与「表格2」17列模板互补，面向只需登记基础编排参数的老师
                fileName = "运动项目表导入模板.xlsx";
                sheet.add(List.of("项目代码","项目名称","每组人数","每批组数","项目类型","场地号","每批所需时间(分)"));
                sheet.add(List.of("100M","100米","1","6","径赛","TRACK","20"));
                sheet.add(List.of("4X100M","4×100米接力","4","8","径赛","TRACK","30"));
                sheet.add(List.of("TY_LJ","立定跳远","1","4","田赛","FIELD_A","90"));
                sheet.add(List.of("TUG","拔河","15","1","趣味运动会","FIELD_B","300"));
            }
            case "roster" -> {
                // 全名单表（5列）：年级/班级/姓名/学号/性别 —— 运动员主数据
                // 按学号 upsert；班级缺失时按(年级,班级)自动创建
                fileName = "全名单表导入模板.xlsx";
                sheet.add(List.of("年级","班级","姓名","学号","性别"));
                sheet.add(List.of("高一年级","高一1班","张三","2024001","男"));
                sheet.add(List.of("高一年级","高一1班","李四","2024002","女"));
            }
            case "signup" -> {
                // 报名表（7列）：年级/班级/姓名/学号/性别/项目/组号
                // 个人项目严禁填写组号；团体/接力按班级内编 A/B（同一班级同一项目同组号视为一队）
                fileName = "报名表导入模板.xlsx";
                sheet.add(List.of("年级","班级","姓名","学号","性别","项目","组号"));
                sheet.add(List.of("高一年级","高一1班","张三","2024001","男","100米",""));
                sheet.add(List.of("高一年级","高一1班","张三","2024001","男","4×100米接力","A"));
                sheet.add(List.of("高一年级","高一1班","李四","2024002","女","4×100米接力","B"));
            }
            case "grade" -> {
                // 年级表（1~2列）：年级 / 序号 —— 供「把年级/班级拆分为独立表」的工作簿
                fileName = "年级表导入模板.xlsx";
                sheet.add(List.of("年级","序号"));
                sheet.add(List.of("高一年级","1"));
                sheet.add(List.of("高二年级","2"));
                sheet.add(List.of("高三年级","3"));
            }
            case "event" -> {
                // 表格2 折中布局（与 EventService.parseTable2Row 列完全对齐）：
                // A代码/B项目/C是否田径/D道次(田赛0)/E顺序号/F每组次几人/G捆绑字母/H并行数(1=串行,n=并行)/
                // I场地编码/J性别/K年级组/L是否团体/M团体人数/N场地/O最大用时(分)/P间隔(分)/Q组次裁判数量
                // 并行数=项目内并发人数（径赛=每组人数即道次，田赛=工位数，游泳=泳道数）；
                // 项目绑定场地后受该场地 parallelMax 约束（上限=可用场地/泳道数）
                // 组次裁判数量=每个组次（heat/组/轮）需安排的裁判人数，留空/0=不安排裁判
                fileName = "项目表导入模板_表格2.xlsx";
                sheet.add(List.of("代码","项目","是否田径","道次","顺序号","每组次几人","捆绑字母","并行数","场地编码",
                        "性别","年级组","是否团体","团体人数","场地","最大用时(分)","间隔(分)","组次裁判数量"));
                sheet.add(List.of("100M","100米","是","8","1","8","","8","TRACK","男子组","高一年级","否","0","田径场","20","10","2"));
                sheet.add(List.of("4X100M","4×100米接力","是","8","2","4","","8","TRACK","男子组","高一年级","是","4","田径场","30","15","3"));
                sheet.add(List.of("TY_F","跳远(女子)","否","0","3","1","A","1","FIELD_A","女子组","高一年级","否","0","田赛A区","90","10","1"));
                sheet.add(List.of("SWIM_M","50米蛙泳(男子)","是","8","4","4","","4","SWIM","男子组","高一年级","否","0","游泳馆","25","10","2"));
            }
            default -> {
                fileName = "导入模板.xlsx";
                sheet.add(List.of("请指定模板类型"));
            }
        }
        return new TemplateSpec(fileName, sheet);
    }

    /** 各模板的「填写说明」（B14/U19）：解释字段取值与系统自动生成项 */
    private List<List<String>> templateNotes(String type) {
        List<List<String>> notes = new ArrayList<>();
        switch (type == null ? "" : type.toLowerCase()) {
            case "athlete" -> {
                notes.add(List.of("号码布编号", "由系统按「号码簿规则」自动生成，导入时可留空；导出「运动员信息」时会自动回填。"));
                notes.add(List.of("学号", "必填且唯一，用于区分同名运动员。"));
                notes.add(List.of("班级", "须与系统中已创建的班级名称一致。"));
                notes.add(List.of("性别", "填写「男」或「女」。"));
            }
            case "score" -> {
                notes.add(List.of("项目编码", "须与系统中项目编码一致，可从「项目列表/项目表模板」获取。"));
                notes.add(List.of("运动员号码", "填号码布编号；也可填学号（系统按号码/学号匹配运动员）。"));
                notes.add(List.of("成绩", "径赛填秒数(如 12.34)、田赛填米/厘米数；支持 DNS/DNF/DSQ。"));
            }
            case "registration" -> {
                notes.add(List.of("项目编码", "须与系统中项目编码一致。"));
                notes.add(List.of("运动员号码", "填号码布编号或学号。"));
                notes.add(List.of("团队标识号", "仅团体/趣味接力类项目填写（如 A组、B组、C组）。同一运动员在同一项目同一队伍标识下重复填写将自动去重；不填则按普通项目「一人一项一次」去重。"));
            }
            case "class" -> notes.add(List.of("班级编码", "唯一标识；班主任可填姓名，系统按规则匹配登录账号。"));
            case "user" -> notes.add(List.of("角色", "取值：ADMIN/TEACHER/CLASS_TEACHER/STUDENT/REFEREE（REFEREE=裁判，可登录查看本人执裁安排）。"));
            case "event" -> {
                notes.add(List.of("道次", "田赛填 0；径赛填实际道次数。"));
                notes.add(List.of("每组次几人", "径赛=每组人数即道次，田赛=工位数，游泳=泳道数。"));
                notes.add(List.of("并行数", "项目内并发人数（1=串行，n=并行）；绑定场地后受该场地并行上限约束。"));
                notes.add(List.of("捆绑字母", "同字母的田赛项目安排在同一时段并行。"));
            }
            case "eventsimple" -> {
                notes.add(List.of("项目代码", "唯一编码，如 100M；导入后作为项目主键。"));
                notes.add(List.of("每组人数", "一个组/队的人数：个人项目填 1，4×100 填 4，拔河填 15。>1 自动标记为团体赛。"));
                notes.add(List.of("每批组数", "同一时刻可并行进行的批次数：1000米6道填6，立定跳远每批4人填4。"));
                notes.add(List.of("项目类型", "取值：径赛 / 田赛 / 趣味运动会 / 球类；用于推断是否占道次与趣味并行。"));
                notes.add(List.of("场地号", "场地编码（与全局场地配置 code 对应），如 TRACK / FIELD_A；绑定独立并发池。"));
                notes.add(List.of("每批所需时间(分)", "一批人同时上场的分钟数，如趣味项目一组5分钟。"));
            }
            case "roster" -> {
                notes.add(List.of("年级", "如 高一年级；用于年级分组与统计。"));
                notes.add(List.of("班级", "班级名称，须与系统中班级名称一致；不存在时按(年级,班级)自动创建。"));
                notes.add(List.of("姓名", "学生姓名，必填。"));
                notes.add(List.of("学号", "必填且唯一，按学号 upsert（已存在则更新，不存在则新建）。"));
                notes.add(List.of("性别", "填 男 / 女。"));
                notes.add(List.of("号码布", "由系统按号码簿规则批量生成，本表无需填写。"));
            }
            case "signup" -> {
                notes.add(List.of("年级/班级/姓名/学号", "用于定位已存在于「全名单表」的运动员，须与全名单一致。"));
                notes.add(List.of("项目", "填项目名称或项目编码（如 100米 / 100M / 4×100米接力），须与运动项目表一致。"));
                notes.add(List.of("组号", "仅团体/接力项目填写（如 A / B）：同一班级同一项目同组号视为同一支队伍；两个 4×100 队分别编 A、B。"));
                notes.add(List.of("个人项目", "个人项目（非团体）严禁填写组号，填了将报错。"));
            }
            case "grade" -> {
                notes.add(List.of("年级", "年级名称，如 高一年级；写入系统「年级管理」，已存在则跳过。"));
                notes.add(List.of("序号", "出场/统计顺序，可留空（留空按现有年级数顺延）。"));
                notes.add(List.of("用途", "配合「班级表」「全名单表」拆分成多个 Sheet 时，先导年级再导班级。"));
            }
            default -> notes.add(List.of("说明", "请在下载链接中指定模板类型。"));
        }
        return notes;
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
                String field = ExcelColumnMapping.matchColumnName(headers.get(c));
                if (field != null) {
                    suggestedMappings.put(String.valueOf(c), field);
                    mappingLabels.put(field, ExcelColumnMapping.getFieldLabel(type, field));
                    fieldOptions.computeIfAbsent(field, k -> new ArrayList<>()).add(headers.get(c));
                }
            }

            Map<String, String> availableFields = ExcelColumnMapping.TYPE_FIELDS.getOrDefault(type, ExcelColumnMapping.TYPE_FIELDS.get("athlete"));

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
        String t = detectTypeOrNull(filename);
        return t != null ? t : "athlete";
    }

    /**
     * 按名称（文件名 / Sheet 名）推断导入类型；<b>无法确定时返回 null</b>（不再默认 athlete）。
     *
     * <p>多表导入依赖它：默认值会让「班级表」被当成运动员表硬导，从而「导入成功但数据全错」。
     * 返回 null 由调用方决定是跳过还是报错，绝不猜。</p>
     */
    public static String detectTypeOrNull(String name) {
        if (name == null) return null;
        String l = name.toLowerCase();
        if (l.contains("score") || l.contains("成绩")) return "score";
        else if (l.contains("名单报名") || l.contains("名单+报名") || l.contains("报名名单")) return "athlete_signup";
        else if (l.contains("报名表")) return "signup";
        else if (l.contains("全名单") || l.contains("名单")) return "roster";
        else if (l.contains("registration") || l.contains("报名")) return "registration";
        else if (l.contains("class") || l.contains("班级")) return "class";
        else if (l.contains("user") || l.contains("用户")) return "user";
        // 年级表：只在明确表达「年级表/年级列表/就是年级」时判定，避免把「高一年级」这类
        // 按年级拆分的名单 Sheet 误判为年级主数据（那种 Sheet 由表头推断为 roster）。
        else if (l.contains("年级表") || l.contains("年级列表") || "年级".equals(l.trim())) return "grade";
        else if (l.contains("运动项目表")) return "eventsimple";
        else if (l.contains("event") || l.contains("项目")) return "event";
        return null;
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
            case "eventsimple" -> processEventSimpleRow(values);
            case "roster" -> processRosterRow(values);
            case "signup" -> processSignupRow(values);
            case "athlete_signup" -> processCombinedRow(values);
            case "grade" -> processGradeRow(values);
            default -> throw new RuntimeException("不支持的导入类型: " + type);
        }
    }

    /**
     * 年级表行处理：写入系统年级配置（{@code system_config.grades}）。
     *
     * <p>用于「把年级/班级拆成独立表」的多表工作簿——年级先落库，后续班级表与全名单表才有年级可挂。</p>
     */
    private void processGradeRow(Map<String, String> v) {
        String name = trimToNull(v.get("name"));
        if (name == null) throw new RuntimeException("年级名称为空");
        List<Map<String, Object>> grades = gradeService.getGrades();
        for (Map<String, Object> g : grades) {
            if (name.equals(String.valueOf(g.get("name")))) {
                throw new RuntimeException("年级已存在: " + name);
            }
        }
        Integer sortOrder = parseIntSafe(v.get("sortOrder"), null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sortOrder", sortOrder != null ? sortOrder : grades.size() + 1);
        gradeService.addGrade(body);
    }

    // ==================== 批量逐行导入（多表导入复用） ====================

    /** 「已存在」类失败标记：多表导入据此归入「跳过」而非「失败」（重跑同一份工作簿是常态）。 */
    private static final List<String> ALREADY_EXISTS_MARKERS =
            List.of("已存在", "已报名", "已存在该", "重复");

    /**
     * 逐行导入（每行一个 {@code field → 值}）。供「多表导入」把每个 Sheet 的行批量交给既有处理器。
     *
     * <p>逐行捕获异常并如实分类：<b>成功 / 跳过（已存在，重跑常见）/ 失败（附行号与原因）</b>。
     * 绝不静默吞掉失败——{@code failed} 与逐行原因都会回传，供前端逐表展示。</p>
     */
    @Transactional
    public Map<String, Object> importRows(String type, List<Map<String, String>> rows) {
        int success = 0, skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> skipNotes = new ArrayList<>();
        int total = rows == null ? 0 : rows.size();
        for (int i = 0; i < total; i++) {
            try {
                processRow(type, rows.get(i));
                success++;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (isAlreadyExists(msg)) {
                    skipped++;
                    if (skipNotes.size() < 20) skipNotes.add("第" + (i + 1) + "行：" + msg);
                } else {
                    errors.add(new LinkedHashMap<>(Map.of("row", i + 1, "message", msg)));
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("totalRows", total);
        r.put("success", success);
        r.put("skipped", skipped);
        r.put("failed", errors.size());
        r.put("errors", errors);
        r.put("skipNotes", skipNotes);
        return r;
    }

    private static boolean isAlreadyExists(String message) {
        if (message == null) return false;
        for (String m : ALREADY_EXISTS_MARKERS) {
            if (message.contains(m)) return true;
        }
        return false;
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
        // 兼容两种写法：「班级名称」（模板表头）与「班级」（口语表头）。自动映射到 className 时也能落库，
        // 否则会出现「表头认得出、处理器认不出」的静默丢字段。
        String name = v.get("name") != null ? v.get("name") : v.get("className");
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
                .refereesPerGroup(parseIntSafe(v.get("refereesPerGroup"), 0))
                .isEnabled(true).sortOrder(0).build();
        eventRepository.save(event);
    }

    /**
     * 运动项目表（7列精简模板）导入：复用 Event 现有字段，不新增列。
     * <p>A项目代码→code / B项目名称→name / C每组人数→teamMembers(>1 自动标记团体赛) /
     * D每批组数→concurrency(径赛同步 laneCount) / E项目类型→category(推断 track/funSports) /
     * F场地号→defaultVenueCode / G每批所需时间→perBatchMinutes。</p>
     */
    private void processEventSimpleRow(Map<String, String> v) {
        String name = v.get("eventName");
        String code = v.get("eventCode");
        if (name == null || code == null) throw new RuntimeException("项目名称和编码不能为空");
        if (eventRepository.existsByCode(code)) throw new RuntimeException("项目编码已存在: " + code);

        String category = trimToNull(v.get("category"));
        Integer teamMembers = parseIntSafe(v.get("teamMembers"), 0);
        Integer concurrency = parseIntSafe(v.get("concurrency"), null);
        Integer perBatch = parseIntSafe(v.get("perBatchMinutes"), null);
        String venueCode = trimToNull(v.get("defaultVenueCode"));

        // 7列精简模板不含「是否田径」列，由项目类型推断径赛/田赛/趣味/球类，复用现有字段
        boolean isTrack = "径赛".equals(category);
        // 趣味运动会、球类 复用田赛逻辑（不占道次、并行分组）
        boolean isFun = "趣味运动会".equals(category) || "球类".equals(category);
        Integer laneCount = isTrack ? (concurrency != null ? concurrency : 8) : 0;

        Event.EventBuilder b = Event.builder()
                .name(name).code(code)
                .category(category)
                .team(teamMembers != null && teamMembers > 1)
                .teamMembers(teamMembers)
                .concurrency(concurrency)
                .perBatchMinutes(perBatch)
                .defaultVenueCode(venueCode)
                .laneCount(laneCount)
                .defaultLanes(laneCount)
                .isEnabled(true).sortOrder(0);
        // 项目类型留空时不覆盖 track/funSports 的实体默认值，避免产生无法编排的事件
        if (category != null) {
            b.track(isTrack).funSports(isFun);
            // 趣味运动会/球类 若被安排在跑道场地(type=track)，则须与真正径赛错开(occupiesTrack)，
            // 避免跑道被径赛与趣味项目同时占用（用户「趣味运动会占用跑道则错开」规则）
            if (isFun && venueCode != null) {
                boolean occ = venueRepository.findByCode(venueCode)
                        .map(ven -> "track".equalsIgnoreCase(ven.getType()))
                        .orElse(false);
                b.occupiesTrack(occ);
            }
        }
        eventRepository.save(b.build());
    }

    // ==================== 全名单表（5列）：年级/班级/姓名/学号/性别 ====================

    /**
     * 全名单表：运动员主数据导入。按学号(studentId) upsert；
     * 班级不存在时按(年级,班级)自动创建，便于「全名单 → 报名表」顺次导入。
     */
    private void processRosterRow(Map<String, String> v) {
        String studentId = trimToNull(v.get("studentId"));
        String name = trimToNull(v.get("name"));
        if (studentId == null) throw new RuntimeException("学号不能为空");
        if (name == null) throw new RuntimeException("姓名不能为空");

        String grade = trimToNull(v.get("grade"));
        String className = trimToNull(v.get("className"));
        ClassInfo classInfo = null;
        if (className != null) {
            classInfo = classInfoRepository.findByGradeAndName(grade, className).orElse(null);
            if (classInfo == null) classInfo = classInfoRepository.findByName(className).orElse(null);
            if (classInfo == null) {
                // 班级缺失：按(年级,班级)自动创建，code 取班级名（唯一），参与状态默认开启
                String code = className;
                int dup = 1;
                while (classInfoRepository.existsByCode(code)) code = className + "_" + (dup++);
                classInfo = ClassInfo.builder()
                        .name(className).code(code).grade(grade)
                        .isParticipating(true).build();
                classInfo = classInfoRepository.save(classInfo);
            }
        }

        String gender = mapGender(v.get("gender"));
        Athlete athlete = athleteRepository.findByStudentId(studentId).orElse(null);
        if (athlete == null) {
            athlete = Athlete.builder()
                    .name(name).gender(gender).grade(grade).classInfo(classInfo)
                    .studentId(studentId).status("normal").build();
        } else {
            athlete.setName(name);
            athlete.setGender(gender);
            athlete.setGrade(grade);
            athlete.setClassInfo(classInfo);
            athlete.setStudentId(studentId);
            athlete.setStatus("normal");
        }
        athleteRepository.save(athlete);
    }

    // ==================== 报名表（7列）：年级/班级/姓名/学号/性别/项目/组号 ====================

    /**
     * 报名表：按(学号/姓名+班级)定位运动员，按(项目编码/名称)定位项目，写入报名。
     * 组号→Registration.teamTag：团体/接力项目可按班级内编 A/B 区分不同队伍；
     * 个人项目(event.team=false)严禁填写组号，否则报错。
     */
    private void processSignupRow(Map<String, String> v) {
        String eventRef = trimToNull(v.get("eventCode"));
        if (eventRef == null) throw new RuntimeException("项目不能为空（填项目编码或名称）");
        Event event = eventRepository.findByCode(eventRef.trim())
                .orElseGet(() -> eventRepository.findByNameAndIsEnabledTrue(eventRef.trim()).orElse(null));
        if (event == null) throw new RuntimeException("项目不存在: " + eventRef);

        // 定位运动员：优先学号，其次 姓名+班级
        Athlete athlete = null;
        String studentId = trimToNull(v.get("studentId"));
        if (studentId != null) athlete = athleteRepository.findByStudentId(studentId).orElse(null);
        if (athlete == null) {
            String name = trimToNull(v.get("name"));
            String className = trimToNull(v.get("className"));
            if (name == null) throw new RuntimeException("姓名或学号至少一项用于定位运动员");
            List<Athlete> cands = athleteRepository.findByName(name);
            if (className != null) {
                cands = cands.stream()
                        .filter(a -> a.getClassInfo() != null && className.equals(a.getClassInfo().getName()))
                        .collect(java.util.stream.Collectors.toList());
            }
            if (cands.isEmpty()) throw new RuntimeException("运动员不存在: " + name + (className != null ? "(" + className + ")" : ""));
            if (cands.size() > 1) throw new RuntimeException("运动员重名需补充学号以唯一定位: " + name);
            athlete = cands.get(0);
        }

        String teamTag = trimToNull(v.get("teamTag"));
        boolean isTeam = Boolean.TRUE.equals(event.getTeam()) || (event.getTeamMembers() != null && event.getTeamMembers() > 1);
        if (!isTeam && teamTag != null) {
            throw new RuntimeException("个人项目严禁填写组号: " + event.getName() + "（" + athlete.getName() + "）");
        }

        if (registrationRepository.existsByAthleteIdAndEventId(athlete.getId(), event.getId()))
            throw new RuntimeException("该运动员已报名此项目: " + athlete.getName() + " / " + event.getName());

        Registration reg = Registration.builder()
                .athlete(athlete).event(event)
                .team(isTeam)
                .teamTag(teamTag)
                .status("approved")
                .source("offline")
                .registrationTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        registrationRepository.save(reg);
    }

    // ==================== 名单+报名合并表（合一）：单 sheet ====================

    /**
     * 名单+报名合一：每行既是一条「全名单」（建/更新运动员，含号码布编号），
     * 若带了「项目」则同时写一条报名；项目为空的行只落运动员主数据（即全名单里未报名的学生）。
     *
     * <p>运动员按学号 upsert、班级缺失自动创建（同 roster 口径），这样「一个 Excel 一张表」
     * 即可完成「建人 + 报名」，适合班主任端一次性提交。报名部分直接复用 {@link #processSignupRow}，
     * 因此它同样遵守「个人项目严禁填组号」「已报名该项目则跳过」等规则。</p>
     */
    private void processCombinedRow(Map<String, String> v) {
        upsertAthleteFromCombined(v);
        if (trimToNull(v.get("eventCode")) != null) {
            processSignupRow(v);
        }
    }

    /** 合一表：按学号(优先)/号码布编号 upsert 运动员；班级缺失自动创建。 */
    private Athlete upsertAthleteFromCombined(Map<String, String> v) {
        String studentId = trimToNull(v.get("studentId"));
        String number = trimToNull(v.get("number"));
        Athlete athlete = null;
        if (studentId != null) athlete = athleteRepository.findByStudentId(studentId).orElse(null);
        if (athlete == null && number != null) athlete = athleteRepository.findByNumber(number).orElse(null);

        String name = trimToNull(v.get("name"));
        if (name == null) throw new RuntimeException("姓名为空");

        if (athlete == null) {
            ClassInfo classInfo = resolveOrCreateClass(v.get("grade"), v.get("className"));
            athlete = Athlete.builder()
                    .name(name).gender(mapGender(v.get("gender")))
                    .grade(trimToNull(v.get("grade"))).classInfo(classInfo)
                    .studentId(studentId).number(number)
                    .status("normal").build();
            return athleteRepository.save(athlete);
        }

        athlete.setName(name);
        String g = trimToNull(v.get("gender"));
        if (g != null) athlete.setGender(mapGender(g));
        String grade = trimToNull(v.get("grade"));
        if (grade != null) athlete.setGrade(grade);
        ClassInfo ci = resolveOrCreateClass(grade, v.get("className"));
        if (ci != null) athlete.setClassInfo(ci);
        if (studentId != null) athlete.setStudentId(studentId);
        if (number != null) athlete.setNumber(number);
        athlete.setStatus("normal");
        return athleteRepository.save(athlete);
    }

    /** 班级解析：按(年级,班级)或班级名查找，缺失则新建（code 取班级名，唯一去重）。 */
    private ClassInfo resolveOrCreateClass(String grade, String className) {
        if (className == null) return null;
        ClassInfo ci = classInfoRepository.findByGradeAndName(grade, className).orElse(null);
        if (ci == null) ci = classInfoRepository.findByName(className).orElse(null);
        if (ci == null) {
            String code = className;
            int dup = 1;
            while (classInfoRepository.existsByCode(code)) code = className + "_" + (dup++);
            ci = classInfoRepository.save(ClassInfo.builder()
                    .name(className).code(code).grade(trimToNull(grade)).isParticipating(true).build());
        }
        return ci;
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
            // U13：读取全部 Sheet——支持「每个项目一个 Sheet」的成绩表，同时兼容单 Sheet
            EasyExcel.read(in, ScoreExcelModel.class, listener).doReadAll();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", listener.getSuccessCount() + listener.getErrorCount());
            result.put("success", listener.getSuccessCount());
            result.put("failed", listener.getErrorCount());
            result.put("errors", listener.getErrors());
            // B02/U13：说明/汇总行单独计数，不再混入 failed
            // （模板自带的「填写说明」Sheet 曾被当成数据，产生一整片假错误）
            result.put("skipped", listener.getSkipped());
            result.put("skippedCount", listener.getSkipped().size());
            return result;
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> importRegistrations(MultipartFile file) {
        log.info("Excel导入报名: {}", file.getOriginalFilename());
        int success = 0;
        int skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        try (InputStream in = file.getInputStream()) {
            // 表头 + 数据一起读（headRowNumber(0)），按表头定位「团队标识号」列；
            // 项目编码 / 运动员号码仍按位置 0/1 兼容旧模板（无团队标识号列时 teamTag 取 null）。
            List<Map<Integer, String>> all = EasyExcel.read(in).sheet().headRowNumber(0).doReadSync();
            if (all.isEmpty()) return buildImportResult(success, skipped, errors);
            Map<Integer, String> header = all.get(0);
            int teamTagCol = -1;
            for (Map.Entry<Integer, String> e : header.entrySet()) {
                if (e.getValue() == null) continue;
                final String hv = e.getValue().trim();
                if (ExcelColumnMapping.COLUMN_ALIASES.getOrDefault("teamTag", List.of()).stream()
                        .anyMatch(a -> a.equalsIgnoreCase(hv))) {
                    teamTagCol = e.getKey();
                    break;
                }
            }
            for (int i = 1; i < all.size(); i++) {
                Map<Integer, String> row = all.get(i);
                try {
                    String eventCode = row.getOrDefault(0, "");
                    String athleteNumber = row.getOrDefault(1, "");
                    Event event = eventRepository.findByCode(eventCode.trim())
                            .orElseThrow(() -> new RuntimeException("项目编码不存在: " + eventCode));
                    Athlete athlete = athleteRepository.findByNumber(athleteNumber.trim())
                            .orElseThrow(() -> new RuntimeException("号码簿不存在: " + athleteNumber));
                    String teamTag = teamTagCol >= 0 ? trimToNull(row.get(teamTagCol)) : null;
                    boolean dup = Boolean.TRUE.equals(event.getTeam())
                            ? registrationRepository.existsByAthleteIdAndEventIdAndTeamTag(athlete.getId(), event.getId(), teamTag)
                            : registrationRepository.existsByAthleteIdAndEventId(athlete.getId(), event.getId());
                    // 每班人数限制：项目自带 maxPerClass 优先（未设定则不限制）
                    if (event.getMaxPerClass() != null && event.getMaxPerClass() > 0
                            && athlete.getClassInfo() != null) {
                        long classCnt = registrationRepository.countByClassAndEvent(
                                athlete.getClassInfo().getId(), event.getId());
                        if (classCnt >= event.getMaxPerClass()) {
                            Map<String, Object> e2 = new LinkedHashMap<>();
                            e2.put("row", i + 1);
                            e2.put("message", "项目「" + event.getName() + "」本班已达每班人数限制("
                                    + event.getMaxPerClass() + "人)");
                            errors.add(e2);
                            continue;
                        }
                    }
                    if (!dup) {
                        Registration reg = Registration.builder()
                                .athlete(athlete).event(event).status("approved")
                                .team(Boolean.TRUE.equals(event.getTeam())).teamTag(teamTag)
                                .registrationTime(LocalDateTime.now())
                                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
                        registrationRepository.save(reg);
                        success++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", i + 1);
                    err.put("message", e.getMessage());
                    errors.add(err);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }
        return buildImportResult(success, skipped, errors);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Map<String, Object> buildImportResult(int success, int skipped, List<Map<String, Object>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", success + skipped + errors.size());
        result.put("success", success);
        result.put("skipped", skipped);
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

    /** 导出秩序册Excel：竞赛日程 / 分组道次名单 / 项目列表 / 参赛班级 */
    @Transactional(readOnly = true)
    public void exportOrderBook(HttpServletResponse response) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();
        // B03/U03 + B04/U04：参赛运动员集合（已审核报名），用于「参赛班级人数」「号码对照表仅含参赛」
        Set<Long> participantIds = registrationRepository.findByStatus("approved").stream()
                .map(r -> r.getAthlete() != null ? r.getAthlete().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Long> participantCountByClass = new LinkedHashMap<>();
        for (Athlete p : athleteRepository.findAllById(participantIds)) {
            if (p.getDeletedAt() != null || p.getClassInfo() == null) continue;
            participantCountByClass.merge(p.getClassInfo().getId(), 1L, Long::sum);
        }
        // 裁判分配查找表：key=eventId|grade|gender|round|heat → 裁判姓名串（分组道次名单挂载用）
        Map<Long, Referee> refMapAll = refereeRepository.findAll().stream()
                .collect(Collectors.toMap(Referee::getId, r -> r, (a, b) -> a));
        Map<String, String> refByHeat = new HashMap<>();
        for (EventReferee er : eventRefereeRepository.findAll()) {
            if (er.getEvent() == null) continue;
            List<Long> ids = parseRefIds(er.getRefereeIds());
            String names = ids.stream()
                    .map(id -> refMapAll.get(id) != null ? refMapAll.get(id).getName() : "未知")
                    .collect(Collectors.joining("、"));
            String key = er.getEvent().getId() + "|" + (er.getGrade() == null ? "" : er.getGrade()) + "|"
                    + (er.getGender() == null ? "" : er.getGender()) + "|"
                    + (er.getRound() == null ? "" : er.getRound()) + "|" + er.getHeat();
            refByHeat.put(key, names);
        }
        setExcelResponse(response, "秩序册_" + dateStr() + ".xlsx");

        try (OutputStream out = response.getOutputStream()) {
            // Sheet1: 竞赛日程
            List<EventSchedule> scheds = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();
            List<List<String>> schedData = new ArrayList<>();
            schedData.add(List.of("天次", "日期", "时段", "时间", "项目", "轮次", "性别", "年级", "场地"));
            // U09/B09/B10：预计算含预赛轮的项目，用于区分「决赛」与「直接决赛」
            Set<Long> prelimEventIds = arrangementRepository.findAll().stream()
                    .filter(a -> "preliminary".equals(a.getRound()))
                    .map(a -> a.getEvent() != null ? a.getEvent().getId() : null)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            for (EventSchedule s : scheds) {
                Event se = s.getEvent();
                long seId = se != null && se.getId() != null ? se.getId() : -1L;
                String roundLabel = com.sports.common.util.RoundLabelUtil.label(s.getRound(), prelimEventIds.contains(seId));
                schedData.add(List.of(safe(s.getDay()), safe(s.getScheduleDate()), safe(s.getTimeSlot()),
                        safe(s.getStartTime()) + "~" + safe(s.getEndTime()),
                        se != null ? safe(se.getName()) : "-",
                        roundLabel,
                        se != null ? safe(se.getGenderLimit()) : "-",
                        safe(s.getGrade()), safe(s.getVenue())));
            }

            // Sheet2: 分组道次名单（决赛优先，无决赛用预赛）——U09/B10：新增「轮次」列
            List<List<String>> laneData = new ArrayList<>();
            laneData.add(List.of("项目", "轮次", "性别", "年级", "组次", "道次", "号码", "姓名", "班级", "裁判"));
            for (Event e : events) {
                List<Arrangement> all = arrangementRepository.findByEventId(e.getId());
                if (all.isEmpty()) continue;
                boolean hasPrelim = all.stream().anyMatch(a -> "preliminary".equals(a.getRound()));
                boolean hasFinal = all.stream().anyMatch(a -> "final".equals(a.getRound()));
                List<Arrangement> pool = hasFinal
                        ? all.stream().filter(a -> "final".equals(a.getRound())).collect(Collectors.toList())
                        : all;
                pool.sort(Comparator
                        .comparingInt((Arrangement a) -> a.getHeat() == null ? 0 : a.getHeat())
                        .thenComparingInt(a -> a.getLane() == null ? 0 : a.getLane()));
                for (Arrangement a : pool) {
                    Athlete at = a.getAthlete();
                    if (at == null) continue;
                    String rl = com.sports.common.util.RoundLabelUtil.label(a.getRound(), hasPrelim);
                    String refKey = e.getId() + "|" + (a.getGrade() == null ? "" : a.getGrade()) + "|"
                            + (a.getGender() == null ? "" : a.getGender()) + "|"
                            + (a.getRound() == null ? "" : a.getRound()) + "|" + a.getHeat();
                    laneData.add(List.of(safe(e.getName()), rl, safe(e.getGenderLimit()), safe(a.getGrade()),
                            safe(a.getHeat()), safe(a.getLane()),
                            safe(at.getNumber()), safe(at.getName()),
                            at.getClassInfo() != null ? safe(at.getClassInfo().getName()) : "-",
                            refByHeat.getOrDefault(refKey, "")));
                }
            }

            // Sheet3: 项目列表
            List<List<String>> eventData = new ArrayList<>();
            eventData.add(List.of("序号", "项目编码", "项目名称", "类别", "性别限制", "跑道数", "校纪录"));
            int idx = 1;
            for (Event e : events) {
                eventData.add(List.of(String.valueOf(idx++), n(e.getCode()), n(e.getName()),
                        n(e.getCategory()), n(e.getGenderLimit()),
                        String.valueOf(e.getDefaultLanes() != null ? e.getDefaultLanes() : 8),
                        n(e.getRecord())));
            }

            // Sheet4: 参赛班级
            List<List<String>> classData = new ArrayList<>();
            classData.add(List.of("序号", "班级名称", "年级", "班主任", "学生人数"));
            idx = 1;
            for (ClassInfo c : classes) {
                // B03/U03：人数取「本班参赛运动员数」（按报名审核统计），班主任优先班级登记名、缺失回退绑定账号
                String teacher = c.getTeacherName() != null && !c.getTeacherName().isBlank() ? c.getTeacherName().trim()
                        : (c.getTeacherUser() != null
                            ? (c.getTeacherUser().getName() != null && !c.getTeacherUser().getName().isBlank()
                                ? c.getTeacherUser().getName().trim()
                                : n(c.getTeacherUser().getUsername()))
                            : "-");
                classData.add(List.of(String.valueOf(idx++), n(c.getName()), n(c.getGrade()),
                        teacher, String.valueOf(participantCountByClass.getOrDefault(c.getId(), 0L))));
            }

            com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).build();
            writer.write(schedData.subList(1, schedData.size()),
                    EasyExcel.writerSheet(0, "竞赛日程").head(schedData.get(0).stream().map(List::of).collect(Collectors.toList())).build());
            writer.write(laneData.subList(1, laneData.size()),
                    EasyExcel.writerSheet(1, "分组道次名单").head(laneData.get(0).stream().map(List::of).collect(Collectors.toList())).build());
            writer.write(eventData.subList(1, eventData.size()),
                    EasyExcel.writerSheet(2, "项目列表").head(eventData.get(0).stream().map(List::of).collect(Collectors.toList())).build());
            writer.write(classData.subList(1, classData.size()),
                    EasyExcel.writerSheet(3, "参赛班级").head(classData.get(0).stream().map(List::of).collect(Collectors.toList())).build());
            writer.finish();
            log.info("导出秩序册: {}个项目, {}个班级, 日程{}条, 道次{}行",
                    events.size(), classes.size(), scheds.size(), laneData.size() - 1);
        } catch (IOException e) {
            throw new RuntimeException("导出秩序册失败: " + e.getMessage());
        }
    }

    /** 导出成绩册Excel */
    @Transactional
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
        // U11/B13：导出文件名统一带版本号，便于区分多版本产物
        fileName = com.sports.common.util.ExportNaming.withVersion(fileName);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + encoded + ";filename*=UTF-8''" + encoded);
    }

    private String dateStr() {
        return LocalDateTime.now().toString().replace(":", "-").substring(0, 19);
    }

    private static String n(String s) { return s != null ? s : ""; }

    private static String safe(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 解析 event_referee.referee_ids（JSON 数组字符串）为裁判 ID 列表 */
    private static List<Long> parseRefIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Integer> list = OB_MAPPER.readValue(json, new TypeReference<List<Integer>>() {});
            return list.stream().map(Long::valueOf).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

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
