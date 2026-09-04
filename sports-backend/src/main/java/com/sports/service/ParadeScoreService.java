package com.sports.service;

import com.sports.entity.ClassInfo;
import com.sports.entity.ParadeScore;
import com.sports.repository.ClassInfoRepository;
import com.sports.repository.ParadeScoreRepository;
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

/**
 * 入场式得分服务：手动录入 / Excel 导入 / 查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ParadeScoreService {

    private final ParadeScoreRepository paradeScoreRepository;
    private final ClassInfoRepository classInfoRepository;

    /** 列表（可按年级过滤） */
    @Transactional(readOnly = true)
    public List<ParadeScore> list(String grade) {
        List<ParadeScore> list = (grade == null || grade.isBlank())
                ? paradeScoreRepository.findAllActive()
                : paradeScoreRepository.findByGrade(grade);
        // 按分数从高到低重新排定名次（1-based）
        list.sort(Comparator.comparing(ParadeScore::getScore).reversed());
        return list;
    }

    /** 批量保存/更新（手动录入：一表多行） */
    public List<ParadeScore> saveAll(List<Map<String, Object>> items) {
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

            ParadeScore existing = paradeScoreRepository.findByClassId(classId).orElse(null);
            ParadeScore ps = existing != null ? existing : new ParadeScore();
            ps.setClassInfo(ci);
            ps.setClassName(ci.getName());
            ps.setGrade(ci.getGrade());
            ps.setScore(score);
            ps.setRemark(item.get("remark") != null ? String.valueOf(item.get("remark")) : ps.getRemark());
            ps.setUpdatedAt(LocalDateTime.now());
            if (ps.getCreatedAt() == null) ps.setCreatedAt(LocalDateTime.now());
            saved.add(paradeScoreRepository.save(ps));
        }
        log.info("保存入场式得分: {} 条", saved.size());
        return saved;
    }

    /** 删除一条（软删除） */
    public void delete(Long id) {
        ParadeScore ps = paradeScoreRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("入场式得分记录不存在: " + id));
        ps.setDeletedAt(LocalDateTime.now());
        ps.setUpdatedAt(LocalDateTime.now());
        paradeScoreRepository.save(ps);
        log.info("删除入场式得分: id={}", id);
    }

    /** 清空（可按年级） */
    public void clear(String grade) {
        List<ParadeScore> all = list(grade);
        for (ParadeScore ps : all) {
            ps.setDeletedAt(LocalDateTime.now());
            paradeScoreRepository.save(ps);
        }
        log.info("清空入场式得分: {} 条", all.size());
    }

    /**
     * Excel/CSV 导入。支持两种列布局（表头自动识别）：
     * ① 班级 | 得分
     * ② 年级 | 班级 | 得分
     */
    public Map<String, Object> importExcel(MultipartFile file) {
        String fn = file.getOriginalFilename();
        log.info("导入入场式得分: {}", fn);
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
                    if (grade != null && !grade.isBlank()) {
                        ci = classInfoRepository.findByGradeAndName(grade, className).orElse(null);
                    } else {
                        ci = classInfoRepository.findByName(className).orElse(null);
                    }
                    if (ci == null) {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("row", rowNum);
                        err.put("message", "找不到班级: " + className);
                        errors.add(err);
                        continue;
                    }
                    ParadeScore existing = paradeScoreRepository.findByClassId(ci.getId()).orElse(null);
                    ParadeScore ps = existing != null ? existing : new ParadeScore();
                    ps.setClassInfo(ci);
                    ps.setClassName(ci.getName());
                    ps.setGrade(ci.getGrade());
                    ps.setScore(score);
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
        String text = com.sports.common.FileEncoding.decode(file.getBytes());
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
