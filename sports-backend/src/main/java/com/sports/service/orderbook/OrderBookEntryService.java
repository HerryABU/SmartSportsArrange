package com.sports.service.orderbook;

import com.sports.entity.orderbook.OrderBookEntry;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秩序册「细则」的服务：一个目录下的具体条目（系统数据表格 / 管理员写的介绍文字）。
 *
 * <p>和 {@link OrderBookSectionService} 一样只负责持久化与排序，不负责把 {@code content} 渲染成什么样子 ——
 * 渲染是 {@link OrderBookLayoutService} 和前端的事。这块要紧的是<b>排序口径</b>：
 * 细则永远在<b>同一目录内</b>排序，跨目录排序会让管理员「拖上一条结果跳到别的章节」，非常难查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookEntryService {

    private final OrderBookEntryRepository entryRepository;
    private final OrderBookSectionRepository sectionRepository;

    /** 某目录下的细则清单（扁平）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBySection(Long sectionId) {
        List<OrderBookEntry> all = new ArrayList<>(entryRepository.findBySection(sectionId));
        all.sort(Comparator.comparing((OrderBookEntry e) -> e.getSortOrder() == null ? 0 : e.getSortOrder())
                .thenComparing(OrderBookEntry::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrderBookEntry e : all) {
            out.add(entryToMap(e));
        }
        return out;
    }

    /**
     * 新增细则。
     *
     * @param kind        CUSTOM（管理员写的介绍内容）/ SYSTEM（系统数据板块）
     * @param sourceKey   系统板块的数据源（SCHEDULE / EVENTS / CLASSES / ARRANGE / NUMBERS）
     * @param contentType TEXT / TABLE
     */
    @Transactional
    public Map<String, Object> create(Long sectionId, String title, String kind,
                                      String sourceKey, String contentType, String content) {
        OrderBookSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new RuntimeException("目录不存在或已删除"));
        OrderBookEntry e = OrderBookEntry.builder()
                .section(section)
                .title(title == null || title.isBlank() ? null : title.trim())
                .kind(kind == null || kind.isBlank() ? OrderBookEntry.KIND_CUSTOM : kind.trim())
                .sourceKey(normalizeSource(sourceKey))
                .contentType(contentType == null || contentType.isBlank()
                        ? OrderBookEntry.CONTENT_TEXT : contentType.trim())
                .content(content)
                .sortOrder(entryRepository.maxSortOrder(sectionId) + 1)
                .enabled(Boolean.TRUE)
                .build();
        return entryToMap(entryRepository.save(e));
    }

    /** 改标题 / 正文 / 启停。 */
    @Transactional
    public Map<String, Object> update(Long id, String title, String content, Boolean enabled) {
        OrderBookEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("细则不存在或已删除"));
        if (title != null) {
            e.setTitle(title.trim().isEmpty() ? null : title.trim());
        }
        if (content != null) {
            e.setContent(content);
        }
        if (enabled != null) {
            e.setEnabled(enabled);
        }
        return entryToMap(entryRepository.save(e));
    }

    /** 同目录内上下移。 */
    @Transactional
    public void move(Long id, int dir) {
        OrderBookEntry me = entryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("细则不存在或已删除"));
        Long sectionId = me.getSectionId();
        List<OrderBookEntry> siblings = new ArrayList<>(entryRepository.findBySection(sectionId));
        siblings.sort(Comparator.comparing((OrderBookEntry e) -> e.getSortOrder() == null ? 0 : e.getSortOrder())
                .thenComparing(OrderBookEntry::getId));
        int idx = indexOf(siblings, id);
        int target = idx + (dir < 0 ? -1 : 1);
        if (idx < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        OrderBookEntry other = siblings.get(target);
        int tmp = siblings.get(idx).getSortOrder() == null ? 0 : siblings.get(idx).getSortOrder();
        siblings.get(idx).setSortOrder(other.getSortOrder() == null ? 0 : other.getSortOrder());
        other.setSortOrder(tmp);
        entryRepository.saveAll(List.of(siblings.get(idx), other));
    }

    /** 删除（软删，可恢复）。 */
    @Transactional
    public void delete(Long id) {
        OrderBookEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("细则不存在或已删除"));
        entryRepository.softDelete(id);
        log.info("秩序册细则已软删：id={} section={}", e.getId(), e.getSectionId());
    }

    /** 恢复。 */
    @Transactional
    public void restore(Long id) {
        OrderBookEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("细则不存在"));
        e.setDeletedAt(null);
        entryRepository.save(e);
    }

    // ==================== 内部 ====================

    private static int indexOf(List<OrderBookEntry> list, Long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 只认这几类系统数据源，别的（包括乱填的）一律按自定义处理，避免渲染层踩到未实现的分支。 */
    private static String normalizeSource(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return null;
        }
        String k = sourceKey.trim().toUpperCase();
        return switch (k) {
            case OrderBookEntry.SRC_SCHEDULE, OrderBookEntry.SRC_EVENTS, OrderBookEntry.SRC_CLASSES,
                    OrderBookEntry.SRC_ARRANGE, OrderBookEntry.SRC_NUMBERS -> k;
            default -> null;
        };
    }

    private Map<String, Object> entryToMap(OrderBookEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sectionId", e.getSectionId());
        m.put("title", e.getTitle());
        m.put("kind", e.getKind());
        m.put("sourceKey", e.getSourceKey());
        m.put("contentType", e.getContentType());
        m.put("content", e.getContent());
        m.put("sortOrder", e.getSortOrder());
        m.put("enabled", e.getEnabled());
        return m;
    }
}
