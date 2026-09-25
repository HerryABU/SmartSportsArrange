package com.sports.service.parade;

import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.meet.SportsMeet;
import com.sports.entity.parade.CustomProject;
import com.sports.entity.parade.ParadeScore;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.parade.ParadeScoreRepository;
import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import com.sports.common.util.FileEncoding;
import com.sports.common.util.Grades;

/**
 * 自定义项目得分服务（班级打分）：手动录入 / Excel 导入 / 查询。按 projectCode 归属到具体项目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ParadeScoreService {

    private final ParadeScoreRepository paradeScoreRepository;
    private final ClassInfoRepository classInfoRepository;
    private final MeetService meetService;
    private final CustomProjectService customProjectService;

    /** 列表（按项目 + 可选年级；按分数从高到低重排名次） */
    @Transactional(readOnly = true)
    public List<ParadeScore> list(String projectCode, String grade) {
        String code = normalizeCode(projectCode);
        String normGrade = Grades.norm(grade);
        List<ParadeScore> list = (normGrade == null || normGrade.isBlank())
                ? paradeScoreRepository.findByProjectCode(code)
                : paradeScoreRepository.findByProjectCode(code).stream()
                    .filter(p -> normGrade.equals(p.getGrade()))
                    .toList();
        list.sort(Comparator.comparing(ParadeScore::getScore).reversed());
        return list;
    }

    /** 批量保存/更新（手动录入：一表多行），按 projectCode 归属 */
    public List<ParadeScore> saveAll(String projectCode, List<Map<String, Object>> items) {
        String code = normalizeCode(projectCode);
        CustomProject project = resolveProject(code);
        List<ParadeScore> saved = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Long classId = item.get("classId") instanceof Number n
                    ? n.longValue() : null;
            Double score = item.get("score") instanceof Number n
                    ? n.doubleValue()
                    : (item.get("score") != null ? Double.parseDouble(String.valueOf(item.get("score"))) : null);
            if (classId == null || score == null) continue;

            ClassInfo ci = classInfoRepository.findById(classId).orElse(null);
            if (ci == null) continue;

            ParadeScore existing = paradeScoreRepository.findByProjectCodeAndClassId(code, classId).orElse(null);
            ParadeScore ps = existing != null ? existing : new ParadeScore();
            ps.setClassInfo(ci);
            ps.setClassName(ci.getName());
            ps.setGrade(ci.getGrade());
            ps.setScore(score);
            ps.setProjectCode(project.getCode());
            ps.setProjectName(project.getName());
            ps.setType(project.getType());
            ps.setMeet(meetService.getActiveOrCreateDefault());
            ps.setRemark(item.get("remark") != null ? String.valueOf(item.get("remark")) : ps.getRemark());
            ps.setUpdatedAt(LocalDateTime.now());
            if (ps.getCreatedAt() == null) ps.setCreatedAt(LocalDateTime.now());
            saved.add(paradeScoreRepository.save(ps));
        }
        log.info("保存项目[{}]得分: {} 条", code, saved.size());
        return saved;
    }

    /** 删除一条（软删除） */
    public void delete(Long id) {
        ParadeScore ps = paradeScoreRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("项目得分记录不存在: " + id));
        ps.setDeletedAt(LocalDateTime.now());
        ps.setUpdatedAt(LocalDateTime.now());
        paradeScoreRepository.save(ps);
        log.info("删除项目得分: id={}", id);
    }

    /** 清空（按项目 + 可选年级） */
    public void clear(String projectCode, String grade) {
        List<ParadeScore> all = list(projectCode, grade);
        for (ParadeScore ps : all) {
            ps.setDeletedAt(LocalDateTime.now());
            paradeScoreRepository.save(ps);
        }
        log.info("清空项目[{}]得分: {} 条", normalizeCode(projectCode), all.size());
    }

    /**
     * Excel/CSV 导入。支持两种列布局（表头自动识别）：
     * ① 班级 | 得分
     * ② 年级 | 班级 | 得分
     */
    public Map<String, Object> importExcel(String projectCode, MultipartFile file) {
        String code = normalizeCode(projectCode);
        CustomProject project = resolveProject(code);
        String fn = file.getOriginalFilename();
        log.info("导入项目[{}]得分: {}", code, fn);
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        try {
            List<Map<Integer, String>> rows;
            if (fn != null && fn.toLowerCase().endsWith(".csv")) {
                rows = readCsv(file);
            } else {
                rows = com.alibaba.excel.EasyExcel.read(file.getInputStream()).sheet().doReadSync();
            }
            int rowNum = 1;
            for (Map<Integer, String> row : rows) {
                rowNum++;
                if (rowNum == 2 && isHeader(row)) continue; // 表头
                try {
                    String col0 = val(row, 0);
                    String col1 = val(row, 1);
                    String col2 = val(row, 2);

                    // 布局判定：① 班级|得分 ；② 年级|班级|得分
                    String grade = null;
                    String className;
                    String scoreStr;
                    if (col2.isEmpty()) {
                        className = col0;
                        scoreStr = col1;
                    } else {
                        grade = col0;
                        className = col1;
                        scoreStr = col2;
                    }
                    if (className.isEmpty() || scoreStr.isEmpty()) continue;
                    if (isHeaderCell(className) || isHeaderCell(grade)) continue;

                    Double score = Double.parseDouble(scoreStr.trim());
                    ClassInfo ci;
                    String normGrade = Grades.norm(grade);
                    String normClassName = Grades.normClassName(className);
                    String cn = (normClassName == null || normClassName.isBlank()) ? className.trim() : normClassName;
                    if (normGrade != null && !normGrade.isBlank()) {
                        ci = classInfoRepository.findByGradeAndName(normGrade, cn).orElse(null);
                    } else {
                        ci = classInfoRepository.findByName(cn).orElse(null);
                    }
                    if (ci == null) {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("row", rowNum);
                        err.put("message", "找不到班级: " + className);
                        errors.add(err);
                        continue;
                    }
                    ParadeScore existing = paradeScoreRepository.findByProjectCodeAndClassId(code, ci.getId()).orElse(null);
                    ParadeScore ps = existing != null ? existing : new ParadeScore();
                    ps.setClassInfo(ci);
                    ps.setClassName(ci.getName());
                    ps.setGrade(ci.getGrade());
                    ps.setScore(score);
                    ps.setProjectCode(project.getCode());
                    ps.setProjectName(project.getName());
                    ps.setType(project.getType());
                    ps.setMeet(meetService.getActiveOrCreateDefault());
                    ps.setUpdatedAt(LocalDateTime.now());
                    if (ps.getCreatedAt() == null) ps.setCreatedAt(LocalDateTime.now());
                    paradeScoreRepository.save(ps);
                    success++;
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", rowNum);
                    err.put("message", e.getMessage());
                    errors.add(err);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", success + errors.size());
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    // ==================== 辅助 ====================

    private String normalizeCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) return CustomProject.DEFAULT_PARADE_CODE;
        return projectCode.trim();
    }

    private CustomProject resolveProject(String code) {
        return customProjectService.getByCode(code)
                .orElseThrow(() -> new RuntimeException("自定义项目不存在: " + code));
    }

    private static boolean isHeader(Map<Integer, String> row) {
        for (String v : row.values()) {
            if (v != null && isHeaderCell(v.trim())) return true;
        }
        return false;
    }

    private static boolean isHeaderCell(String s) {
        if (s == null) return false;
        String t = s.trim();
        return "班级".equals(t) || "班".equals(t) || "年级".equals(t) || "得分".equals(t)
                || "分数".equals(t) || "成绩".equals(t);
    }

    private static String val(Map<Integer, String> row, int idx) {
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    private List<Map<Integer, String>> readCsv(MultipartFile file) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        String text = FileEncoding.decode(file.getBytes());
        String[] lines = text.split("\r?\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cols = line.split("[,，]", -1);
            Map<Integer, String> row = new HashMap<>();
            for (int i = 0; i < cols.length; i++) row.put(i, cols[i].trim());
            rows.add(row);
        }
        return rows;
    }
}
