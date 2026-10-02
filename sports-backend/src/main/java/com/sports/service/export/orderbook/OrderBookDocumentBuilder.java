package com.sports.service.export.orderbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.common.util.Grades;
import com.sports.common.util.RoundLabelUtil;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventReferee;
import com.sports.entity.event.EventSchedule;
import com.sports.entity.orderbook.OrderBookEntry;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.entity.referee.Referee;
import com.sports.entity.registration.Registration;
import com.sports.entity.user.User;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import com.sports.repository.referee.RefereeRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.service.export.orderbook.document.OrderBookDoc;
import com.sports.service.meet.MeetService;
import com.sports.service.system.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 秩序册<b>文档构建器</b>：把编排数据 + 管理员自定义的目录细则，按秩序册的既有顺序拼成
 * {@link OrderBookDoc} 模型，供 HTML 预览与 Word 导出两条渲染通道共用。
 *
 * <p>抽出来的原因很实际：预览和导出要<b>长得一模一样</b>，如果两边各自查一遍数据库、各自拼一遍行，
 * 迟早会出现「预览有这张表、导出少一行」这种最难查的 bug。这里把数据只算一遍，
 * 两个渲染器都只吃 {@link OrderBookDoc}。</p>
 *
 * <p>内容分两块：</p>
 * <ul>
 *   <li><b>内置章节</b>：封面 / 目录 / 竞赛日程 / 项目设置 / 参赛单位 / 分组编排 / 号码对照，
 *       这五章是秩序册的骨架，由编排数据实时生成，管理员改不动（改了反而会与现场不符）。</li>
 *   <li><b>管理员自定义章节</b>（F3 的 {@link OrderBookSection} / {@link OrderBookEntry}）：
 *       纯介绍文字按正文输出，系统板块（SCHEDULE / EVENTS / CLASSES / ARRANGE / NUMBERS）
 *       直接复用上面算好的同一批表格——所以自定义章节里的数字和内置章节必然一致。</li>
 * </ul>
 *
 * <p>顺序规矩：<b>内置章节在前，自定义目录按管理员排的序接在后面</b>（同级内按 level → sortOrder → id）。
 * 跨内置/自定义做重排会让「某一章到底算哪边的」变得含糊，管理员也难预期。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookDocumentBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String ORGANIZER = "学校体育运动委员会";

    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository scheduleRepository;
    private final AthleteRepository athleteRepository;
    private final RegistrationRepository registrationRepository;
    private final EventRefereeRepository eventRefereeRepository;
    private final RefereeRepository refereeRepository;
    private final SystemService systemService;
    private final MeetService meetService;
    private final OrderBookSectionRepository sectionRepository;
    private final OrderBookEntryRepository entryRepository;

    /**
     * 构建整本秩序册。
     *
     * @param gradeScope 年级筛选（null / 空 = 全部）；传入时只出该年级相关的日程与编排
     */
    @Transactional(readOnly = true)
    public OrderBookDoc build(String gradeScope) {
        // ============ 一、元数据与参与人 ============
        Map<String, Object> cfg = systemService.getMeetSchedule();
        String meetName = meetName(cfg);
        String startDate = str(cfg.get("startDate"));
        int days = intOf(cfg.get("days"), 2);

        List<String> gradeOrder = systemService.getGradeOrder();
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();
        Set<Long> participantIds = registrationRepository.findByStatus("approved").stream()
                .map(r -> r.getAthlete() != null ? r.getAthlete().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Long> participantCountByClass = new LinkedHashMap<>();
        for (Athlete p : athleteRepository.findAllById(participantIds)) {
            if (p.getDeletedAt() != null || p.getClassInfo() == null) {
                continue;
            }
            participantCountByClass.merge(p.getClassInfo().getId(), 1L, Long::sum);
        }
        List<EventSchedule> scheds = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();

        // ============ 二、内置五章的数据（自定义章节的 SYSTEM 表格会复用这批） ============
        List<List<String>> schedRows = buildScheduleRows(scheds, gradeScope);
        List<EventTable> eventTables = buildEventTables(events, gradeScope);
        List<List<String>> classRows = buildClassRows(classes, gradeOrder, participantCountByClass);
        List<EventTable> arrangeTables = buildArrangeTables(events, gradeScope);
        List<List<String>> numberRows = buildNumberRows(participantIds, gradeOrder);

        // ============ 三、拼块 ============
        String end = startDate.isBlank() ? "—" : shiftDate(startDate, days - 1);
        OrderBookDoc doc = new OrderBookDoc(new OrderBookDoc.Meta(
                meetName,
                String.valueOf(days),
                startDate.isBlank() ? "待定" : startDate + " 至 " + end,
                ORGANIZER,
                LocalDateTime.now().format(FMT)));

        // ---- 封面 ----
        doc.paragraph(meetName, true, 48, "1F3864", "center");
        doc.paragraph("秩 序 册", true, 44, "1F3864", "center");
        doc.paragraph("（田径运动会）", false, 24, "404040", "center");
        doc.paragraph("", false, 12, null, null);
        doc.paragraph("举办时间：" + doc.meta().dateRange(), false, 22, "404040", "center");
        doc.paragraph("主办单位：" + ORGANIZER, false, 22, "404040", "center");
        doc.paragraph("编制日期：" + doc.meta().compiledAt(), false, 20, "808080", "center");
        doc.paragraph("", false, 12, null, null);
        doc.paragraph("本秩序册依据报名审核与编排结果自动生成，最终以现场公告为准。", false, 18, "808080", "center");
        doc.pageBreak();

        // ---- 目录 ----
        doc.h1("目　录");
        for (EventTable t : eventTables) {
            doc.paragraph("- " + t.heading, false, 22, "000000", null);
        }
        layoutService_sections().forEach(s -> doc.paragraph("- " + s, false, 22, "000000", null));
        doc.pageBreak();

        // ---- 一、竞赛日程 ----
        doc.h1("一、竞赛日程");
        if (schedRows.isEmpty()) {
            doc.note("（运动会日程尚未编排，请先在「赛程编排」中一键生成。）");
        } else {
            doc.table(List.of("天次", "日期", "时段", "时间", "项目", "轮次", "性别", "年级", "场地"),
                    schedRows);
        }
        doc.pageBreak();

        // ---- 二、竞赛项目设置 ----
        doc.h1("二、竞赛项目设置");
        if (eventTables.isEmpty()) {
            doc.note("（尚未设置竞赛项目。）");
        } else {
            for (EventTable t : eventTables) {
                doc.h2(t.heading);
                doc.table(t.headers, t.rows);
            }
        }
        doc.pageBreak();

        // ---- 三、参赛单位（班级）----
        doc.h1("三、参赛单位（班级）");
        doc.table(List.of("序号", "班级名称", "年级", "班主任", "人数"), classRows);
        doc.pageBreak();

        // ---- 四、分组与道次编排 ----
        doc.h1("四、分组与道次编排");
        if (arrangeTables.isEmpty()) {
            doc.note("（各项目尚未编排，请在「道次编排」中生成预赛 / 决赛分组。）");
        } else {
            for (EventTable t : arrangeTables) {
                doc.h2(t.heading);
                doc.table(t.headers, t.rows);
            }
        }
        doc.pageBreak();

        // ---- 五、运动员号码对照表 ----
        doc.h1("五、运动员号码对照表（仅参赛运动员）");
        if (numberRows.isEmpty()) {
            doc.note("（暂无参赛运动员名单。）");
        } else {
            doc.table(List.of("序号", "号码", "姓名", "性别", "年级", "班级"), numberRows);
            doc.note("注：仅含已报名审核通过的参赛运动员；全校完整号码库请从「运动员管理」导出。");
        }

        // ---- 六、管理员自定义章节 ----
        appendCustomSections(doc, gradeScope);
        return doc;
    }

    // ==================== 管理员自定义章节 ====================

    /**
     * 追加管理员自定义目录与其细则。
     *
     * <p>内置章节永远在这后面：跨「内置 / 自定义」做重排会让「这一章算哪边的」含糊，
     * 管理员也难预期结果，不如把规矩说死。</p>
     */
    private void appendCustomSections(OrderBookDoc doc, String gradeScope) {
        Long meetId = meetService.getActive().map(m -> m.getId()).orElse(null);
        if (meetId == null) {
            return;
        }
        List<OrderBookSection> sections = new ArrayList<>(sectionRepository.findByMeet(meetId));
        sections.removeIf(s -> !Boolean.TRUE.equals(s.getEnabled()));
        sections.sort(Comparator
                .comparing((OrderBookSection s) -> s.getLevel() == null ? 1 : s.getLevel())
                .thenComparingInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder())
                .thenComparing(OrderBookSection::getId));
        if (sections.isEmpty()) {
            return;
        }
        doc.pageBreak();

        for (OrderBookSection s : sections) {
            int level = s.getLevel() == null ? 1 : s.getLevel();
            doc.heading(s.getTitle(), level <= 1 ? 1 : 2);
            List<OrderBookEntry> entries = new ArrayList<>(entryRepository.findBySection(s.getId()));
            entries.removeIf(e -> !Boolean.TRUE.equals(e.getEnabled()));
            entries.sort(Comparator
                    .comparingInt((OrderBookEntry e) -> e.getSortOrder() == null ? 0 : e.getSortOrder())
                    .thenComparing(OrderBookEntry::getId));
            for (OrderBookEntry e : entries) {
                appendEntry(doc, e, gradeScope);
            }
        }
    }

    /** 一条细则：介绍文字按段落输出；系统板块取同一批已算好的表格。 */
    private void appendEntry(OrderBookDoc doc, OrderBookEntry e, String gradeScope) {
        if (e.getTitle() != null && !e.getTitle().isBlank()) {
            doc.h2(e.getTitle().trim());
        }
        if (OrderBookEntry.CONTENT_TEXT.equals(e.getContentType())) {
            // 纯文本正文：按空行分段，一段一段出，避免 Word 里糊成一坨
            for (String para : splitParagraphs(e.getContent())) {
                doc.paragraph(para, false, 21, null, null);
            }
            return;
        }
        appendSourceTable(doc, normalizeSource(e.getSourceKey()), gradeScope);
    }

    private void appendSourceTable(OrderBookDoc doc, String sourceKey, String gradeScope) {
        switch (sourceKey) {
            case OrderBookEntry.SRC_SCHEDULE -> doc.table(
                    List.of("天次", "日期", "时段", "时间", "项目", "轮次", "性别", "年级", "场地"),
                    buildScheduleRows(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc(),
                            gradeScope));
            case OrderBookEntry.SRC_EVENTS -> {
                boolean any = false;
                for (EventTable t : buildEventTables(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc(),
                        gradeScope)) {
                    doc.h2(t.heading);
                    doc.table(t.headers, t.rows);
                    any = true;
                }
                if (!any) {
                    doc.note("（尚未设置竞赛项目。）");
                }
            }
            case OrderBookEntry.SRC_CLASSES -> doc.table(
                    List.of("序号", "班级名称", "年级", "班主任", "人数"),
                    buildClassRows(classInfoRepository.findByIsParticipatingTrue(),
                            systemService.getGradeOrder(), Map.of()));
            case OrderBookEntry.SRC_ARRANGE -> {
                boolean any = false;
                for (EventTable t : buildArrangeTables(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc(),
                        gradeScope)) {
                    doc.h2(t.heading);
                    doc.table(t.headers, t.rows);
                    any = true;
                }
                if (!any) {
                    doc.note("（各项目尚未编排，请在「道次编排」中生成预赛 / 决赛分组。）");
                }
            }
            case OrderBookEntry.SRC_NUMBERS -> {
                List<List<String>> rows = buildNumberRows(
                        registrationRepository.findByStatus("approved").stream()
                                .map(r -> r.getAthlete() != null ? r.getAthlete().getId() : null)
                                .filter(Objects::nonNull).collect(Collectors.toSet()),
                        systemService.getGradeOrder());
                if (rows.isEmpty()) {
                    doc.note("（暂无参赛运动员名单。）");
                } else {
                    doc.table(List.of("序号", "号码", "姓名", "性别", "年级", "班级"), rows);
                    doc.note("注：仅含已报名审核通过的参赛运动员。");
                }
            }
            default -> {
                // 认不出的 sourceKey：如实留一行，别静默吞掉（吞了管理员以为导出了其实没有）
                doc.note("（该细则的数据源尚未实现，请改用「介绍内容」填写。）");
            }
        }
    }

    private static String normalizeSource(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return switch (key.trim().toUpperCase()) {
            case OrderBookEntry.SRC_SCHEDULE, OrderBookEntry.SRC_EVENTS, OrderBookEntry.SRC_CLASSES,
                    OrderBookEntry.SRC_ARRANGE, OrderBookEntry.SRC_NUMBERS -> key.trim().toUpperCase();
            default -> "";
        };
    }

    private static List<String> splitParagraphs(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String line : content.split("\\n\\s*\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 目录里要列出的自定义章节标题（只列一级，二级挂在它下面）。 */
    private List<String> layoutService_sections() {
        Long meetId = meetService.getActive().map(m -> m.getId()).orElse(null);
        if (meetId == null) {
            return List.of();
        }
        return sectionRepository.findByMeet(meetId).stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .filter(s -> s.getLevel() == null || s.getLevel() <= 1)
                .sorted(Comparator
                        .comparing((OrderBookSection s) -> s.getLevel() == null ? 1 : s.getLevel())
                        .thenComparingInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder())
                        .thenComparing(OrderBookSection::getId))
                .map(OrderBookSection::getTitle)
                .filter(Objects::nonNull)
                .toList();
    }

    // ==================== 各章数据 ====================

    private List<List<String>> buildScheduleRows(List<EventSchedule> scheds, String gradeScope) {
        Set<Long> prelimEventIds = arrangementRepository.findAll().stream()
                .filter(a -> "preliminary".equals(a.getRound()))
                .map(a -> a.getEvent() != null ? a.getEvent().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<List<String>> rows = new ArrayList<>();
        for (EventSchedule s : scheds) {
            if (gradeScope != null && !gradeScope.isBlank()) {
                boolean evMatch = s.getEvent() != null && Grades.same(gradeScope, s.getEvent().getGradeGroup());
                boolean schMatch = Grades.same(gradeScope, s.getGrade());
                if (!evMatch && !schMatch) {
                    continue;
                }
            }
            Event e = s.getEvent();
            long evId = e != null && e.getId() != null ? e.getId() : -1L;
            String roundLabel = RoundLabelUtil.label(s.getRound(), prelimEventIds.contains(evId));
            rows.add(List.of(
                    "第" + s.getDay() + "天",
                    n(s.getScheduleDate()), n(s.getTimeSlot()),
                    n(s.getStartTime()) + "~" + n(s.getEndTime()),
                    e != null ? n(e.getName()) : "-",
                    roundLabel,
                    e != null ? n(e.getGenderLimit()) : "-",
                    n(s.getGrade()), n(s.getVenue())));
        }
        return rows;
    }

    private List<EventTable> buildEventTables(List<Event> events, String gradeScope) {
        Map<String, List<Event>> byCat = new LinkedHashMap<>();
        byCat.put("径赛", new ArrayList<>());
        byCat.put("田赛", new ArrayList<>());
        byCat.put("其他", new ArrayList<>());
        for (Event e : events) {
            if (gradeScope != null && !gradeScope.isBlank()
                    && e.getGradeGroup() != null && !e.getGradeGroup().isBlank()
                    && !(Grades.same(gradeScope, e.getGradeGroup()))) {
                continue;
            }
            String cat = e.getCategory() == null || e.getCategory().isBlank() ? "其他" : e.getCategory();
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(e);
        }
        List<EventTable> out = new ArrayList<>();
        int idx = 1;
        for (Map.Entry<String, List<Event>> en : byCat.entrySet()) {
            List<Event> list = en.getValue();
            if (list.isEmpty()) {
                continue;
            }
            List<List<String>> rows = new ArrayList<>();
            for (Event e : list) {
                rows.add(List.of(String.valueOf(idx++), n(e.getCode()), n(e.getName()),
                        n(e.getCategory()), n(e.getGenderLimit()), n(e.getGradeGroup()),
                        String.valueOf(e.getDefaultLanes() != null ? e.getDefaultLanes() : 8),
                        n(e.getRecord())));
            }
            out.add(new EventTable(en.getKey() + "项目",
                    List.of("序号", "编码", "项目名称", "类别", "性别", "年级组", "道次/人数", "校纪录"), rows));
        }
        return out;
    }

    private List<List<String>> buildClassRows(List<ClassInfo> classes, List<String> gradeOrder,
                                              Map<Long, Long> participantCountByClass) {
        List<ClassInfo> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator
                .comparingInt((ClassInfo c) -> gradeIdx(gradeOrder, c.getGrade()))
                .thenComparingInt(c -> c.getClassOrder() == null ? 0 : c.getClassOrder())
                .thenComparing(c -> n(c.getName())));
        List<List<String>> rows = new ArrayList<>();
        int ci = 1;
        for (ClassInfo c : sorted) {
            rows.add(List.of(String.valueOf(ci++), n(c.getName()), n(c.getGrade()),
                    teacherLabel(c),
                    String.valueOf(participantCountByClass.getOrDefault(c.getId(), 0L))));
        }
        return rows;
    }

    private List<EventTable> buildArrangeTables(List<Event> events, String gradeScope) {
        Map<Long, Referee> refMap = refereeRepository.findAll().stream()
                .collect(Collectors.toMap(Referee::getId, r -> r, (a, b) -> a));
        Map<String, String> refByHeat = new HashMap<>();
        for (EventReferee er : eventRefereeRepository.findAll()) {
            if (er.getEvent() == null) {
                continue;
            }
            String names = parseRefIds(er.getRefereeIds()).stream()
                    .map(id -> refMap.get(id) != null ? refMap.get(id).getName() : "未知")
                    .collect(Collectors.joining("、"));
            refByHeat.put(er.getEvent().getId() + "|" + (er.getGrade() == null ? "" : er.getGrade()) + "|"
                    + (er.getRound() == null ? "" : er.getRound()) + "|" + er.getHeat(), names);
        }

        List<EventTable> out = new ArrayList<>();
        for (Event e : events) {
            if (gradeScope != null && !gradeScope.isBlank()
                    && e.getGradeGroup() != null && !e.getGradeGroup().isBlank()
                    && !(Grades.same(gradeScope, e.getGradeGroup()))) {
                continue;
            }
            List<Arrangement> all = arrangementRepository.findByEventId(e.getId());
            if (all.isEmpty()) {
                continue;
            }
            Map<String, List<Arrangement>> byRound = all.stream().collect(Collectors.groupingBy(
                    a -> a.getRound() == null || a.getRound().isBlank() ? "" : a.getRound(),
                    LinkedHashMap::new, Collectors.toList()));
            boolean hasPrelim = all.stream().anyMatch(a -> "preliminary".equals(a.getRound()));
            for (Map.Entry<String, List<Arrangement>> rEntry : byRound.entrySet()) {
                String round = rEntry.getKey();
                String roundLabel = RoundLabelUtil.label(round, hasPrelim);
                Map<String, List<Arrangement>> byGrade = new LinkedHashMap<>();
                for (Arrangement a : rEntry.getValue()) {
                    String g = a.getGrade() == null || a.getGrade().isBlank() ? "不分年级" : a.getGrade();
                    byGrade.computeIfAbsent(g, k -> new ArrayList<>()).add(a);
                }
                boolean isField = Boolean.FALSE.equals(e.getTrack());
                for (Map.Entry<String, List<Arrangement>> gEntry : byGrade.entrySet()) {
                    List<Arrangement> pool = gEntry.getValue();
                    pool.sort(Comparator
                            .comparingInt((Arrangement a) -> a.getHeat() == null ? 0 : a.getHeat())
                            .thenComparingInt(a -> a.getLane() == null ? 0 : a.getLane()));
                    String heading = e.getName() + "（" + n(e.getGenderLimit()) + "）· "
                            + roundLabel + " · " + gEntry.getKey();
                    List<List<String>> rows = new ArrayList<>();
                    if (isField) {
                        int seq = 1;
                        for (Arrangement a : pool) {
                            Athlete at = a.getAthlete();
                            if (at == null) {
                                continue;
                            }
                            rows.add(List.of(String.valueOf(seq++), n(at.getNumber()), n(at.getName()),
                                    at.getClassInfo() != null ? n(at.getClassInfo().getName()) : "-",
                                    "M".equals(at.getGender()) ? "男" : "F".equals(at.getGender()) ? "女" : "-",
                                    refByHeat.getOrDefault(
                                            e.getId() + "|" + gEntry.getKey() + "|" + round + "|" + a.getHeat(),
                                            "")));
                        }
                        out.add(new EventTable(heading,
                                List.of("出场顺序", "号码", "姓名", "班级", "性别", "裁判"), rows));
                    } else {
                        for (Arrangement a : pool) {
                            Athlete at = a.getAthlete();
                            if (at == null) {
                                continue;
                            }
                            rows.add(List.of(String.valueOf(a.getHeat()), String.valueOf(a.getLane()),
                                    n(at.getNumber()), n(at.getName()),
                                    at.getClassInfo() != null ? n(at.getClassInfo().getName()) : "-",
                                    "M".equals(at.getGender()) ? "男" : "F".equals(at.getGender()) ? "女" : "-",
                                    refByHeat.getOrDefault(
                                            e.getId() + "|" + gEntry.getKey() + "|" + round + "|" + a.getHeat(),
                                            "")));
                        }
                        out.add(new EventTable(heading,
                                List.of("组次", "道次", "号码", "姓名", "班级", "性别", "裁判"), rows));
                    }
                }
            }
        }
        return out;
    }

    private List<List<String>> buildNumberRows(Set<Long> participantIds, List<String> gradeOrder) {
        if (participantIds.isEmpty()) {
            return List.of();
        }
        return athleteRepository.findAllById(participantIds).stream()
                .filter(a -> a.getDeletedAt() == null)
                .sorted(Comparator
                        .comparingInt((Athlete a) -> gradeIdx(gradeOrder, a.getGrade()))
                        .thenComparing(a -> a.getClassInfo() != null ? n(a.getClassInfo().getName()) : "")
                        .thenComparing(a -> n(a.getName()))
                        .thenComparingLong(Athlete::getId))
                .map(a -> List.of(n(a.getNumber()), n(a.getName()),
                        "M".equals(a.getGender()) ? "男" : "F".equals(a.getGender()) ? "女" : "-",
                        n(a.getGrade()),
                        a.getClassInfo() != null ? n(a.getClassInfo().getName()) : "-"))
                .collect(Collectors.toList());
    }

    // ==================== 小工具 ====================

    /** 一张「标题 + 表头 + 行」的小模型，章节之间复用。 */
    public record EventTable(String heading, List<String> headers, List<List<String>> rows) {
    }

    private static String teacherLabel(ClassInfo c) {
        if (c.getTeacherName() != null && !c.getTeacherName().isBlank()) {
            return c.getTeacherName().trim();
        }
        User t = c.getTeacherUser();
        if (t != null) {
            if (t.getName() != null && !t.getName().isBlank()) {
                return t.getName().trim();
            }
            if (t.getUsername() != null && !t.getUsername().isBlank()) {
                return t.getUsername().trim();
            }
        }
        return "-";
    }

    private static List<Long> parseRefIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Integer> list = MAPPER.readValue(json, new TypeReference<List<Integer>>() {});
            return list.stream().map(Long::valueOf).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String meetName(Map<String, Object> cfg) {
        try {
            Object v = cfg.get("meetName");
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        } catch (Exception ignored) {
            // 配置缺字段时静默回退到默认名，别让整本秩序册生成失败
        }
        return "校园田径运动会";
    }

    private static int gradeIdx(List<String> order, String grade) {
        if (grade == null) {
            return 999;
        }
        for (int i = 0; i < order.size(); i++) {
            if (Grades.same(order.get(i), grade)) {
                return i;
            }
        }
        return 999;
    }

    private static String shiftDate(String startDate, int offset) {
        try {
            return LocalDate.parse(startDate).plusDays(offset).format(DATE);
        } catch (Exception e) {
            return startDate;
        }
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? "" : String.valueOf(v);
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Exception ignored) {
                // 落回默认值
            }
        }
        return def;
    }

    private static String n(String s) {
        return s != null ? s : "";
    }
}
