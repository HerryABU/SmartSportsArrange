package com.sports.service.orderbook;

import com.sports.entity.meet.SportsMeet;
import com.sports.entity.orderbook.OrderBookEntry;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秩序册<b>排版</b>：把「目录 + 细则」按用户定好的顺序拼成一份可直接预览 / 导出 / 打印的树。
 *
 * <p>为什么单独一个服务而不是塞进前面两个： Section 服务管「目录这张表」、Entry 服务管「细则这张表」，
 * 而「谁在谁前面、谁属于谁」是<b>跨域的组合关系</b>，既不属于目录也不属于细则。
 * 放到谁那里都会变成另一个的对偶职责；独立出来后，预览、HTML 渲染、docx 导出三方共用同一份树。</p>
 *
 * <p>这里<b>不</b>生成系统板块（SCHEDULE / ARRANGE 这类）的表格内容 —— 那属于编排数据的读取，
 * 由渲染侧按 {@code sourceKey} 去取（前端已有秩序册数据的接口）。服务层只负责把这版结构说清楚：
 * 顺序是管理员定的，顺序由它决定。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderBookLayoutService {

    private final OrderBookSectionRepository sectionRepository;
    private final OrderBookEntryRepository entryRepository;
    private final OrderBookEntryService entryService;
    private final MeetService meetService;

    /** 组装整本秩序册的目录结构（未启用的东西也带上，设计器要能看见并改回来）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> build(Long meetId) {
        SportsMeet meet = meetService.getActive().orElse(null);
        Long mid = meetId != null ? meetId : (meet == null ? null : meet.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meetId", mid);
        out.put("meetName", meet == null ? null : meet.getName());
        out.put("edition", meet == null ? null : meet.getEdition());

        List<OrderBookSection> sections = new ArrayList<>(sectionRepository.findByMeet(mid));
        sections.sort(Comparator.comparing((OrderBookSection s) -> s.getLevel() == null ? 1 : s.getLevel())
                .thenComparing(s -> s.getSortOrder() == null ? 0 : s.getSortOrder())
                .thenComparing(OrderBookSection::getId));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (OrderBookSection s : sections) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", s.getId());
            node.put("parentId", s.getParentId());
            node.put("title", s.getTitle());
            node.put("level", s.getLevel());
            node.put("kind", s.getKind());
            node.put("enabled", s.getEnabled());
            node.put("entries", entryService.listBySection(s.getId()));
            nodes.add(node);
        }
        out.put("sections", nodes);
        out.put("enabledSectionCount", nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("enabled"))).count());
        return out;
    }

    /** 只取「启用了」的部分，给最终导出/预览用。 */
    @Transactional(readOnly = true)
    public Map<String, Object> buildEnabled(Long meetId) {
        Map<String, Object> full = build(meetId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) full.get("sections");
        List<Map<String, Object>> kept = sections.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("enabled")))
                .peek(s -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> entries = (List<Map<String, Object>>) s.get("entries");
                    s.put("entries", entries.stream()
                            .filter(e -> Boolean.TRUE.equals(e.get("enabled"))).toList());
                })
                .toList();
        full.put("sections", new ArrayList<>(kept));
        return full;
    }

    /**
     * 某个系统板块有没有被启用（渲染侧据此决定要不要画那一块表格）。
     *
     * <p>为什么不在 build() 里就把 enable 状态塞进每条：系统板块的表格数据由渲染侧另外去取，
     * 这里只要回答「这块该不该出现」——返回 false 时前端连请求都不必发。</p>
     */
    @Transactional(readOnly = true)
    public boolean isSourceEnabled(Long meetId, String sourceKey) {
        if (meetId == null || sourceKey == null || sourceKey.isBlank()) {
            return false;
        }
        return entryRepository.findBySource(meetId, sourceKey).stream()
                .anyMatch(e -> Boolean.TRUE.equals(e.getEnabled()));
    }
}
