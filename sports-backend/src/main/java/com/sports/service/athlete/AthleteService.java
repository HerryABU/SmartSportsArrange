package com.sports.service.athlete;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.arrange.ArrangementRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import com.sports.service.excel.ExcelService;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AthleteService {

    private final AthleteRepository athleteRepository;
    private final ClassInfoRepository classInfoRepository;
    private final ExcelService excelService;
    private final NumberRuleService numberRuleService;
    private final ResultRepository resultRepository;
    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;

    /** 分页查询 */
    @Transactional(readOnly = true)
    public Page<Athlete> list(Pageable pageable, String grade, Long classId, String gender, String keyword) {
        Page<Athlete> result = athleteRepository.findAll(buildSpec(grade, classId, gender, keyword), pageable);
        // 预加载 classInfo，避免序列化时懒加载导致班级信息为 null
        result.getContent().forEach(a -> {
            if (a.getClassInfo() != null) {
                try { a.getClassInfo().getName(); } catch (Exception ignored) {}
            }
        });
        return result;
    }

    /** 按筛选条件返回全部匹配 id（供「全选筛选结果」批量删除） */
    @Transactional(readOnly = true)
    public List<Long> findIdsByFilter(String grade, Long classId, String gender, String keyword) {
        return athleteRepository.findAll(buildSpec(grade, classId, gender, keyword)).stream()
                .map(Athlete::getId)
                .toList();
    }

    /** 统一的筛选条件（年级/班级/性别/关键词），分页查询与全选删除复用 */
    private Specification<Athlete> buildSpec(String grade, Long classId, String gender, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (grade != null && !grade.isBlank())
                predicates.add(cb.equal(root.get("grade"), grade));
            if (classId != null)
                predicates.add(cb.equal(root.get("classInfo").get("id"), classId));
            if (gender != null && !gender.isBlank()) {
                // 兼容「男/女」与「M/F」两种存储口径
                if ("男".equals(gender) || "M".equalsIgnoreCase(gender)) {
                    predicates.add(cb.or(cb.equal(root.get("gender"), "男"), cb.equal(root.get("gender"), "M")));
                } else if ("女".equals(gender) || "F".equalsIgnoreCase(gender)) {
                    predicates.add(cb.or(cb.equal(root.get("gender"), "女"), cb.equal(root.get("gender"), "F")));
                } else {
                    predicates.add(cb.equal(root.get("gender"), gender));
                }
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword + "%";
                predicates.add(cb.or(
                        cb.like(root.get("name"), pattern),
                        cb.like(root.get("number"), pattern),
                        cb.like(root.get("studentId"), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public Athlete getById(Long id) {
        return athleteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("运动员不存在: " + id));
    }

    public Athlete create(Athlete athlete) {
        if (athlete.getClassInfo() != null && athlete.getClassInfo().getId() != null) {
            classInfoRepository.findById(athlete.getClassInfo().getId())
                    .orElseThrow(() -> new IllegalArgumentException("班级不存在"));
        }
        if (athlete.getStudentId() != null && !athlete.getStudentId().isBlank()) {
            if (athleteRepository.findByStudentId(athlete.getStudentId()).isPresent())
                throw new IllegalArgumentException("学号已存在: " + athlete.getStudentId());
        }
        if (athlete.getNumber() != null && !athlete.getNumber().isBlank()) {
            if (athleteRepository.findByNumber(athlete.getNumber()).isPresent())
                throw new IllegalArgumentException("号码已存在: " + athlete.getNumber());
        }
        athlete.setCreatedAt(LocalDateTime.now());
        athlete.setUpdatedAt(LocalDateTime.now());
        if (athlete.getStatus() == null) athlete.setStatus("normal");
        return athleteRepository.save(athlete);
    }

    public Athlete update(Long id, Athlete updated) {
        Athlete existing = getById(id);
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getGender() != null) existing.setGender(updated.getGender());
        if (updated.getGrade() != null) existing.setGrade(updated.getGrade());
        if (updated.getClassInfo() != null) existing.setClassInfo(updated.getClassInfo());
        if (updated.getStudentId() != null) existing.setStudentId(updated.getStudentId());
        if (updated.getIdCard() != null) existing.setIdCard(updated.getIdCard());
        if (updated.getBirthDate() != null) existing.setBirthDate(updated.getBirthDate());
        if (updated.getEmergencyContact() != null) existing.setEmergencyContact(updated.getEmergencyContact());
        if (updated.getEmergencyPhone() != null) existing.setEmergencyPhone(updated.getEmergencyPhone());
        if (updated.getHealthStatus() != null) existing.setHealthStatus(updated.getHealthStatus());
        if (updated.getPhoto() != null) existing.setPhoto(updated.getPhoto());
        if (updated.getStatus() != null) existing.setStatus(updated.getStatus());
        if (updated.getRemark() != null) existing.setRemark(updated.getRemark());
        existing.setUpdatedAt(LocalDateTime.now());
        return athleteRepository.save(existing);
    }

    public void delete(Long id) {
        Athlete athlete = getById(id);
        athlete.setDeletedAt(LocalDateTime.now());
        athleteRepository.save(athlete);
        log.info("删除运动员: {}", athlete.getName());
    }

    /** 导入 */
    public Map<String, Object> importAthletes(MultipartFile file) {
        log.info("从Excel导入运动员: {}", file.getOriginalFilename());
        return excelService.importAthletes(file);
    }

    /** 导出 */
    public void export(HttpServletResponse response) throws IOException {
        excelService.exportAthletes(response);
    }

    /** 批量生成号码簿（按自定义号码簿规则） */
    public int batchGenerateNumbers(String grade, Long classId) {
        List<Athlete> athletes;
        if (classId != null) athletes = athleteRepository.findByClassIdAndGrade(classId, grade);
        else if (grade != null) athletes = athleteRepository.findByGrade(grade);
        else athletes = athleteRepository.findAll();

        Map<String, Object> rule = numberRuleService.getNumberRule();
        int generated = 0;
        for (Athlete a : athletes) {
            if (a.getNumber() != null && !a.getNumber().isBlank()) continue;
            int seq = 1;
            String number;
            do {
                number = numberRuleService.generateNumber(rule, a, a.getClassInfo(), seq++);
            } while (athleteRepository.findByNumber(number).isPresent());
            a.setNumber(number);
            a.setUpdatedAt(LocalDateTime.now());
            athleteRepository.save(a);
            generated++;
        }
        log.info("批量生成号码: grade={}, classId={}, 生成{}个", grade, classId, generated);
        return generated;
    }

    /** 下载模板 */
    public void downloadTemplate(HttpServletResponse response) {
        excelService.getTemplate("athlete", response);
    }

    /**
     * 批量删除（软删除）+ 条件约束：
     * 被成绩(Result)/报名(Registration)/编排(Arrangement) 引用的运动员**跳过并报告原因**，不破坏历史数据。
     * 返回 { total, success, skipped, deleted, errors }。
     */
    public Map<String, Object> batchDelete(List<Long> ids) {
        // 汇总被各业务引用的运动员 id
        Set<Long> resultAthletes = resultRepository.findValidByAthleteIdIn(ids).stream()
                .map(r -> r.getAthlete().getId()).collect(Collectors.toSet());
        Set<Long> regAthletes = registrationRepository.findActiveByAthleteIdIn(ids).stream()
                .map(r -> r.getAthlete().getId()).collect(Collectors.toSet());
        Set<Long> arrAthletes = new HashSet<>(arrangementRepository.findAthleteIdsIn(ids));

        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Long id : ids) {
            try {
                List<String> reasons = new ArrayList<>();
                if (resultAthletes.contains(id)) reasons.add("成绩");
                if (regAthletes.contains(id)) reasons.add("报名");
                if (arrAthletes.contains(id)) reasons.add("编排");
                if (!reasons.isEmpty()) {
                    Athlete a = athleteRepository.findById(id).orElse(null);
                    errors.add(Map.of("id", id,
                            "name", a == null ? "" : a.getName(),
                            "message", "存在关联数据(" + String.join("/", reasons) + ")，已跳过删除"));
                    continue;
                }
                Athlete athlete = getById(id);
                athlete.setDeletedAt(LocalDateTime.now());
                athleteRepository.save(athlete);
                deleted.add(athlete.getName());
            } catch (Exception e) {
                errors.add(Map.of("id", id,
                        "message", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", ids.size());
        result.put("success", deleted.size());
        result.put("skipped", errors.size());
        result.put("deleted", deleted);
        result.put("errors", errors);
        log.info("批量删除运动员: total={}, success={}, skipped={}", ids.size(), deleted.size(), errors.size());
        return result;
    }
}
