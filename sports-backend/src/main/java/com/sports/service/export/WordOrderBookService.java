package com.sports.service.export;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.service.export.orderbook.OrderBookDocumentBuilder;
import com.sports.service.export.orderbook.OrderBookWordRenderer;
import com.sports.service.export.orderbook.document.OrderBookDoc;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 秩序册 Word 导出服务（对外入口）。
 *
 * <p>这一层<b>只剩三件事</b>：拼 OOXML 包、写 HTTP 响应头、落盘。
 * 文档内容怎么拼（数据 + 管理员自定义章节）在 {@link OrderBookDocumentBuilder}，
 * 块怎么变成 Word XML 在 {@link OrderBookWordRenderer}，
 * 块怎么变成浏览器里能看的 HTML 在 {@link com.sports.service.export.orderbook.OrderBookHtmlRenderer}——
 * 三个出口吃同一份 {@link OrderBookDoc}，所以预览和导出不可能对不上。</p>
 *
 * <p>不依赖 Apache POI：直接以 Office Open XML（WordprocessingML）标准结构生成 .docx
 * （ZIP 包 + 多个 XML 部件），零额外依赖、离线可构建。内嵌多个表格：封面、目录、
 * 竞赛日程、竞赛项目设置、参赛单位、分组与道次编排、运动员号码对照表，
 * 以及管理员在秩序册设计器里自定义的所有目录与细则。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WordOrderBookService {

    private final ArrangementRepository arrangementRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final OrderBookDocumentBuilder documentBuilder;
    private final OrderBookWordRenderer wordRenderer;

    private static final String FONT = "宋体";
    private static final int PAGE_W = 11906;
    private static final int MARGIN = 1080;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ==================== 对外接口 ====================

    /** 下载 .docx 秩序册 */
    public void exportOrderBook(HttpServletResponse response) {
        try {
            byte[] data = buildOrderBook(null);
            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            response.setCharacterEncoding("utf-8");
            String fileName = "运动会秩序册_v" + com.sports.common.util.ExportNaming.appVersion()
                    + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".docx";
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
            return writeDisk(data, "秩序册", null);
        } catch (Exception e) {
            throw new RuntimeException("生成秩序册(Word)失败: " + e.getMessage(), e);
        }
    }

    /**
     * U14/U15：一键生成「最终秩序册」——基于当前（二次编排后的）编排结果生成，
     * 生成前做完整性校验（参赛名单与报名一致、无未报名人员混入），返回值含校验结果。
     */
    public Map<String, Object> generateFinalToDisk() {
        Map<String, Object> integrity = orderBookIntegrityCheck();
        try {
            byte[] data = buildOrderBook(null);
            return writeDisk(data, "秩序册_最终", integrity);
        } catch (Exception e) {
            throw new RuntimeException("生成最终秩序册(Word)失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> writeDisk(byte[] data, String prefix, Map<String, Object> integrity) {
        try {
            Path dir = Path.of("./data/order_book");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = dir.resolve(prefix + "_" + ts + ".docx");
            Path latest = dir.resolve(prefix.endsWith("最终") ? "秩序册_final.docx" : "秩序册_latest.docx");
            Files.write(file, data);
            Files.write(latest, data);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("file", file.toString());
            r.put("latest", latest.toString());
            r.put("generatedAt", LocalDateTime.now().format(FMT));
            r.put("size", data.length);
            if (integrity != null) {
                r.put("integrity", integrity);
            }
            log.info("秩序册(Word)已生成落盘: {}", file);
            return r;
        } catch (IOException e) {
            throw new RuntimeException("秩序册落盘失败: " + e.getMessage(), e);
        }
    }

    /** U14/U15：秩序册完整性校验——参赛人数与报名审核一致、无未报名人员混入、含决赛项目数 */
    public Map<String, Object> orderBookIntegrityCheck() {
        List<Registration> approved = registrationRepository.findByStatus("approved");
        java.util.Set<Long> regAthletes = approved.stream()
                .map(Registration::getAthlete).filter(Objects::nonNull)
                .map(Athlete::getId).collect(Collectors.toSet());
        java.util.Set<Long> arranged = arrangementRepository.findAll().stream()
                .map(Arrangement::getAthlete).filter(Objects::nonNull)
                .map(Athlete::getId).collect(Collectors.toSet());
        long arrangedNotRegistered = arranged.stream().filter(id -> !regAthletes.contains(id)).count();
        long registeredNotArranged = regAthletes.stream().filter(id -> !arranged.contains(id)).count();

        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        long eventsWithArrangement = events.stream()
                .filter(e -> !arrangementRepository.findByEventId(e.getId()).isEmpty()).count();
        long finalCount = arrangementRepository.findAll().stream()
                .filter(a -> "final".equals(a.getRound())).count();

        Map<String, Object> check = new LinkedHashMap<>();
        check.put("registrationApproved", regAthletes.size());
        check.put("arrangedAthletes", arranged.size());
        check.put("arrangedNotRegistered", arrangedNotRegistered);
        check.put("registeredNotArranged", registeredNotArranged);
        check.put("eventCount", events.size());
        check.put("eventsWithArrangement", eventsWithArrangement);
        check.put("finalArrangementCount", finalCount);
        // ok：无未报名人员混入（硬性），且所有项目均已编排
        boolean ok = arrangedNotRegistered == 0 && eventsWithArrangement >= events.size();
        check.put("ok", ok);
        check.put("message", ok ? "秩序册完整性校验通过" : "秩序册存在缺失或未报名人员混入，请复核");
        return check;
    }

    /** 拼一本秩序册的 docx 字节（内容来自文档构建器，这里只做包装）。 */
    private byte[] buildOrderBook(String gradeScope) {
        OrderBookDoc doc = documentBuilder.build(gradeScope);
        String bodyXml = wordRenderer.renderBody(doc);
        return buildPackage(wrapDocument(bodyXml), doc.meta().meetName());
    }

    // ==================== OOXML 包组装 ====================

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

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
