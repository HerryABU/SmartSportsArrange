package com.sports.service.parade;

import com.sports.entity.meet.SportsMeet;
import com.sports.entity.parade.CustomProject;
import com.sports.entity.parade.ParadeScore;
import com.sports.repository.parade.CustomProjectRepository;
import com.sports.repository.parade.ParadeScoreRepository;
import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自定义项目服务：项目 CRUD + 默认「入场式」项目兜底 + 历史打分记录回填项目归属。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CustomProjectService {

    private final CustomProjectRepository customProjectRepository;
    private final ParadeScoreRepository paradeScoreRepository;
    private final MeetService meetService;

    @Transactional(readOnly = true)
    public List<CustomProject> list() {
        return customProjectRepository.findAllActive();
    }

    @Transactional(readOnly = true)
    public Optional<CustomProject> getByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return customProjectRepository.findByCode(code);
    }

    /** 创建 / 更新一个自定义项目（id 为空则新建） */
    public CustomProject save(Map<String, Object> item) {
        Long id = item.get("id") instanceof Number n ? n.longValue() : null;
        String code = item.get("code") != null ? String.valueOf(item.get("code")).trim() : null;
        String name = item.get("name") != null ? String.valueOf(item.get("name")).trim() : null;
        if (name == null || name.isBlank()) throw new RuntimeException("项目名称不能为空");
        if (code == null || code.isBlank()) code = name; // 未给编码则以名称兜底
        code = code.replaceAll("\\s+", "_");

        SportsMeet meet = meetService.getActiveOrCreateDefault();
        CustomProject existing = (id != null)
                ? customProjectRepository.findById(id).orElse(null)
                : customProjectRepository.findByCodeAndMeet(code, meet.getId()).orElse(null);

        // 同届内编码唯一：新建或改编码时若与他人冲突则拒绝
        if (existing == null && customProjectRepository.findByCodeAndMeet(code, meet.getId()).isPresent()) {
            throw new RuntimeException("项目编码已存在: " + code);
        }

        CustomProject p = existing != null ? existing : CustomProject.builder().meet(meet).build();
        p.setCode(code);
        p.setName(name);
        p.setType(item.get("type") != null ? String.valueOf(item.get("type")).trim() : CustomProject.TYPE_CUSTOM);
        if (item.containsKey("sortOrder") && item.get("sortOrder") instanceof Number s) {
            p.setSortOrder(s.intValue());
        } else if (p.getSortOrder() == null) {
            p.setSortOrder(0);
        }
        p.setCountInTotal(item.get("countInTotal") instanceof Boolean b ? b : Boolean.TRUE);
        p.setUpdatedAt(LocalDateTime.now());
        if (p.getCreatedAt() == null) p.setCreatedAt(LocalDateTime.now());
        CustomProject saved = customProjectRepository.save(p);
        log.info("保存自定义项目: id={}, code={}, name={}", saved.getId(), saved.getCode(), saved.getName());
        return saved;
    }

    /** 软删除一个项目（其打分记录保留，仅标记归属失效不影响历史） */
    public void delete(Long id) {
        CustomProject p = customProjectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("自定义项目不存在: " + id));
        p.setDeletedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        customProjectRepository.save(p);
        log.info("删除自定义项目: id={}, code={}", id, p.getCode());
    }

    /**
     * 启动兜底：若本届无任何自定义项目，则创建默认「入场式」项目（code=parade）。
     * 幂等：已存在则跳过。
     */
    public void ensureDefaultProject() {
        SportsMeet meet = meetService.getActiveOrCreateDefault();
        if (customProjectRepository.findByMeetId(meet.getId()).isEmpty()) {
            CustomProject parade = CustomProject.builder()
                    .meet(meet)
                    .code(CustomProject.DEFAULT_PARADE_CODE)
                    .name(CustomProject.DEFAULT_PARADE_NAME)
                    .type(CustomProject.TYPE_PARADE)
                    .sortOrder(0)
                    .countInTotal(true)
                    .build();
            customProjectRepository.save(parade);
            log.info("[custom-project] 已为本届创建默认项目：入场式(code=parade)");
        }
        backfillParadeProjectOnScores(meet);
    }

    /** 历史入场式得分记录回填项目归属（projectCode/name/type），仅对缺失归属的行补写 */
    private void backfillParadeProjectOnScores(SportsMeet meet) {
        Optional<CustomProject> parade = customProjectRepository.findByCodeAndMeet(
                CustomProject.DEFAULT_PARADE_CODE, meet.getId());
        if (parade.isEmpty()) return;
        CustomProject p = parade.get();
        List<ParadeScore> all = paradeScoreRepository.findByMeetId(meet.getId());
        int n = 0;
        for (ParadeScore ps : all) {
            if (ps.getProjectCode() == null || ps.getProjectCode().isBlank()) {
                ps.setProjectCode(p.getCode());
                ps.setProjectName(p.getName());
                ps.setType(p.getType());
                paradeScoreRepository.save(ps);
                n++;
            }
        }
        if (n > 0) log.info("[custom-project] 已为 {} 条历史入场式得分回填项目归属(code=parade)", n);
    }
}
