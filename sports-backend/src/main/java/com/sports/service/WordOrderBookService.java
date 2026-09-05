package com.sports.service;

import com.sports.common.Grades;
import com.sports.entity.*;
import com.sports.repository.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 秩序册 Word 生成服务
 *
 * <p>不依赖 Apache POI：直接以 Office Open XML（WordprocessingML）标准结构生成 .docx
 * （ZIP 包 + 多个 XML 部件），零额外依赖、离线可构建。输出为真实 Word 文档，内嵌多个表格：</p>
 * <ul>
 *   <li>封面（运动会名称 / 届次 / 举办时间 / 编制日期）</li>
 *   <li>目录</li>
 *   <li>一、竞赛日程</li>
 *   <li>二、竞赛项目设置（径赛 / 田赛 / 其他）</li>
 *   <li>三、参赛单位（班级）</li>
 *   <li>四、分组与道次编排（按项目，预赛 / 决赛分别成表）</li>
 *   <li>五、运动员号码对照表（按年级 → 班级 → 名单顺序）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WordOrderBookService {

    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository scheduleRepository;
    private final AthleteRepository athleteRepository;
    private final SystemService systemService;

    private static final String FONT = "宋体";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int PAGE_W = 11906;
    private static final int MARGIN = 1080;
    private static final int USABLE = PAGE_W - MARGIN * 2; // 9746

    // ==================== 对外接口 ====================

    /** 下载 .docx 秩序册 */
    public void exportOrderBook(HttpServletResponse response) {
        try {
            byte[] data = buildOrderBook(null);
            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            response.setCharacterEncoding("utf-8");
            String fileName = "运动会秩序册_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".docx";
            String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);
            try (OutputStream out = response.getOutputStream()) {
                out.write(data);
                out.flush();
            }
            log.info("导出秩序册(Word) 成功: {} 字节", data.length);
        } catch (Exception e) {
            throw new RuntimeException("导出秩序册(Word)失败: " + e.getMessage(), e);
        }
    }

    /** 生成并落盘到 data/order_book/，返回元数据（供自动生成与手动「生成」使用） */
    public Map<String, Object> generateToDisk() {
        try {
            byte[] data = buildOrderBook(null);
            Path dir = Path.of("./data/order_book");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = dir.resolve("秩序册_" + ts + ".docx");
            Path latest = dir.resolve("秩序册_latest.docx");
            Files.write(file, data);
            Files.write(latest, data);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("file", file.toString());
            r.put("latest", latest.toString());
            r.put("generatedAt", LocalDateTime.now().format(FMT));
            r.put("size", data.length);
            log.info("秩序册(Word)已生成落盘: {}", file);
            return r;
        } catch (Exception e) {
            throw new RuntimeException("生成秩序册(Word)失败: " + e.getMessage(), e);
        }
    }

    // ==================== 文档构建 ====================

    private byte[] buildOrderBook(String gradeScope) {
        String meetName = resolveMeetName();
        Map<String, Object> scheduleCfg = systemService.getMeetSchedule();
        String startDate = str(scheduleCfg.get("startDate"));
        int days = intOf(scheduleCfg.get("days"), 2);

        List<String> gradeOrder = systemService.getGradeOrder();
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();
        List<EventSchedule> scheds = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();

        StringBuilder body = new StringBuilder();

        // ---- 封面 ----
        body.append(para(meetName, true, 48, "1F3864", "center"));
        body.append(para("秩 序 册", true, 44, "1F3864", "center"));
        body.append(para("（田径运动会）", false, 24, "404040", "center"));
        body.append(para("", false, 12, null, null));
        String end = startDate.isBlank() ? "—" : shiftDate(startDate, days - 1);
        body.append(para("举办时间：" + (startDate.isBlank() ? "待定" : startDate + " 至 " + end), false, 22, "404040", "center"));
        body.append(para("主办单位：学校体育运动委员会", false, 22, "404040", "center"));
        body.append(para("编制日期：" + LocalDateTime.now().format(FMT), false, 20, "808080", "center"));
        body.append(para("", false, 12, null, null));
        body.append(para("本秩序册依据报名审核与编排结果自动生成，最终以现场公告为准。", false, 18, "808080", "center"));
        body.append(pageBreak());

        // ---- 目录 ----
        body.append(heading("目　录", 1));
        body.append(para("一、竞赛日程", false, 22, "000000", null));
        body.append(para("二、竞赛项目设置", false, 22, "000000", null));
        body.append(para("三、参赛单位（班级）", false, 22, "000000", null));
        body.append(para("四、分组与道次编排", false, 22, "000000", null));
        body.append(para("五、运动员号码对照表", false, 22, "000000", null));
        body.append(pageBreak());

        // ---- 一、竞赛日程 ----
        body.append(heading("一、竞赛日程", 1));
        List<List<String>> schedRows = new ArrayList<>();
        for (EventSchedule s : scheds) {
            if (gradeScope != null && !gradeScope.isBlank()) {
                Event e0 = s.getEvent();
                boolean evMatch = e0 != null && Grades.same(gradeScope, e0.getGradeGroup());
                boolean schMatch = Grades.same(gradeScope, s.getGrade());
                if (!evMatch && !schMatch) continue;
            }
            Event e0 = s.getEvent();
            schedRows.add(List.of(
                    "第" + s.getDay() + "天",
                    n(s.getScheduleDate()), n(s.getTimeSlot()),
                    (n(s.getStartTime())) + "~" + (n(s.getEndTime())),
                    e0 != null ? n(e0.getName()) : "-",
                    e0 != null ? n(e0.getGenderLimit()) : "-",
                    n(s.getGrade()), n(s.getVenue())));
        }
        if (schedRows.isEmpty()) {
            body.append(para("（运动会日程尚未编排，请先在「赛程编排」中一键生成。）", false, 20, "808080", null));
        } else {
            body.append(table(List.of("天次", "日期", "时段", "时间", "项目", "性别", "年级", "场地"),
                    schedRows, equalWidths(8)));
        }
        body.append(pageBreak());

        // ---- 二、竞赛项目设置 ----
        body.append(heading("二、竞赛项目设置", 1));
        Map<String, List<Event>> byCat = new LinkedHashMap<>();
        byCat.put("径赛", new ArrayList<>());
        byCat.put("田赛", new ArrayList<>());
        byCat.put("其他", new ArrayList<>());
        for (Event e : events) {
            String cat = e.getCategory();
            if (cat == null) cat = "其他";
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(e);
        }
        int idx = 1;
        for (Map.Entry<String, List<Event>> en : byCat.entrySet()) {
            List<Event> list = en.getValue();
            if (list.isEmpty()) continue;
            body.append(heading(en.getKey() + "项目", 2));
            List<List<String>> rows = new ArrayList<>();
            for (Event e : list) {
                rows.add(List.of(String.valueOf(idx++), n(e.getCode()), n(e.getName()),
                        n(e.getCategory()), n(e.getGenderLimit()),
                        n(e.getGradeGroup()),
                        String.valueOf(e.getDefaultLanes() != null ? e.getDefaultLanes() : 8),
                        n(e.getRecord())));
            }
            body.append(table(List.of("序号", "编码", "项目名称", "类别", "性别", "年级组", "道次/人数", "校纪录"),
                    rows, equalWidths(8)));
        }
        body.append(pageBreak());

        // ---- 三、参赛单位（班级）----
        body.append(heading("三、参赛单位（班级）", 1));
        List<ClassInfo> sortedClasses = new ArrayList<>(classes);
        sortedClasses.sort(Comparator
                .comparingInt((ClassInfo c) -> gradeIdx(gradeOrder, c.getGrade()))
                .thenComparingInt(c -> c.getClassOrder() == null ? 0 : c.getClassOrder())
                .thenComparing(c -> n(c.getName())));
        List<List<String>> classRows = new ArrayList<>();
        int ci = 1;
        for (ClassInfo c : sortedClasses) {
            classRows.add(List.of(String.valueOf(ci++), n(c.getName()), n(c.getGrade()),
                    n(c.getTeacherName()), String.valueOf(c.getStudentCount() != null ? c.getStudentCount() : 0)));
        }
        body.append(table(List.of("序号", "班级名称", "年级", "班主任", "人数"), classRows, equalWidths(5)));
        body.append(pageBreak());

        // ---- 四、分组与道次编排 ----
        body.append(heading("四、分组与道次编排", 1));
        boolean anyArranged = false;
        for (Event e : events) {
            if (gradeScope != null && !gradeScope.isBlank()
                    && !(e.getGradeGroup() != null && Grades.same(gradeScope, e.getGradeGroup()))) {
                // 年级筛选：仅展示该年级项目；年级组为空者视为全部保留
                if (e.getGradeGroup() != null && !e.getGradeGroup().isBlank()) continue;
            }
            List<Arrangement> all = arrangementRepository.findByEventId(e.getId());
            if (all.isEmpty()) continue;
            anyArranged = true;

            // 按赛次分组（preliminary / final / 其他）
            Map<String, List<Arrangement>> byRound = all.stream().collect(Collectors.groupingBy(
                    a -> a.getRound() == null || a.getRound().isBlank() ? "final" : a.getRound(),
                    LinkedHashMap::new, Collectors.toList()));

            for (Map.Entry<String, List<Arrangement>> rEntry : byRound.entrySet()) {
                String round = rEntry.getKey();
                String roundLabel = "preliminary".equals(round) ? "预赛" : "final".equals(round) ? "决赛" : "编排";
                List<Arrangement> pool = rEntry.getValue();
                pool.sort(Comparator
                        .comparingInt((Arrangement a) -> a.getHeat() == null ? 0 : a.getHeat())
                        .thenComparingInt(a -> a.getLane() == null ? 0 : a.getLane()));
                body.append(heading(e.getName() + "（" + n(e.getGenderLimit()) + "）· " + roundLabel, 2));
                List<List<String>> rows = new ArrayList<>();
                for (Arrangement a : pool) {
                    Athlete at = a.getAthlete();
                    if (at == null) continue;
                    rows.add(List.of(
                            String.valueOf(a.getHeat()),
                            String.valueOf(a.getLane()),
                            n(at.getNumber()),
                            n(at.getName()),
                            at.getClassInfo() != null ? n(at.getClassInfo().getName()) : "-",
                            n(at.getGrade()),
                            "M".equals(at.getGender()) ? "男" : "F".equals(at.getGender()) ? "女" : "-"));
                }
                body.append(table(List.of("组次", "道次", "号码", "姓名", "班级", "年级", "性别"),
                        rows, equalWidths(7)));
            }
        }
        if (!anyArranged) {
            body.append(para("（各项目尚未编排，请在「道次编排」中生成预赛 / 决赛分组。）", false, 20, "808080", null));
        }
        body.append(pageBreak());

        // ---- 五、运动员号码对照表 ----
        body.append(heading("五、运动员号码对照表", 1));
        List<Athlete> athletes = athleteRepository.findAll().stream()
                .filter(a -> a.getDeletedAt() == null)
                .sorted(Comparator
                        .comparingInt((Athlete a) -> gradeIdx(gradeOrder, a.getGrade()))
                        .thenComparing(a -> a.getClassInfo() != null ? n(a.getClassInfo().getName()) : "",
                                Comparator.naturalOrder())
                        .thenComparing(a -> n(a.getName()))
                        .thenComparingLong(Athlete::getId))
                .collect(Collectors.toList());
        List<List<String>> numRows = new ArrayList<>();
        int ai = 1;
        for (Athlete a : athletes) {
            numRows.add(List.of(String.valueOf(ai++), n(a.getNumber()), n(a.getName()),
                    "M".equals(a.getGender()) ? "男" : "F".equals(a.getGender()) ? "女" : "-",
                    n(a.getGrade()),
                    a.getClassInfo() != null ? n(a.getClassInfo().getName()) : "-"));
        }
        if (numRows.isEmpty()) {
            body.append(para("（暂无运动员名单。）", false, 20, "808080", null));
        } else {
            body.append(table(List.of("序号", "号码", "姓名", "性别", "年级", "班级"), numRows, equalWidths(6)));
        }

        return buildPackage(wrapDocument(body.toString()), meetName);
    }

    // ==================== WordprocessingML 片段 ====================

    private String wrapDocument(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + inner
                + "<w:sectPr>"
                + "<w:pgSz w:w=\"" + PAGE_W + "\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"" + MARGIN + "\" w:right=\"" + MARGIN + "\" w:bottom=\"" + MARGIN
                + "\" w:left=\"" + MARGIN + "\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>";
    }

    private String para(String text, boolean bold, int halfPts, String color, String align) {
        String jc = align != null ? "<w:jc w:val=\"" + align + "\"/>" : "";
        StringBuilder rpr = new StringBuilder("<w:rPr>");
        if (bold) rpr.append("<w:b/>");
        if (color != null) rpr.append("<w:color w:val=\"").append(color).append("\"/>");
        if (halfPts > 0) rpr.append("<w:sz w:val=\"").append(halfPts).append("\"/><w:szCs w:val=\"").append(halfPts).append("\"/>");
        rpr.append("</w:rPr>");
        return "<w:p><w:pPr>" + jc
                + "<w:spacing w:before=\"60\" w:after=\"60\" w:line=\"288\" w:lineRule=\"auto\"/>"
                + "</w:pPr><w:r>" + rpr + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private String heading(String text, int level) {
        int size = level <= 1 ? 32 : 26;
        String color = level <= 1 ? "1F4E79" : "2E5496";
        return "<w:p><w:pPr><w:spacing w:before=\"200\" w:after=\"120\"/>"
                + (level <= 1 ? "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"1F4E79\"/></w:pBdr>" : "")
                + "</w:pPr><w:r><w:rPr><w:b/><w:color w:val=\"" + color + "\"/>"
                + "<w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private String pageBreak() {
        return "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>";
    }

    private String table(List<String> headers, List<List<String>> rows, int[] widths) {
        int n = headers.size();
        StringBuilder sb = new StringBuilder();
        sb.append("<w:tbl><w:tblPr>")
                .append("<w:tblW w:w=\"0\" w:type=\"auto\"/>")
                .append("<w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
                .append("</w:tblBorders>")
                .append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/>")
                .append("</w:tblPr><w:tblGrid>");
        for (int i = 0; i < n; i++) sb.append("<w:gridCol w:w=\"").append(widths[i]).append("\"/>");
        sb.append("</w:tblGrid>");

        // 表头行
        sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>");
        for (int i = 0; i < n; i++) {
            sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/>")
                    .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"2E5496\"/>")
                    .append("<w:vAlign w:val=\"center\"/></w:tcPr>")
                    .append(cellPara(headers.get(i), true, 18, "FFFFFF", "center"))
                    .append("</w:tc>");
        }
        sb.append("</w:tr>");

        // 数据行
        int ri = 0;
        for (List<String> row : rows) {
            String fill = (ri % 2 == 1) ? "F2F5FB" : "FFFFFF";
            sb.append("<w:tr>");
            for (int i = 0; i < n; i++) {
                String val = i < row.size() ? row.get(i) : "";
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[i]).append("\" w:type=\"dxa\"/>")
                        .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>")
                        .append("<w:vAlign w:val=\"center\"/></w:tcPr>")
                        .append(cellPara(val, false, 18, null, "center"))
                        .append("</w:tc>");
            }
            sb.append("</w:tr>");
            ri++;
        }
        sb.append("</w:tbl>");
        // 表后留白
        sb.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:p>");
        return sb.toString();
    }

    private String cellPara(String text, boolean bold, int halfPts, String color, String align) {
        String jc = align != null ? "<w:jc w:val=\"" + align + "\"/>" : "";
        StringBuilder rpr = new StringBuilder("<w:rPr>");
        if (bold) rpr.append("<w:b/>");
        if (color != null) rpr.append("<w:color w:val=\"").append(color).append("\"/>");
        if (halfPts > 0) rpr.append("<w:sz w:val=\"").append(halfPts).append("\"/><w:szCs w:val=\"").append(halfPts).append("\"/>");
        rpr.append("</w:rPr>");
        return "<w:p><w:pPr>" + jc
                + "<w:spacing w:before=\"20\" w:after=\"20\" w:line=\"240\" w:lineRule=\"auto\"/>"
                + "</w:pPr><w:r>" + rpr + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r></w:p>";
    }

    private int[] equalWidths(int n) {
        int[] w = new int[n];
        int base = USABLE / n;
        int rem = USABLE - base * n;
        for (int i = 0; i < n; i++) w[i] = base + (i < rem ? 1 : 0);
        return w;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String n(String s) { return s != null ? s : ""; }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? "" : String.valueOf(v);
    }

    private static String shiftDate(String startDate, int offset) {
        try { return LocalDate.parse(startDate).plusDays(offset).format(DATE); }
        catch (Exception e) { return startDate; }
    }

    private static int gradeIdx(List<String> order, String grade) {
        if (grade == null) return 999;
        for (int i = 0; i < order.size(); i++) {
            if (Grades.same(order.get(i), grade)) return i;
        }
        return 999;
    }

    private String resolveMeetName() {
        try {
            Object mn = systemService.getMeetSchedule().get("meetName");
            if (mn != null && !String.valueOf(mn).isBlank()) return String.valueOf(mn);
        } catch (Exception ignored) {}
        return "校园田径运动会";
    }

    // ==================== OOXML 包组装 ====================

    private byte[] buildPackage(String documentXml, String meetName) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addEntry(zos, "[Content_Types].xml", contentTypesXml());
            addEntry(zos, "_rels/.rels", relsRoot());
            addEntry(zos, "word/document.xml", documentXml);
            addEntry(zos, "word/styles.xml", stylesXml());
            addEntry(zos, "word/_rels/document.xml.rels", docRelsXml());
            addEntry(zos, "docProps/core.xml", coreXml(meetName));
            addEntry(zos, "docProps/app.xml", appXml());
        } catch (IOException e) {
            throw new RuntimeException("打包 DOCX 失败: " + e.getMessage(), e);
        }
        return baos.toByteArray();
    }

    private void addEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                + "</Types>";
    }

    private String relsRoot() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                + "</Relationships>";
    }

    private String docRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    private String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"" + FONT + "\" w:eastAsia=\"" + FONT + "\" w:hAnsi=\"" + FONT + "\" w:cs=\"" + FONT + "\"/>"
                + "<w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/>"
                + "</w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"60\" w:line=\"288\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
                + "</w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
                + "<w:rPr><w:rFonts w:ascii=\"" + FONT + "\" w:eastAsia=\"" + FONT + "\" w:hAnsi=\"" + FONT + "\"/></w:rPr></w:style>"
                + "</w:styles>";
    }

    private String coreXml(String meetName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + esc(meetName + " 秩序册") + "</dc:title>"
                + "<dc:creator>运动会智能编排系统</dc:creator>"
                + "<cp:lastModifiedBy>运动会智能编排系统</cp:lastModifiedBy>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }

    private String appXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
                + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<Application>运动会智能编排系统</Application>"
                + "<Company>学校体育运动委员会</Company>"
                + "</Properties>";
    }
}
