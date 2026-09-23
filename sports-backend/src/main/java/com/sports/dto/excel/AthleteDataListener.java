package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.sports.common.util.Grades;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.repository.clazz.ClassInfoRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 运动员 Excel 导入监听器
 */
@Slf4j
public class AthleteDataListener implements ReadListener<AthleteExcelModel> {

    private static final int BATCH_SIZE = 100;

    private final ClassInfoRepository classInfoRepository;
    private final List<Athlete> athletes;
    private final List<Map<String, Object>> errors;
    private final List<Athlete> cachedList = new ArrayList<>(BATCH_SIZE);

    public AthleteDataListener(ClassInfoRepository classInfoRepository,
                               List<Athlete> athletes,
                               List<Map<String, Object>> errors) {
        this.classInfoRepository = classInfoRepository;
        this.athletes = athletes;
        this.errors = errors;
    }

    @Override
    public void invoke(AthleteExcelModel model, AnalysisContext context) {
        try {
            Athlete athlete = convert(model);
            if (athlete != null) cachedList.add(athlete);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("row", context.readRowHolder().getRowIndex() + 1);
            error.put("message", e.getMessage());
            errors.add(error);
        }
        if (cachedList.size() >= BATCH_SIZE) {
            athletes.addAll(cachedList);
            cachedList.clear();
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        if (!cachedList.isEmpty()) { athletes.addAll(cachedList); cachedList.clear(); }
        log.info("解析完成: {} 条, 错误 {} 条", athletes.size(), errors.size());
    }

    private Athlete convert(AthleteExcelModel m) {
        if (m.getName() == null || m.getName().isBlank()) return null;
        String gender = mapGender(m.getGender());

        ClassInfo classInfo = null;
        if (m.getClassName() != null && !m.getClassName().isBlank()) {
            // 模糊班级名：「高三年级（1）班」→「高三1班」，与库内命名对齐后再查
            String className = Grades.normClassName(m.getClassName());
            if (className == null || className.isBlank()) className = m.getClassName().trim();
            classInfo = classInfoRepository.findByName(className).orElse(null);
        }
        // 模糊年级：「高一年级 / 10年级 / Grade 10」都归一成「高一」再落库
        String grade = Grades.norm(m.getGrade());
        if (grade == null && classInfo != null) grade = classInfo.getGrade();

        Athlete a = Athlete.builder()
                .name(m.getName().trim()).gender(gender).grade(grade).classInfo(classInfo)
                .number(m.getNumber()).studentId(m.getStudentId()).idCard(m.getIdCard())
                .emergencyContact(m.getEmergencyContact()).emergencyPhone(m.getEmergencyPhone())
                .healthStatus(m.getHealthStatus()).remark(m.getRemark()).status("normal").build();
        if (m.getBirthDate() != null && !m.getBirthDate().isBlank()) {
            try { a.setBirthDate(java.time.LocalDate.parse(m.getBirthDate().trim())); }
            catch (Exception e) { log.warn("日期格式错误: {}", m.getBirthDate()); }
        }
        return a;
    }

    private String mapGender(String v) {
        if (v == null) return null;
        return switch (v.trim()) {
            case "男","M","m","male","Male","男子","男生" -> "M";
            case "女","F","f","female","Female","女子","女生" -> "F";
            default -> v.trim();
        };
    }
}
