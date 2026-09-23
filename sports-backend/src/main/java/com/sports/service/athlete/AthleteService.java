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
import com.sports.common.util.Grades;

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

    /** 分页查询（className 支持按班级名称模糊筛选，便于「直接输入班级名」的检索） */
    @Transactional(readOnly = true)
    public Page<Athlete> list(Pageable pageable, String grade, Long classId, String gender, String keyword,
                              String className) {
        Page<Athlete> result = athleteRepository.findAll(buildSpec(grade, classId, gender, keyword, className), pageable);
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
    public List<Long> findIdsByFilter(String grade, Long classId, String gender, String keyword, String className) {
        return athleteRepository.findAll(buildSpec(grade, classId, gender, keyword, className)).stream()
                .map(Athlete::getId)
                .toList();
    }

    /** 统一的筛选条件（年级/班级/性别/关键词），分页查询与全选删除复用 */
    private Specification<Athlete> buildSpec(String grade, Long classId, String gender, String keyword,
                                             String className) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (grade != null && !grade.isBlank())
                // 模糊年级：筛选「高一」时同时命中库里存的「高一年级」「10年级」等写法
                predicates.add(root.get("grade").in(Grades.equivalents(grade)));
            if (classId != null)
                predicates.add(cb.equal(root.get("classInfo").get("id"), classId));
            if (className != null && !className.isBlank())
                predicates.add(cb.like(root.get("classInfo").get("name"), "%" + className.trim() + "%"));
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
        athlete.setGrade(Grades.norm(athlete.getGrade()));   // 模糊年级：「10年级 / 高一年级」统一存「高一」
        if (athlete.getClassInfo() != null && athlete.getClassInfo().getId() != null) {
            classInfoRepository.findById(athlete.getClassInfo().getId())
                    .orElseThrow(() -> new IllegalArgumentException("班级不存在"));
        } else {
            // 允许前端直接输入班级名称（不必先建班级）：按名称解析，缺失则自动创建
            athlete.setClassInfo(resolveOrCreateClass(athlete.getClassNameInput(), athlete.getGrade()));
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
        if (updated.getGrade() != null) {
            String g = Grades.norm(updated.getGrade());
            if (g != null) existing.setGrade(g);   // 模糊年级：非空才覆盖
        }
        if (updated.getClassInfo() != null && updated.getClassInfo().getId() != null) {
            existing.setClassInfo(updated.getClassInfo());
        } else if (updated.getClassNameInput() != null && !updated.getClassNameInput().isBlank()) {
            // 直接输入班级名 → 解析或自动创建
            existing.setClassInfo(resolveOrCreateClass(updated.getClassNameInput(),
                    updated.getGrade() != null ? updated.getGrade() : existing.getGrade()));
        }
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

    /**
     * 按班级名称解析 ClassInfo；不存在时按 (年级, 班级) 自动创建。
     * 与 Excel 导入的「班级缺失自动创建」口径一致，让前端可以直接输入班级名而不必先建班。
     *
     * <p>年级/班级做模糊匹配：「高三年级1班」「10年级1班」「高三（1）班」与「高三1班」视为同一班级。</p>
     */
    private ClassInfo resolveOrCreateClass(String className, String grade) {
        if (className == null || className.isBlank()) {
            return null;
        }
        String name = Grades.normClassName(className);
        if (name == null || name.isBlank()) name = className.trim();
        String normGrade = Grades.norm(grade);

        ClassInfo found = classInfoRepository.findByName(name).orElse(null);
        if (found == null) {
            // 兜底：按归一化班级键匹配（容忍存量库里「高三年级1班」vs 本次「高三1班」）
            String key = Grades.classKey(name);
            if (key != null && !key.startsWith("RAW:")) {
                found = classInfoRepository.findAll().stream()
                        .filter(c -> key.equals(Grades.classKey(c.getName())))
                        .findFirst().orElse(null);
            }
        }
        if (found != null) {
            return found;
        }
        ClassInfo c = new ClassInfo();
        c.setName(name);
        c.setGrade(normGrade);
        c.setGradeOrder(Grades.order(normGrade));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        log.info("按名称自动创建班级: {}（年级 {}）", name, normGrade);
        return classInfoRepository.save(c);
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
        String normGrade = Grades.norm(grade);   // 模糊年级：库内统一存规范名
        List<Athlete> athletes;
        if (classId != null) athletes = athleteRepository.findByClassIdAndGrade(classId, normGrade);
        else if (normGrade != null) athletes = athleteRepository.findByGrade(normGrade);
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

    /**
     * 全部删除（软删除）：对「当前全部运动员」执行 {@link #batchDelete}。
     *
     * <p>仍保留引用保护——被成绩 / 报名 / 编排引用的运动员会<b>跳过并报告原因</b>，
     * 因此本操作不会破坏历史数据；前端须以强警告二次确认。</p>
     */
    public Map<String, Object> deleteAll() {
        List<Long> ids = athleteRepository.findAll().stream().map(Athlete::getId).toList();
        Map<String, Object> result = new LinkedHashMap<>(batchDelete(ids));
        result.put("all", true);
        log.info("全部删除运动员: 候选 {} 人，实际删除 {} 人，跳过 {} 人",
                ids.size(), result.get("success"), result.get("skipped"));
        return result;
    }
}
