package com.sports.service.orderbook;

import com.sports.entity.meet.SportsMeet;
import com.sports.entity.orderbook.OrderBookSection;
import com.sports.repository.orderbook.OrderBookEntryRepository;
import com.sports.repository.orderbook.OrderBookSectionRepository;
import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 秩序册「目录」的服务：拉默认章节、增改删（软删）、上下移、启停。
 *
 * <p>目录是秩序册的骨架，管理员在设计器里 citrus 一手「新增目录 → 改名 → 调顺序」，
 * 因此这里对外提供的是<b>扁平且带层级信息</b>的列表（前端自己按 {@code parentId} 渲染树），
 * 而不是拼好的嵌套树 —— 拼树属于展示层的事，服务层不该替前端决定长什么样。</p>
 *
 * <h3>删除一律软删</h3>
 * <p>删掉一个目录是个危险动作（同时要把它的细则一起处理掉），软删之后还能恢复，
 * 管理员误操作不至于把一本已经排好的秩序册打回原形。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookSectionService {

    private final OrderBookSectionRepository sectionRepository;
    private final OrderBookEntryRepository entryRepository;
    private final OrderBookEntryService entryService;
    private final MeetService meetService;

    // ==================== 默认章节 ====================

    /** 首次打开某届秩序册时铺好的一级目录（顺序即册子里的先后）。 */
    private static final List<String> DEFAULT_SECTIONS = List.of(
            "一、竞赛日程",
            "二、竞赛项目设置",
            "三、参赛单位",
            "四、分组与道次编排",
            "五、运动员号码对照表");

    /**
     * 确保该届有一套默认目录；已有（哪怕全被软删）就不重复铺，避免二次打开长出第二套。
     */
    @Transactional
    public List<Map<String, Object>> ensureDefaults(Long meetId) {
        SportsMeet meet = resolveMeet(meetId);
        if (sectionRepository.countByMeet(meet.getId()) > 0) {
            return listTree(meet.getId());
        }
        int base = 0;
        for (String title : DEFAULT_SECTIONS) {
            OrderBookSection s = OrderBookSection.builder()
                    .meet(meet)
                    .parentId(null)
                    .title(title)
                    .kind(OrderBookSection.KIND_SYSTEM)
                    .level(OrderBookSection.LEVEL_TOP)
                    .sortOrder(base++)
                    .enabled(Boolean.TRUE)
                    .build();
            sectionRepository.save(s);
        }
        log.info("秩序册目录：为第 {} 届铺了 {} 个默认章节", meet.getEdition(), DEFAULT_SECTIONS.size());
        return listTree(meet.getId());
    }

    // ==================== 查询 ====================

    /** 目录清单（扁平，前端自己拼树）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTree(Long meetId) {
        Long mid = meetId != null ? meetId : activeMeetId();
        if (mid == null) {
            return List.of();
        }
        List<OrderBookSection> all = new ArrayList<>(sectionRepository.findByMeet(mid));
        all.sort(Comparator.comparing((OrderBookSection s) -> s.getLevel())
                .thenComparing(s -> s.getSortOrder() == null ? 0 : s.getSortOrder())
                .thenComparing(OrderBookSection::getId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (OrderBookSection s : all) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("meetId", s.getMeetId());
            m.put("parentId", s.getParentId());
            m.put("title", s.getTitle());
            m.put("kind", s.getKind());
            m.put("level", s.getLevel());
            m.put("sortOrder", s.getSortOrder());
            m.put("enabled", s.getEnabled());
            m.put("entryCount", entryRepository.countBySection(s.getId()));
            out.add(m);
        }
        return out;
    }

    // ==================== 写入 ====================

    /** 新增目录（可选挂到某个父目录下成为二级）。 */
    @Transactional
    public Map<String, Object> create(Long meetId, String parentId, String title, Integer level) {
        SportsMeet meet = resolveMeet(meetId);
        String p = (parentId == null || parentId.isBlank()) ? null : parentId.trim();
        // 用户新增的一律 CUSTOM：SYSTEM 那五个默认章节只在 ensureDefaults 里铺一次，
        // 管理员改了名字也还是它们（改名保留 kind），不会跟自定义目录混在一起。
        int sort = sectionRepository.maxSortOrder(meet.getId(), p) + 1;
        OrderBookSection s = OrderBookSection.builder()
                .meet(meet)
                .parentId(p)
                .title(title == null || title.isBlank() ? "新建目录" : title.trim())
                .kind(OrderBookSection.KIND_CUSTOM)
                .level(p == null ? OrderBookSection.LEVEL_TOP : OrderBookSection.LEVEL_SUB)
                .sortOrder(sort)
                .enabled(Boolean.TRUE)
                .build();
        // level 由调用方给定时以调用方为准（设计器可能自己维护层级）
        if (level != null) {
            s.setLevel(level);
        }
        sectionRepository.save(s);
        return sectionToMap(s);
    }

    /** 改名 / 启停 / 换父级。 */
    @Transactional
    public Map<String, Object> update(Long id, String title, String parentId, Boolean enabled, Integer level) {
        OrderBookSection s = sectionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("目录不存在或已删除"));
        if (title != null && !title.isBlank()) {
            s.setTitle(title.trim());
        }
        if (parentId != null) {
            s.setParentId(parentId.trim().isEmpty() ? null : parentId.trim());
        }
        if (enabled != null) {
            s.setEnabled(enabled);
        }
        if (level != null) {
            s.setLevel(level);
        }
        sectionRepository.save(s);
        return sectionToMap(s);
    }

    /**
     * 上下移（同一父级内交换）。
     *
     * @param dir -1 上移 / 1 下移
     */
    @Transactional
    public void move(Long meetId, Long id, int dir) {
        SportsMeet meet = resolveMeet(meetId);
        OrderBookSection me = sectionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("目录不存在或已删除"));
        // 兄弟 = 同届同父，按排序取；parentId 为 null 的一级目录之间互调
        List<OrderBookSection> siblings = new ArrayList<>(sectionRepository.findByMeet(meet.getId()));
        siblings.removeIf(s -> !java.util.Objects.equals(s.getParentId(), me.getParentId()));
        siblings.sort(Comparator.comparing((OrderBookSection s) -> s.getSortOrder() == null ? 0 : s.getSortOrder())
                .thenComparing(OrderBookSection::getId));
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(id)) {
                idx = i;
                break;
            }
        }
        int target = idx + (dir < 0 ? -1 : 1);
        if (idx < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        OrderBookSection other = siblings.get(target);
        int tmp = siblings.get(idx).getSortOrder() == null ? 0 : siblings.get(idx).getSortOrder();
        siblings.get(idx).setSortOrder(other.getSortOrder() == null ? 0 : other.getSortOrder());
        other.setSortOrder(tmp);
        sectionRepository.saveAll(List.of(siblings.get(idx), other));
    }

    /**
     * 删除目录：软删自己，并把它名下的细则一并软删（规则是「目录没了，细则也没了」，
     * 不做提升——把细则提到上级目录会破坏管理员对结构的预期）。
     */
    /**
     * 删除目录：软删自己，并把它名下的细则一并软删。
     *
     * <p>规则是「目录没了，细则也没了」，不做提升到上级 —— 把细则提到上级会破坏管理员对结构的预期。
     * 软删同样走「查出来改字段再 save」而不是 {@code @Modifying} 批量 UPDATE：
     * 类级 {@code @SQLRestriction} 会拼进 DML，SQLite 上直接报 {@code no such column}。</p>
     */
    @Transactional
    public void delete(Long id) {
        OrderBookSection s = sectionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("目录不存在或已删除"));
        int entries = entryService.softDeleteBySection(id);
        s.setDeletedAt(LocalDateTime.now());
        sectionRepository.save(s);
        log.info("秩序册目录已软删：id={} title={} 连带细则={} 条", s.getId(), s.getTitle(), entries);
    }

    /**
     * 恢复（软删误操作时救命）。
     *
     * <p>同 {@code OrderBookEntryService#restore}：走原生 UPDATE 绕开 {@code @SQLRestriction}
     * 对 {@code findById} 的过滤，否则已软删的目录永远查不到、恢复无从谈起。</p>
     */
    @Transactional
    public void restore(Long id) {
        if (sectionRepository.restore(id) == 0) {
            throw new RuntimeException("目录不存在");
        }
        log.info("秩序册目录已恢复：id={}", id);
    }

    // ==================== 内部 ====================

    /**
     * 写入场景取届：带届次就用它，没带就用当前进行中的届；
     * 一届都没有时按需造一届默认（否则管理员连第一个目录都建不出来，只能面对一个空页面）。
     */
    private SportsMeet resolveMeet(Long meetId) {
        return meetService.getActiveOrCreateDefault();
    }

    /** 当前进行中的届；实在没有就返回 null（而不是凭空造一届），让调用方如实提示。 */
    private Long activeMeetId() {
        return meetService.getActive().map(SportsMeet::getId).orElse(null);
    }

    private Map<String, Object> sectionToMap(OrderBookSection s) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("meetId", s.getMeetId());
        m.put("parentId", s.getParentId());
        m.put("title", s.getTitle());
        m.put("kind", s.getKind());
        m.put("level", s.getLevel());
        m.put("sortOrder", s.getSortOrder());
        m.put("enabled", s.getEnabled());
        m.put("entryCount", entryRepository.countBySection(s.getId()));
        return m;
    }
}
