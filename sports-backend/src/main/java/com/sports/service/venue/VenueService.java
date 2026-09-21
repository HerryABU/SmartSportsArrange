package com.sports.service.venue;

import com.sports.entity.venue.Venue;
import com.sports.repository.venue.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    /** 启用的场地列表（按 sortOrder 升序），供编排引擎与前端下拉使用 */
    public List<Venue> listEnabled() {
        return venueRepository.findByEnabledTrueOrderBySortOrderAsc();
    }

    public List<Venue> listAll() {
        return venueRepository.findAllByOrderBySortOrderAsc();
    }

    public Optional<Venue> findByCode(String code) {
        return venueRepository.findByCodeAndEnabledTrue(code);
    }

    @Transactional
    public Venue create(Venue venue) {
        if (venue.getCode() == null || venue.getCode().isBlank()) {
            throw new IllegalArgumentException("场地编码不能为空");
        }
        if (venueRepository.existsByCode(venue.getCode().trim())) {
            throw new IllegalArgumentException("场地编码已存在: " + venue.getCode().trim());
        }
        Venue v = Venue.builder()
                .code(venue.getCode().trim())
                .name(venue.getName() == null ? venue.getCode().trim() : venue.getName().trim())
                .type(venue.getType() == null || venue.getType().isBlank() ? "other" : venue.getType().trim())
                .capacity(venue.getCapacity() > 0 ? venue.getCapacity() : 1)
                .parallelMax(venue.getParallelMax() > 0 ? venue.getParallelMax() : 1)
                .sortOrder(venue.getSortOrder())
                .enabled(venue.isEnabled())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Venue saved = venueRepository.save(v);
        log.info("创建场地: code={}, name={}, type={}, parallelMax={}", saved.getCode(), saved.getName(), saved.getType(), saved.getParallelMax());
        return saved;
    }

    /** 部分更新：仅覆盖请求中出现的字段 */
    @Transactional
    public Venue update(Long id, Map<String, Object> body) {
        Venue v = venueRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("场地不存在: " + id));
        if (body.containsKey("code")) {
            String code = String.valueOf(body.get("code")).trim();
            if (!code.equals(v.getCode()) && venueRepository.existsByCode(code)) {
                throw new IllegalArgumentException("场地编码已存在: " + code);
            }
            v.setCode(code);
        }
        if (body.containsKey("name")) v.setName(String.valueOf(body.get("name")).trim());
        if (body.containsKey("type")) v.setType(String.valueOf(body.get("type")).trim());
        if (body.containsKey("capacity")) v.setCapacity(intOf(body.get("capacity"), v.getCapacity()));
        if (body.containsKey("parallelMax")) v.setParallelMax(intOf(body.get("parallelMax"), v.getParallelMax()));
        if (body.containsKey("sortOrder")) v.setSortOrder(intOf(body.get("sortOrder"), v.getSortOrder()));
        if (body.containsKey("enabled")) v.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        v.setUpdatedAt(LocalDateTime.now());
        Venue saved = venueRepository.save(v);
        log.info("更新场地: id={}, code={}", id, saved.getCode());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Venue v = venueRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("场地不存在: " + id));
        v.setDeletedAt(LocalDateTime.now());
        venueRepository.save(v);
        log.info("删除(软删)场地: id={}, code={}", id, v.getCode());
    }

    private static int intOf(Object v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return def; }
    }
}
