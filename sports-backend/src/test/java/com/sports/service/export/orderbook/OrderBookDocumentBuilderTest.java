package com.sports.service.export.orderbook;

import com.sports.entity.meet.SportsMeet;
import com.sports.entity.orderbook.OrderBookEntry;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.event.EventRefereeRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import com.sports.repository.referee.RefereeRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.service.export.orderbook.document.DocBlock;
import com.sports.service.export.orderbook.document.OrderBookDoc;
import com.sports.service.meet.MeetService;
import com.sports.service.system.SystemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 秩序册文档构建器的单测。
 *
 * <p>这一层最要紧的是<b>「管理员自定义章节一定要进文档」</b>：
 * 系统在订单里按固定章节灌数据，管理员额外写的「竞赛须知」「应急预案」如果漏了，
 * 导出和预览都会静默少一章，而且没人会察觉。所以这里用空编排数据 + 两条自定义细则，
 * 专门盯「内置章节还在、自定义章节也进来了」这件事。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderBookDocumentBuilderTest {

    @Mock private EventRepository eventRepository;
    @Mock private ClassInfoRepository classInfoRepository;
    @Mock private ArrangementRepository arrangementRepository;
    @Mock private EventScheduleRepository scheduleRepository;
    @Mock private AthleteRepository athleteRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private EventRefereeRepository eventRefereeRepository;
    @Mock private RefereeRepository refereeRepository;
    @Mock private SystemService systemService;
    @Mock private MeetService meetService;
    @Mock private OrderBookSectionRepository sectionRepository;
    @Mock private OrderBookEntryRepository entryRepository;

    @InjectMocks
    private OrderBookDocumentBuilder builder;

    @BeforeEach
    void setUp() {
        when(systemService.getMeetSchedule()).thenReturn(Map.of(
                "meetName", "第 30 届校运动会",
                "startDate", "2026-10-08",
                "days", 2));
        when(systemService.getGradeOrder()).thenReturn(List.of("高一", "高二"));
        when(meetService.getActive()).thenReturn(Optional.of(SportsMeet.builder()
                .id(1L).name("第 30 届校运动会").edition(30).build()));
    }

    private static OrderBookSection section(String title, boolean enabled, int sort) {
        return OrderBookSection.builder()
                .id(10L).meet(SportsMeet.builder().id(1L).build())
                .title(title).kind(OrderBookSection.KIND_CUSTOM)
                .level(1).sortOrder(sort).enabled(enabled).build();
    }

    private static OrderBookEntry entry(String title, String kind, String sourceKey,
                                        String contentType, String content, boolean enabled) {
        return OrderBookEntry.builder()
                .id(20L).section(OrderBookSection.builder().id(10L).build())
                .title(title).kind(kind).sourceKey(sourceKey).contentType(contentType)
                .content(content).sortOrder(0).enabled(enabled).build();
    }

    @Test
    @DisplayName("内置五章都在，管理员自定义目录与介绍内容也进文档")
    void builtInChaptersPlusCustomSections() {
        when(sectionRepository.findByMeet(1L)).thenReturn(List.of(
                section("竞赛须知", true, 1),
                section("应急预案", false, 2)));
        when(entryRepository.findBySection(10L)).thenReturn(List.of(
                entry("检录须知", OrderBookEntry.KIND_CUSTOM, null,
                        OrderBookEntry.CONTENT_TEXT, "请各班级于 8:00 前入场。", true),
                entry("上场路线", OrderBookEntry.KIND_CUSTOM, null,
                        OrderBookEntry.CONTENT_TEXT, "从东侧通道入场。", false)));

        OrderBookDoc doc = builder.build(null);

        List<String> headings = headings(doc);
        assertTrue(headings.contains("一、竞赛日程"), "内置章节不能被动掉");
        assertTrue(headings.contains("二、竞赛项目设置"));
        assertTrue(headings.contains("五、运动员号码对照表（仅参赛运动员）"));
        assertTrue(headings.contains("竞赛须知"), "启用的自定义目录必须进");
        assertFalse(headings.contains("应急预案"), "未启用的不该进");

        assertTrue(texts(doc).contains("请各班级于 8:00 前入场。"), "介绍内容正文必须进");
        assertFalse(texts(doc).contains("从东侧通道入场。"), "未启用的细则不该进正文");
    }

    @Test
    @DisplayName("自定义目录里的系统板块复用内置表格（同一批数据，不会两边打架）")
    void systemSourceReusesBuiltInTables() {
        when(sectionRepository.findByMeet(1L)).thenReturn(List.of(section("竞赛日程", true, 1)));
        when(entryRepository.findBySection(10L)).thenReturn(List.of(
                entry(null, OrderBookEntry.KIND_SYSTEM, OrderBookEntry.SRC_SCHEDULE,
                        OrderBookEntry.CONTENT_TABLE, null, true)));
        when(scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc()).thenReturn(List.of());

        OrderBookDoc doc = builder.build(null);

        DocBlock.Table schedTable = doc.blocks().stream()
                .filter(b -> b instanceof DocBlock.Table)
                .map(b -> (DocBlock.Table) b)
                .filter(t -> t.headers().size() == 9)
                .findFirst()
                .orElse(null);
        assertNotNull(schedTable, "SCHEDULE 系统板块应渲染成九列日程表");
    }

    @Test
    @DisplayName("认不出的 sourceKey 如实提示，不静默吞掉")
    void unknownSourceIsNotSilentlyDropped() {
        when(sectionRepository.findByMeet(1L)).thenReturn(List.of(section("奇怪板块", true, 1)));
        when(entryRepository.findBySection(10L)).thenReturn(List.of(
                entry(null, OrderBookEntry.KIND_SYSTEM, "NOT_A_SOURCE",
                        OrderBookEntry.CONTENT_TABLE, null, true)));

        OrderBookDoc doc = builder.build(null);

        assertTrue(texts(doc).stream().anyMatch(t -> t.contains("数据源尚未实现")),
                "认不出的数据源要明说，否则管理员以为导出了其实没有");
    }

    @Test
    @DisplayName("没届次时安静返回内置内容，不抛异常也不造届")
    void noMeetIsTolerated() {
        when(meetService.getActive()).thenReturn(Optional.empty());

        OrderBookDoc doc = builder.build(null);

        assertTrue(headings(doc).contains("一、竞赛日程"));
        assertFalse(headings(doc).contains("竞赛须知"));
    }

    @Test
    @DisplayName("正文按空行分段，一段一段进文档")
    void textContentIsSplitIntoParagraphs() {
        when(sectionRepository.findByMeet(1L)).thenReturn(List.of(section("须知", true, 1)));
        when(entryRepository.findBySection(10L)).thenReturn(List.of(
                entry(null, OrderBookEntry.KIND_CUSTOM, null, OrderBookEntry.CONTENT_TEXT,
                        "第一段。\n\n第二段。", true)));

        OrderBookDoc doc = builder.build(null);

        List<String> texts = texts(doc);
        assertTrue(texts.contains("第一段。"));
        assertTrue(texts.contains("第二段。"));
    }

    // ==================== 工具 ====================

    private static List<String> headings(OrderBookDoc doc) {
        return doc.blocks().stream()
                .filter(b -> b instanceof DocBlock.Heading)
                .map(b -> ((DocBlock.Heading) b).text())
                .filter(t -> t != null && !t.startsWith("目"))
                .toList();
    }

    private static List<String> texts(OrderBookDoc doc) {
        return doc.blocks().stream()
                .filter(b -> b instanceof DocBlock.Paragraph)
                .map(b -> ((DocBlock.Paragraph) b).text())
                .filter(t -> t != null && !t.isBlank())
                .toList();
    }
}
