package com.sports.service;

import com.alibaba.excel.EasyExcel;
import com.sports.entity.ClassInfo;
import com.sports.repository.ClassInfoRepository;
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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ClassService {

    private final ClassInfoRepository classInfoRepository;
    private final ExcelService excelService;
    private final com.sports.repository.UserRepository userRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<ClassInfo> list(Pageable pageable, String grade) {
        Specification<ClassInfo> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (grade != null && !grade.isBlank())
                predicates.add(cb.equal(root.get("grade"), grade));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return classInfoRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public ClassInfo getById(Long id) {
        return classInfoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("班级不存在: " + id));
    }

    public ClassInfo create(ClassInfo classInfo) {
        if (classInfoRepository.existsByName(classInfo.getName()))
            throw new IllegalArgumentException("班级名称已存在: " + classInfo.getName());
        if (classInfo.getCode() != null && classInfoRepository.existsByCode(classInfo.getCode()))
            throw new IllegalArgumentException("班级编码已存在: " + classInfo.getCode());
        classInfo.setCreatedAt(LocalDateTime.now());
        classInfo.setUpdatedAt(LocalDateTime.now());
        return classInfoRepository.save(classInfo);
    }

    public ClassInfo update(Long id, ClassInfo updated) {
        ClassInfo existing = getById(id);
        if (updated.getName() != null && !updated.getName().equals(existing.getName())) {
            if (classInfoRepository.existsByName(updated.getName()))
                throw new IllegalArgumentException("班级名称已存在: " + updated.getName());
            existing.setName(updated.getName());
        }
        if (updated.getCode() != null && !updated.getCode().equals(existing.getCode())) {
            if (classInfoRepository.existsByCode(updated.getCode()))
                throw new IllegalArgumentException("班级编码已存在: " + updated.getCode());
            existing.setCode(updated.getCode());
        }
        if (updated.getGrade() != null) existing.setGrade(updated.getGrade());
        if (updated.getGradeOrder() != null) existing.setGradeOrder(updated.getGradeOrder());
        if (updated.getClassOrder() != null) existing.setClassOrder(updated.getClassOrder());
        if (updated.getTeacherName() != null) existing.setTeacherName(updated.getTeacherName());
        if (updated.getPhone() != null) existing.setPhone(updated.getPhone());
        if (updated.getStudentCount() != null) existing.setStudentCount(updated.getStudentCount());
        if (updated.getIsParticipating() != null) existing.setIsParticipating(updated.getIsParticipating());
        if (updated.getRemark() != null) existing.setRemark(updated.getRemark());
        existing.setUpdatedAt(LocalDateTime.now());
        return classInfoRepository.save(existing);
    }

    public void delete(Long id) {
        ClassInfo classInfo = getById(id);
        classInfo.setDeletedAt(LocalDateTime.now());
        classInfoRepository.save(classInfo);
        log.info("删除班级成功: {}", classInfo.getName());
    }

    /** 从Excel导入班级 */
    public Map<String, Object> importClasses(MultipartFile file) {
        log.info("从Excel导入班级: {}", file.getOriginalFilename());
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        try {
            List<Map<Integer, String>> rows = EasyExcel.read(file.getInputStream())
                    .sheet().doReadSync();
            int rowNum = 1;
            for (Map<Integer, String> row : rows) {
                rowNum++;
                try {
                    String name = row.getOrDefault(0, "");
                    String code = row.getOrDefault(1, "");
                    String grade = row.getOrDefault(2, "");
                    String teacherName = row.getOrDefault(3, "");

                    if (name.isBlank()) continue;

                    ClassInfo ci = new ClassInfo();
                    ci.setName(name.trim());
                    ci.setCode(code.isBlank() ? null : code.trim());
                    ci.setGrade(grade.isBlank() ? null : grade.trim());
                    ci.setTeacherName(teacherName.isBlank() ? null : teacherName.trim());
                    ci.setIsParticipating(true);
                    create(ci);
                    success++;
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", rowNum);
                    err.put("message", e.getMessage());
                    errors.add(err);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取Excel文件失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", success + errors.size());
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 导出班级到Excel */
    public void export(HttpServletResponse response) throws IOException {
        List<ClassInfo> classes = classInfoRepository.findAll();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "班级信息_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("班级名称", "班级编码", "年级", "班主任", "学生人数", "是否参赛"));
            for (ClassInfo c : classes) {
                data.add(List.of(
                    c.getName() != null ? c.getName() : "",
                    c.getCode() != null ? c.getCode() : "",
                    c.getGrade() != null ? c.getGrade() : "",
                    c.getTeacherName() != null ? c.getTeacherName() : "",
                    c.getStudentCount() != null ? String.valueOf(c.getStudentCount()) : "",
                    c.getIsParticipating() != null && c.getIsParticipating() ? "是" : "否"
                ));
            }
            List<List<String>> headCols = data.get(0).stream()
                    .map(List::of).collect(Collectors.toList());
            EasyExcel.write(out).head(headCols)
                    .sheet("班级信息").doWrite(data.subList(1, data.size()));
        }
        log.info("导出班级信息完成: 共{}条", classes.size());
    }

    /** 下载班级导入模板 */
    public void downloadTemplate(HttpServletResponse response) {
        excelService.getTemplate("class", response);
    }

    /** 批量创建班级，每个班级自动生成班主任账号并绑定 */
    public Map<String, Object> batchCreate(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> grades = (List<String>) body.get("grades");
        int classFrom = ((Number) body.getOrDefault("classFrom", 1)).intValue();
        int classTo = ((Number) body.getOrDefault("classTo", 8)).intValue();
        String defaultPwd = (String) body.getOrDefault("defaultPwd", "123456");

        Map<String, String> gradeMap = new LinkedHashMap<>();
        gradeMap.put("一年级","1"); gradeMap.put("二年级","2"); gradeMap.put("三年级","3");
        gradeMap.put("四年级","4"); gradeMap.put("五年级","5"); gradeMap.put("六年级","6");
        gradeMap.put("初一","7"); gradeMap.put("初二","8"); gradeMap.put("初三","9");
        gradeMap.put("高一","10"); gradeMap.put("高二","11"); gradeMap.put("高三","12");

        int createdClasses = 0, createdTeachers = 0;
        List<String> skipped = new ArrayList<>();

        for (String grade : grades) {
            String gradeNum = gradeMap.getOrDefault(grade, "00");
            for (int i = classFrom; i <= classTo; i++) {
                String name = grade + i + "班";
                String code = "G" + gradeNum + "-" + String.format("%02d", i);
                String teacherUsername = "ct_" + grade + i;

                if (classInfoRepository.existsByName(name)) {
                    skipped.add(name);
                    continue;
                }

                // 1. 创建或复用班主任账号
                com.sports.entity.User teacher = userRepository.findByUsername(teacherUsername).orElse(null);
                if (teacher == null) {
                    teacher = com.sports.entity.User.builder()
                            .username(teacherUsername)
                            .password(passwordEncoder.encode(defaultPwd))
                            .name(name + "班主任")
                            .role("ROLE_CLASS_TEACHER")
                            .status("active")
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    teacher = userRepository.save(teacher);
                    createdTeachers++;
                }

                // 2. 创建班级并绑定班主任
                ClassInfo ci = ClassInfo.builder()
                        .name(name).grade(grade).code(code)
                        .teacherName(teacher.getName())
                        .teacherUser(teacher)
                        .isParticipating(true).build();
                ci.setCreatedAt(LocalDateTime.now());
                ci.setUpdatedAt(LocalDateTime.now());
                classInfoRepository.save(ci);
                createdClasses++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdClasses", createdClasses);
        result.put("createdTeachers", createdTeachers);
        result.put("skipped", skipped.size());
        result.put("skippedList", skipped);
        log.info("批量创建: 班级{}个, 班主任{}个, 跳过{}个", createdClasses, createdTeachers, skipped.size());
        return result;
    }

    /** 绑定班主任到班级 */
    public void bindTeacher(Long classId, String username) {
        ClassInfo ci = getById(classId);
        com.sports.entity.User teacher = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));
        ci.setTeacherUser(teacher);
        ci.setTeacherName(teacher.getName());
        ci.setUpdatedAt(LocalDateTime.now());
        classInfoRepository.save(ci);
        log.info("绑定班主任: class={}, teacher={}", ci.getName(), username);
    }
}
