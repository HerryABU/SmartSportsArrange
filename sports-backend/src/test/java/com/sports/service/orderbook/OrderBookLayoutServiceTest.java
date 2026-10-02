package com.sports.service.orderbook;

import com.sports.entity.meet.SportsMeet;
import com.sports.entity.orderbook.OrderBookEntry;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import com.sports.service.meet.MeetService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 秩序册排版服务的单测：只验「顺序」与「启用了哪些」这两件事。
 *
 * <p>这两件事正是设计器存在的意义（管理员自己定的顺序），也是最容易因为「按 id 排」「按插入序排」
 * 而悄悄跑偏的地方 —— 排错了不会报错，只是导出出来的册子章节乱序，
 * 大半天才发现。所以断言直接落在顺序上。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderBookLayoutServiceTest {

    private static final Long MEET_ID = 7L;

    @Mock
    private OrderBookSectionRepository sectionRepository;
    @Mock
    private OrderBookEntryRepository entryRepository;
    @Mock
    private OrderBookEntryService entryService;
    @Mock
    private MeetService meetService;

    @InjectMocks
    private OrderBookLayoutService layoutService;

    private SportsMeet meet;

    @BeforeEach
    void setUp() {
        meet = new SportsMeet();
        meet.setId(MEET_ID);
        meet.setName("第 30 届校运动会");
        meet.setEdition(30);
    }

    private static OrderBookSection section(Long id, int level, int sort, boolean enabled) {
        return OrderBookSection.builder()
                .id(id)
                .parentId(null)
                .title("目录" + id)
                .kind(OrderBookSection.KIND_SYSTEM)
                .level(level)
                .sortOrder(sort)
                .enabled(enabled)
                .build();
    }

    /** build 返回的 sections 列表 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sectionsOf(Object layout) {
        return (List<Map<String, Object>>) ((Map<String, Object>) layout).get("sections");
    }

    @Test
    @DisplayName("目录按「层级 → 排序 → id」输出，不是按数据库返回顺序")
    void orderByLevelThenSort() {
        when(meetService.getActive()).thenReturn(Optional.of(meet));
        when(sectionRepository.findByMeet(MEET_ID)).thenReturn(List.of(
                section(3L, 1, 2, true),   // 本来排第三
                section(1L, 1, 1, true),   // 应该排第一
                section(2L, 2, 1, true))); // 二级目录
        when(entryService.listBySection(anyLong())).thenReturn(List.of());

        Map<String, Object> layout = layoutService.build(null);
        List<Map<String, Object>> sections = sectionsOf(layout);

        assertEquals(3, sections.size());
        assertEquals("目录1", sections.get(0).get("title"));  // 一级 + 排序 1
        assertEquals("目录3", sections.get(1).get("title"));  // 一级 + 排序 2
        assertEquals("目录2", sections.get(2).get("title"));  // 二级，整体靠后
        assertEquals(3L, layout.get("enabledSectionCount"));
    }

    @Test
    @DisplayName("未启用的目录与细则不会被 buildEnabled 带进最终文档")
    void enabledOnly() {
        when(meetService.getActive()).thenReturn(Optional.of(meet));
        when(sectionRepository.findByMeet(MEET_ID)).thenReturn(List.of(
                section(1L, 1, 1, true),
                section(2L, 1, 2, false)));  // 停用
        when(entryService.listBySection(1L)).thenReturn(List.of(
                Map.of("id", 11L, "title", "细则A", "enabled", true),
                Map.of("id", 12L, "title", "细则B", "enabled", false)));
        when(entryService.listBySection(2L)).thenReturn(List.of(
                Map.of("id", 21L, "title", "停用的细则", "enabled", true)));

        Map<String, Object> layout = layoutService.buildEnabled(MEET_ID);
        List<Map<String, Object>> sections = sectionsOf(layout);

        assertEquals(1, sections.size(), "停用的目录不该出现在终稿里");
        assertEquals("目录1", sections.get(0).get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) sections.get(0).get("entries");
        assertEquals(1, entries.size());
        assertEquals("细则A", entries.get(0).get("title"));
    }

    @Test
    @DisplayName("build 里带上届次信息，供封面与页眉使用")
    void carriesMeetInfo() {
        when(meetService.getActive()).thenReturn(Optional.of(meet));
        when(sectionRepository.findByMeet(MEET_ID)).thenReturn(List.of());

        Map<String, Object> layout = layoutService.build(null);
        assertEquals(MEET_ID, layout.get("meetId"));
        assertEquals("第 30 届校运动会", layout.get("meetName"));
        assertEquals(30, ((Number) layout.get("edition")).intValue());
        assertTrue(((List<?>) layout.get("sections")).isEmpty());
    }

    @Test
    @DisplayName("系统板块的启用状态单独查，未启用时渲染侧干脆不取数")
    void sourceEnabled() {
        OrderBookEntry on = OrderBookEntry.builder()
                .id(1L).sourceKey(OrderBookEntry.SRC_SCHEDULE)
                .kind(OrderBookEntry.KIND_SYSTEM)
                .contentType(OrderBookEntry.CONTENT_TABLE)
                .enabled(true).build();
        OrderBookEntry off = OrderBookEntry.builder()
                .id(2L).sourceKey(OrderBookEntry.SRC_ARRANGE)
                .kind(OrderBookEntry.KIND_SYSTEM)
                .contentType(OrderBookEntry.CONTENT_TABLE)
                .enabled(false).build();
        when(entryRepository.findBySource(MEET_ID, OrderBookEntry.SRC_SCHEDULE)).thenReturn(List.of(on));
        when(entryRepository.findBySource(MEET_ID, OrderBookEntry.SRC_ARRANGE)).thenReturn(List.of(off));

        assertTrue(layoutService.isSourceEnabled(MEET_ID, OrderBookEntry.SRC_SCHEDULE));
        assertFalse(layoutService.isSourceEnabled(MEET_ID, OrderBookEntry.SRC_ARRANGE));
        // 没查过的板块与空参数都要返回 false，不能抛
        assertFalse(layoutService.isSourceEnabled(MEET_ID, "NOT_A_SOURCE"));
        assertFalse(layoutService.isSourceEnabled(null, OrderBookEntry.SRC_SCHEDULE));
    }
}
