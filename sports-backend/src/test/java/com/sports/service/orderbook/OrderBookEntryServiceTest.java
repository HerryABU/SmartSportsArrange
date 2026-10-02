package com.sports.service.orderbook;

import com.sports.entity.orderbook.OrderBookEntry;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秩序册细则服务的单测：落位排序、数据源白名单、上下移边界。
 *
 * <p>重点在「排序」和「越界」两件事：细则永远是同目录内排序，
 * 上下移点到头时必须是<b>安静地不动</b>，而不是把别人的顺序打乱或抛异常；
 * 数据源 {@code sourceKey} 必须白名单校验，否则前端渲染层会撞上没实现的分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderBookEntryServiceTest {

    @Mock
    private OrderBookEntryRepository entryRepository;
    @Mock
    private OrderBookSectionRepository sectionRepository;

    @InjectMocks
    private OrderBookEntryService entryService;

    private static OrderBookSection section(Long id) {
        return OrderBookSection.builder()
                .id(id)
                .title("目录" + id)
                .level(1)
                .kind(OrderBookSection.KIND_CUSTOM)
                .enabled(true)
                .build();
    }

    private static OrderBookEntry entry(Long id, Long sectionId, int sort, boolean enabled) {
        return OrderBookEntry.builder()
                .id(id)
                .section(section(sectionId))
                .title("细则" + id)
                .kind(OrderBookEntry.KIND_CUSTOM)
                .contentType(OrderBookEntry.CONTENT_TEXT)
                .sortOrder(sort)
                .enabled(enabled)
                .build();
    }

    /** mock 的 save 默认返回 null，得让它把入参原样吐回来，才能断言服务层加工后的结果。 */
    private void stubSaveReturningArgument() {
        when(entryRepository.save(org.mockito.ArgumentMatchers.any(OrderBookEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("新增落位到同目录末尾（maxSortOrder + 1）")
    void createAppendsToEnd() {
        when(sectionRepository.findById(1L)).thenReturn(Optional.of(section(1L)));
        when(entryRepository.maxSortOrder(1L)).thenReturn(4);
        stubSaveReturningArgument();

        entryService.create(1L, "竞赛须知", "CUSTOM", null, "TEXT", "请各班级于 8:00 前入场");

        ArgumentCaptor<OrderBookEntry> captor = ArgumentCaptor.forClass(OrderBookEntry.class);
        verify(entryRepository).save(captor.capture());
        OrderBookEntry saved = captor.getValue();
        assertEquals(5, saved.getSortOrder());
        assertEquals("竞赛须知", saved.getTitle());
        assertEquals(OrderBookEntry.CONTENT_TEXT, saved.getContentType());
        assertTrue(saved.getEnabled());
    }

    @Test
    @DisplayName("系统板块的 sourceKey 走白名单，认不出的当成自定义（不能塞给渲染层）")
    void sourceWhitelist() {
        when(sectionRepository.findById(1L)).thenReturn(Optional.of(section(1L)));
        when(entryRepository.maxSortOrder(1L)).thenReturn(0);
        stubSaveReturningArgument();

        entryService.create(1L, "日程", "SYSTEM", "schedule", "TABLE", null);
        entryService.create(1L, "乱填", "SYSTEM", "NOT_A_SOURCE", "TABLE", null);

        ArgumentCaptor<OrderBookEntry> captor = ArgumentCaptor.forClass(OrderBookEntry.class);
        verify(entryRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(OrderBookEntry.SRC_SCHEDULE, captor.getAllValues().get(0).getSourceKey());
        assertNull(captor.getAllValues().get(1).getSourceKey(), "非法 sourceKey 必须被吃掉");
    }

    @Test
    @DisplayName("小写 sourceKey 会被规整成大写")
    void sourceKeyNormalized() {
        when(sectionRepository.findById(1L)).thenReturn(Optional.of(section(1L)));
        when(entryRepository.maxSortOrder(1L)).thenReturn(0);
        stubSaveReturningArgument();

        entryService.create(1L, "号码", "SYSTEM", "numbers", "TABLE", null);

        ArgumentCaptor<OrderBookEntry> captor = ArgumentCaptor.forClass(OrderBookEntry.class);
        verify(entryRepository).save(captor.capture());
        assertEquals(OrderBookEntry.SRC_NUMBERS, captor.getValue().getSourceKey());
    }

    @Test
    @DisplayName("上移/下移：到头到尾都安静不动，且不改别人的顺序")
    void moveAtBoundary() {
        when(entryRepository.findById(1L)).thenReturn(Optional.of(entry(1L, 9L, 0, true)));
        when(entryRepository.findById(3L)).thenReturn(Optional.of(entry(3L, 9L, 2, true)));
        when(entryRepository.findBySection(9L)).thenReturn(List.of(
                entry(1L, 9L, 0, true),
                entry(2L, 9L, 1, true),
                entry(3L, 9L, 2, true)));

        entryService.move(1L, -1); // 已经是第一条
        entryService.move(3L, 1);  // 已经是最后一条

        // 两个方向都点到头了：既不抛异常，也不该有任何写入
        verify(entryRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(entryRepository, never()).save(org.mockito.ArgumentMatchers.any(OrderBookEntry.class));
    }

    @Test
    @DisplayName("同目录内上下移会互换排序，且只动这两条")
    void moveSwapsWithinSection() {
        when(entryRepository.findById(2L)).thenReturn(Optional.of(entry(2L, 9L, 1, true)));
        when(entryRepository.findBySection(9L)).thenReturn(List.of(
                entry(1L, 9L, 0, true),
                entry(2L, 9L, 1, true),
                entry(3L, 9L, 2, true)));

        entryService.move(2L, -1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderBookEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(captor.capture());
        List<OrderBookEntry> saved = captor.getValue();
        assertEquals(2, saved.size(), "只应改这两条，别的一动不动");
        // 一个是原来的 1 号（现在 0），一个是原来的 0 号（现在 1）
        List<Integer> sorts = saved.stream().map(OrderBookEntry::getSortOrder).sorted().toList();
        assertEquals(List.of(0, 1), sorts);
    }

    @Test
    @DisplayName("目录不存在时如实报错，而不是悄悄造一条")
    void missingSectionThrows() {
        when(sectionRepository.findById(42L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> entryService.create(42L, "细则", "CUSTOM", null, "TEXT", "x"));
    }

    @Test
    @DisplayName("删除是软删，恢复只是清空删除时间")
    void deleteAndRestore() {
        OrderBookEntry e = entry(5L, 9L, 0, true);
        when(entryRepository.findById(5L)).thenReturn(Optional.of(e));
        entryService.delete(5L);
        // 软删不是 @Modifying 批量 UPDATE（类级 @SQLRestriction 会污染 DML），而是改字段后按主键 save
        verify(entryRepository).save(e);
        assertNotNull(e.getDeletedAt(), "删除时间要打上，否则 @SQLRestriction 过滤不掉这一行");

        e.setDeletedAt(null);
        when(entryRepository.restore(5L)).thenReturn(1);
        entryService.restore(5L);
        // 恢复是原生 UPDATE（@SQLRestriction 会挡住 findById），不再走 save
        verify(entryRepository).restore(5L);
    }

    @Test
    @DisplayName("恢复时找不到那一行就报不存在，不静默吞掉")
    void restoreMissing() {
        when(entryRepository.restore(404L)).thenReturn(0);
        assertThrows(RuntimeException.class, () -> entryService.restore(404L));
    }

    @Test
    @DisplayName("删目录时整目录细则被收口（软删），不带回别的目录")
    void softDeleteBySection() {
        OrderBookEntry a = entry(1L, 7L, 0, true);
        OrderBookEntry b = entry(2L, 7L, 1, true);
        OrderBookEntry c = entry(3L, 8L, 0, true);
        when(entryRepository.findBySectionId(7L)).thenReturn(List.of(a, b));

        int n = entryService.softDeleteBySection(7L);
        assertEquals(2, n, "返回被收口的条数");
        verify(entryRepository).saveAll(List.of(a, b));
        assertNotNull(a.getDeletedAt());
        assertNotNull(b.getDeletedAt());
    }
}
