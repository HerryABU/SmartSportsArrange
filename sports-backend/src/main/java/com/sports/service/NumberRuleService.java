package com.sports.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.common.Grades;
import com.sports.entity.Athlete;
import com.sports.entity.ClassInfo;
import com.sports.entity.SystemConfig;
import com.sports.repository.AthleteRepository;
import com.sports.repository.ClassInfoRepository;
import com.sports.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 号码簿规则服务 — 完全自定义号码生成规则
 *
 * 支持模板变量：
 *   {grade}          年级编号（grade_mapping 映射，可配 :02d 补零）
 *   {grade_name}     年级名称
 *   {class}          班级编号（auto_extract_class_number 从班名提取，可配 :02d）
 *   {class_name}     班级名称
 *   {seq}            序号（可配 :02d / :03d）
 *   {gender}         性别代码 M / F
 *   {gender_ch}      性别中文 男 / 女
 *   {year}           年份后两位
 *   {school_code}    学校代码（配置）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NumberRuleService {

    private static final String CONFIG_KEY = "number_rule";

    private final SystemConfigRepository systemConfigRepository;
    private final ClassInfoRepository classInfoRepository;
    private final AthleteRepository athleteRepository;
    private final SystemService systemService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 占位符：{var} 或 {var:NNd} */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)(?::(\\d+)d)?\\}");

    // ==================== 配置读写 ====================

    /** 读取号码簿规则（含默认值兜底） */
    @Transactional(readOnly = true)
    public Map<String, Object> getNumberRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("template", "{grade}{class}{seq:02d}");
        rule.put("grade_mapping", defaultGradeMapping());
        rule.put("auto_extract_class_number", true);
        rule.put("auto_pad_zero", true);
        rule.put("unique_global", true);
        rule.put("allow_manual_edit", true);
        rule.put("school_code", "01");

        SystemConfig config = systemConfigRepository.findByConfigKey(CONFIG_KEY).orElse(null);
        if (config != null && config.getConfigValue() != null) {
            try {
                Map<String, Object> saved = objectMapper.readValue(
                        config.getConfigValue(), new TypeReference<Map<String, Object>>() {});
                if (saved != null) {
                    for (Map.Entry<String, Object> e : saved.entrySet()) {
                        rule.put(e.getKey(), e.getValue());
                    }
                }
            } catch (Exception e) {
                log.warn("解析号码簿规则失败，使用默认规则", e);
            }
        }
        return rule;
    }

    /** 保存号码簿规则 */
    @Transactional
    public Map<String, Object> saveNumberRule(Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("号码簿规则序列化失败: " + e.getMessage());
        }
        SystemConfig config = systemConfigRepository.findByConfigKey(CONFIG_KEY)
                .orElse(SystemConfig.builder().configKey(CONFIG_KEY).build());
        config.setConfigValue(json);
        config.setConfigType("rule");
        config.setDescription("号码簿生成规则（完全自定义）");
        config.setUpdatedAt(LocalDateTime.now());
        systemConfigRepository.save(config);
        log.info("号码簿规则已保存");
        return getNumberRule();
    }

    // ==================== 号码簿 · 按名单顺序重新排列（重排号码簿） ====================

    /**
     * 按「年级（系统设置顺序）→ 班级（班级序号）→ 名单（导入顺序）」重排全部（或指定年级）
     * 运动员号码簿：每个班级内序号从 1 重新连续编号，号码模板沿用当前规则。
     *
     * @param gradeScope null/空 = 全部；否则仅该年级（容忍 高一/高一年级 写法）
     * @return 汇总（班级数 / 更新人数 / 样例）
     */
    @Transactional
    public Map<String, Object> reassignNumbers(String gradeScope) {
        Map<String, Object> rule = getNumberRule();

        // 年级出场顺序（系统设置）
        List<String> order = new ArrayList<>();
        for (Map<String, Object> g : systemService.getGrades()) {
            Object nm = g.get("name");
            if (nm != null && !nm.toString().isBlank()) order.add(nm.toString());
        }

        List<ClassInfo> classes = new ArrayList<>();
        for (ClassInfo ci : classInfoRepository.findAll()) {
            if (ci.getDeletedAt() != null) continue;
            if (gradeScope != null && !gradeScope.isBlank()
                    && !Grades.same(gradeScope, ci.getGrade())) continue;
            classes.add(ci);
        }
        classes.sort(Comparator
                .comparingInt((ClassInfo c) -> gradeIdx(order, c.getGrade()))
                .thenComparingInt(c -> c.getClassOrder() == null ? 0 : c.getClassOrder())
                .thenComparingLong(ClassInfo::getId));

        int updated = 0, classesDone = 0;
        List<String> sample = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (ClassInfo ci : classes) {
            List<Athlete> list = athleteRepository.findByClassInfoId(ci.getId());
            list.removeIf(a -> a.getDeletedAt() != null);
            list.sort(Comparator.comparingLong(Athlete::getId));
            if (list.isEmpty()) continue;
            classesDone++;
            int seq = 1;
            for (Athlete a : list) {
                String no = generateNumber(rule, a, ci, seq);
                if (no != null && !no.equals(a.getNumber())) {
                    a.setNumber(no);
                    a.setUpdatedAt(now);
                    athleteRepository.save(a);
                    updated++;
                    if (sample.size() < 5) sample.add(a.getName() + " → " + no);
                }
                seq++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalClasses", classesDone);
        result.put("updated", updated);
        result.put("sample", sample);
        result.put("gradeScope", gradeScope == null || gradeScope.isBlank() ? null : gradeScope);
        log.info("号码簿重排完成: grade={}, classes={}, updated={}",
                result.get("gradeScope"), classesDone, updated);
        return result;
    }

    private static int gradeIdx(List<String> order, String grade) {
        if (grade == null) return 999;
        for (int i = 0; i < order.size(); i++) {
            if (Grades.same(order.get(i), grade)) return i;
        }
        return 999;
    }

    // ==================== 号码簿 · 按名单顺序生成（仅补全空缺） ====================

    /**
     * 按「年级（系统设置顺序）→ 班级（班级序号）→ 名单（导入顺序）」为<b>尚无号码</b>的运动员
     * 生成号码簿：沿用当前模板，班级内序号从「已有号码人数 + 1」起递增，避免覆盖现有号码、也尽量不与现有号码冲突。
     * 适用于初次生成 / 补全新导入运动员的号码；若需整体按名单重排（覆盖全部），请使用 {@link #reassignNumbers}。
     *
     * @param gradeScope null/空 = 全部；否则仅该年级（容忍 高一/高一年级 写法）
     * @return 汇总（班级数 / 新增人数 / 已有人数 / 样例）
     */
    @Transactional
    public Map<String, Object> generateNumbers(String gradeScope) {
        Map<String, Object> rule = getNumberRule();

        List<String> order = new ArrayList<>();
        for (Map<String, Object> g : systemService.getGrades()) {
            Object nm = g.get("name");
            if (nm != null && !nm.toString().isBlank()) order.add(nm.toString());
        }

        List<ClassInfo> classes = new ArrayList<>();
        for (ClassInfo ci : classInfoRepository.findAll()) {
            if (ci.getDeletedAt() != null) continue;
            if (gradeScope != null && !gradeScope.isBlank()
                    && !Grades.same(gradeScope, ci.getGrade())) continue;
            classes.add(ci);
        }
        classes.sort(Comparator
                .comparingInt((ClassInfo c) -> gradeIdx(order, c.getGrade()))
                .thenComparingInt(c -> c.getClassOrder() == null ? 0 : c.getClassOrder())
                .thenComparingLong(ClassInfo::getId));

        // 全库已占用的号码集合：历史数据可能沿用其它编号规则（如纯流水号），
        // 与模板生成值相撞时会触发号码唯一约束并导致整批回退，故生成前先登记、遇撞顺延。
        Set<String> used = new HashSet<>();
        for (Athlete x : athleteRepository.findAll()) {
            if (x.getDeletedAt() == null && x.getNumber() != null && !x.getNumber().isBlank()) {
                used.add(x.getNumber().trim());
            }
        }

        int generated = 0, classesDone = 0, already = 0, skipped = 0;
        List<String> sample = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (ClassInfo ci : classes) {
            List<Athlete> list = athleteRepository.findByClassInfoId(ci.getId());
            list.removeIf(a -> a.getDeletedAt() != null);
            list.sort(Comparator.comparingLong(Athlete::getId));
            if (list.isEmpty()) continue;
            classesDone++;
            // 已有人数 → 新号码起始序号
            long existing = list.stream().filter(a -> a.getNumber() != null && !a.getNumber().isBlank()).count();
            int seq = (int) existing + 1;
            for (Athlete a : list) {
                if (a.getNumber() != null && !a.getNumber().isBlank()) {
                    already++;
                    continue;
                }
                String no = generateNumber(rule, a, ci, seq);
                // 撞号则顺延序号，直至取到未被占用的号码（带上限，防死循环）
                int guard = 0;
                while (no != null && !no.isBlank() && used.contains(no) && guard++ < 999) {
                    no = generateNumber(rule, a, ci, ++seq);
                }
                if (no != null && !no.isBlank() && !used.contains(no)) {
                    used.add(no);
                    a.setNumber(no);
                    a.setUpdatedAt(now);
                    athleteRepository.save(a);
                    generated++;
                    if (sample.size() < 5) sample.add(a.getName() + " → " + no);
                } else {
                    skipped++;
                    log.warn("号码生成跳过（无法取得空闲号码）: athleteId={}, grade={}, class={}",
                            a.getId(), a.getGrade(), ci.getName());
                }
                seq++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalClasses", classesDone);
        result.put("generated", generated);
        result.put("already", already);
        result.put("skipped", skipped);
        result.put("sample", sample);
        result.put("gradeScope", gradeScope == null || gradeScope.isBlank() ? null : gradeScope);
        log.info("号码簿生成(补全)完成: grade={}, classes={}, generated={}, already={}",
                result.get("gradeScope"), classesDone, generated, already);
        return result;
    }

    // ==================== 号码生成 ====================

    /** 根据规则为指定运动员生成号码（不落地，仅计算） */
    public String generateNumber(Athlete athlete, ClassInfo classInfo, int seq) {
        return generateNumber(getNumberRule(), athlete, classInfo, seq);
    }

    /** 根据指定规则为运动员生成号码 */
    public String generateNumber(Map<String, Object> rule, Athlete athlete, ClassInfo classInfo, int seq) {
        String template = str(rule.get("template"), "{grade}{class}{seq:02d}");
        boolean autoPadZero = bool(rule.get("auto_pad_zero"), true);
        boolean autoExtractClass = bool(rule.get("auto_extract_class_number"), true);
        String schoolCode = str(rule.get("school_code"), "01");

        String gradeName = athlete.getGrade();
        String gradeCode = resolveGradeCode(gradeName, rule.get("grade_mapping"));
        int classNum = resolveClassNumber(classInfo, autoExtractClass);
        String gender = athlete.getGender() != null ? athlete.getGender().trim() : "";
        String genderCh = "M".equals(gender) ? "男" : "F".equals(gender) ? "女" : "";
        String year = String.valueOf(LocalDate.now().getYear() % 100);

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("grade", gradeCode);
        raw.put("grade_name", gradeName != null ? gradeName : "");
        raw.put("class", formatClass(classNum, autoPadZero));
        raw.put("class_name", classInfo != null && classInfo.getName() != null ? classInfo.getName() : "");
        raw.put("seq", autoPadZero ? String.format("%02d", seq) : String.valueOf(seq));
        raw.put("gender", gender);
        raw.put("gender_ch", genderCh);
        raw.put("year", year);
        raw.put("school_code", schoolCode);

        Map<String, Long> numeric = new LinkedHashMap<>();
        numeric.put("grade", parseLongSafe(gradeCode));
        numeric.put("class", (long) classNum);
        numeric.put("seq", (long) seq);
        numeric.put("year", Long.parseLong(year));
        numeric.put("school_code", parseLongSafe(schoolCode));

        return render(template, raw, numeric);
    }

    /** 预览：给定模板 + 年级 + 班级 + 序号 → 生成号码 */
    public String preview(String template, String gradeName, String className, int seq, boolean autoPadZero) {
        Map<String, Object> rule = getNumberRule();
        rule.put("template", template);
        rule.put("auto_pad_zero", autoPadZero);

        Athlete athlete = Athlete.builder().grade(gradeName).gender("M").build();
        // 不写死 classOrder，让班级号从班级名称中提取，预览结果才符合实际
        ClassInfo classInfo = ClassInfo.builder().name(className).build();
        return generateNumber(rule, athlete, classInfo, seq);
    }

    // ==================== 模板渲染 ====================

    private String render(String template, Map<String, String> raw, Map<String, Long> numeric) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String var = m.group(1);
            String width = m.group(2);
            String replacement;
            if (width != null && numeric.containsKey(var) && numeric.get(var) != null) {
                // 注意：模板中的 :NNd 已含补零位（如 02），不能再额外加 0，否则 %002d 会触发 DuplicateFormatFlagsException
                replacement = String.format("%" + width + "d", numeric.get(var));
            } else {
                replacement = raw.getOrDefault(var, "");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unchecked")
    private String resolveGradeCode(String gradeName, Object gradeMappingObj) {
        Map<String, Object> mapping = null;
        if (gradeMappingObj instanceof Map) {
            mapping = (Map<String, Object>) gradeMappingObj;
        } else if (gradeMappingObj instanceof String && !((String) gradeMappingObj).isBlank()) {
            try {
                mapping = objectMapper.readValue((String) gradeMappingObj, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) { /* fallthrough */ }
        }
        if (mapping == null) {
            mapping = defaultGradeMapping();
        }
        if (gradeName != null) {
            Object v = mapping.get(gradeName);
            if (v != null) return String.valueOf(v);
            // 容忍写法差异：Athlete/ClassInfo.grade 常存短称（"高一"），而映射键为全称（"高一年级"）。
            // 此处若不做归一，短称永远查不到映射，会静默退化成 "00"，导致各年级号码前缀全部相同。
            for (Map.Entry<String, Object> en : mapping.entrySet()) {
                if (en.getValue() != null && Grades.same(en.getKey(), gradeName)) {
                    return String.valueOf(en.getValue());
                }
            }
            // 再退一步：补「年级」后缀后精确匹配
            String withSuffix = gradeName.endsWith("年级") ? gradeName : gradeName + "年级";
            v = mapping.get(withSuffix);
            if (v != null) return String.valueOf(v);
        }
        return "00";
    }

    private int resolveClassNumber(ClassInfo classInfo, boolean autoExtract) {
        if (classInfo == null) return 1;
        // 1. 优先用 classOrder
        if (classInfo.getClassOrder() != null && classInfo.getClassOrder() > 0) {
            return classInfo.getClassOrder();
        }
        // 2. 从班级编码提取数字：编码形如 "G10-01"，前半是年级、末尾才是班号，
        //    故必须取末位数字段（"G10-01" → 1）；取首段会误拿到年级号 10，使所有班级同号。
        if (classInfo.getCode() != null) {
            Integer n = extractTrailingNumber(classInfo.getCode());
            if (n != null && n > 0) return n;
        }
        // 3. 从班级名称提取末尾数字（如 "高一3班" → 3）
        if (autoExtract && classInfo.getName() != null) {
            Integer n = extractTrailingNumber(classInfo.getName());
            if (n != null) return n;
        }
        return 1;
    }

    private String formatClass(int classNum, boolean autoPadZero) {
        return autoPadZero ? String.format("%02d", classNum) : String.valueOf(classNum);
    }

    private Integer extractTrailingNumber(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        Integer last = null;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    private Long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private static Map<String, Object> defaultGradeMapping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("一年级", "1"); m.put("二年级", "2"); m.put("三年级", "3");
        m.put("四年级", "4"); m.put("五年级", "5"); m.put("六年级", "6");
        m.put("初一年级", "7"); m.put("初二年级", "8"); m.put("初三年级", "9");
        m.put("高一年级", "10"); m.put("高二年级", "11"); m.put("高三年级", "12");
        return m;
    }

    private static String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    private static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        return def;
    }
}
