package com.sports.service.meet;

import com.sports.entity.meet.SportsMeet;
import com.sports.repository.meet.SportsMeetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 届 / 运动会管理（数据库一等公民）。
 *
 * <p>负责届的 CRUD、当前届切换（全校同时仅一个 active），以及「默认届」保障：
 * 历史库没有届概念，首次启动必须把所有存量数据归入一个默认届，并令其 active，
 * 后续录成绩 / 报名时默认归属当前届。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetService {

    private final SportsMeetRepository meetRepository;

    /** 默认届季节（自由文本，用户可改） */
    private static final String DEFAULT_SEASON = "秋季";

    /** 列表（最新在前） */
    @Transactional(readOnly = true)
    public List<SportsMeet> list() {
        return meetRepository.findAllOrdered();
    }

    /** 当前届；无则返回空 */
    @Transactional(readOnly = true)
    public Optional<SportsMeet> getActive() {
        return meetRepository.findByActiveTrue();
    }

    /** 取当前届；若没有 active 届，则取最新一届并置为 active；若全库无届，则创建默认届 */
    @Transactional
    public SportsMeet getActiveOrCreateDefault() {
        Optional<SportsMeet> active = meetRepository.findByActiveTrue();
        if (active.isPresent()) return active.get();
        List<SportsMeet> all = meetRepository.findAllOrdered();
        if (!all.isEmpty()) {
            SportsMeet latest = all.get(0);
            latest.setActive(true);
            SportsMeet saved = meetRepository.save(latest);
            log.info("[meet] 无 active 届，已将最新一届 {} 置为当前届", saved.getName());
            return saved;
        }
        return createDefault();
    }

    /** 首次启动保障：若全库无届，建一个默认届并 active */
    @Transactional
    public SportsMeet ensureDefaultMeet() {
        if (meetRepository.count() > 0) {
            return getActiveOrCreateDefault();
        }
        return createDefault();
    }

    /** 新建默认届：第 1 届 · 秋季 · 本年 */
    @Transactional
    public SportsMeet createDefault() {
        int year = LocalDate.now().getYear();
        SportsMeet m = SportsMeet.builder()
                .edition(1)
                .season(DEFAULT_SEASON)
                .year(year)
                .name(SportsMeet.composeName(1, DEFAULT_SEASON))
                .location("学校田径场")
                .active(true)
                .build();
        SportsMeet saved = meetRepository.save(m);
        log.info("[meet] 已创建默认届：{}（year={}）", saved.getName(), year);
        return saved;
    }

    /** 新建届 */
    @Transactional
    public SportsMeet create(Map<String, Object> body) {
        SportsMeet m = toEntity(body, null);
        if (Boolean.TRUE.equals(m.getActive())) {
            clearActiveExcept(null);
        } else if (meetRepository.countByActiveTrue() == 0) {
            m.setActive(true);   // 首个届自动成为当前届
        }
        return meetRepository.save(m);
    }

    /** 编辑届 */
    @Transactional
    public SportsMeet update(Long id, Map<String, Object> body) {
        SportsMeet existing = meetRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("届不存在: " + id));
        SportsMeet m = toEntity(body, existing);
        if (Boolean.TRUE.equals(m.getActive())) {
            clearActiveExcept(id);
        }
        return meetRepository.save(m);
    }

    /** 删除届；若删除的是当前届，则把剩下最新一届置为当前届 */
    @Transactional
    public void delete(Long id) {
        SportsMeet m = meetRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("届不存在: " + id));
        boolean wasActive = Boolean.TRUE.equals(m.getActive());
        meetRepository.delete(m);
        if (wasActive) {
            List<SportsMeet> remaining = meetRepository.findAllOrdered();
            if (!remaining.isEmpty()) {
                SportsMeet next = remaining.get(0);
                next.setActive(true);
                meetRepository.save(next);
                log.info("[meet] 已删除当前届，将 {} 置为新当前届", next.getName());
            }
        }
    }

    /** 设为当前届（全校同时仅一个 active） */
    @Transactional
    public SportsMeet setActive(Long id) {
        SportsMeet m = meetRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("届不存在: " + id));
        clearActiveExcept(id);
        m.setActive(true);
        return meetRepository.save(m);
    }

    // ==================== 内部 ====================

    private void clearActiveExcept(Long keepId) {
        for (SportsMeet m : meetRepository.findAll()) {
            if (keepId != null && m.getId().equals(keepId)) continue;
            if (Boolean.TRUE.equals(m.getActive())) {
                m.setActive(false);
                meetRepository.save(m);
            }
        }
    }

    private SportsMeet toEntity(Map<String, Object> body, SportsMeet existing) {
        SportsMeet m = existing == null ? new SportsMeet() : existing;
        if (body.containsKey("edition")) m.setEdition(intOf(body.get("edition"), m.getEdition()));
        if (body.containsKey("season")) {
            String s = strOf(body.get("season"), null);
            m.setSeason(s == null || s.isBlank() ? (m.getSeason() == null ? DEFAULT_SEASON : m.getSeason()) : s.trim());
        }
        if (body.containsKey("year")) m.setYear(intOf(body.get("year"), m.getYear()));
        if (body.containsKey("location")) m.setLocation(strOf(body.get("location"), m.getLocation()));
        if (body.containsKey("startDate")) m.setStartDate(dateOf(body.get("startDate"), m.getStartDate()));
        if (body.containsKey("endDate")) m.setEndDate(dateOf(body.get("endDate"), m.getEndDate()));
        if (body.containsKey("remark")) m.setRemark(strOf(body.get("remark"), m.getRemark()));
        if (body.containsKey("active")) m.setActive(boolOf(body.get("active"), m.getActive()));

        Integer edition = m.getEdition();
        String season = m.getSeason();
        if (edition != null && season != null) {
            m.setName(SportsMeet.composeName(edition, season));
        }
        return m;
    }

    private static int intOf(Object v, Integer def) {
        if (v == null) return def == null ? 0 : def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return def == null ? 0 : def; }
    }

    private static String strOf(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static boolean boolOf(Object v, Boolean def) {
        if (v == null) return def != null && def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static LocalDate dateOf(Object v, LocalDate def) {
        if (v == null) return def;
        try { return LocalDate.parse(String.valueOf(v).trim().substring(0, 10)); } catch (Exception e) { return def; }
    }
}
