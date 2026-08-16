package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.sports.entity.Athlete;
import com.sports.entity.ClassInfo;
import com.sports.repository.ClassInfoRepository;
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
            classInfo = classInfoRepository.findByName(m.getClassName().trim()).orElse(null);
        }
        String grade = m.getGrade();
        if ((grade == null || grade.isBlank()) && classInfo != null) grade = classInfo.getGrade();

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
