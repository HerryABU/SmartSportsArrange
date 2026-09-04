package com.sports.service;

import com.sports.entity.*;
import com.sports.repository.*;
import com.alibaba.excel.EasyExcel;
import com.sports.security.JwtUserDetails;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
public class RegistrationService {

    private final RegistrationRepository registrationRepository;
    private final AthleteRepository athleteRepository;
    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final NumberRuleService numberRuleService;

    /** 分页查询报名（班主任自动限本人绑定班级，无法越班查询） */
    @Transactional(readOnly = true)
    public Page<Registration> list(Pageable pageable, Long eventId, Long classId, String status) {
        UserScope scope = currentUserScope();
        Specification<Registration> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (eventId != null)
                predicates.add(cb.equal(root.get("event").get("id"), eventId));
            if (classId != null && (!scope.restricted || scope.classIds.contains(classId)))
                predicates.add(cb.equal(root.get("athlete").get("classInfo").get("id"), classId));
            if (status != null && !status.isBlank())
                predicates.add(cb.equal(root.get("status"), status));
            if (scope.restricted) // 班主任：只看得到自己班级的报名
                predicates.add(root.get("athlete").get("classInfo").get("id").in(scope.classIds));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return registrationRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Registration getById(Long id) {
        Registration reg = registrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报名记录不存在"));
        UserScope scope = currentUserScope();
        if (scope.restricted && !belongsToMyClass(reg, scope)) {
            throw new IllegalArgumentException("无权访问其他班级的报名记录");
        }
        return reg;
    }

    /** 报名记录是否属于当前班主任绑定班级 */
    private boolean belongsToMyClass(Registration reg, UserScope scope) {
        if (!scope.restricted) return true;
        if (reg.getAthlete() == null || reg.getAthlete().getClassInfo() == null) return false;
        return scope.classIds.contains(reg.getAthlete().getClassInfo().getId());
    }

    /** 单个报名 */
    public Registration create(Long athleteId, Long eventId) {
        Athlete athlete = athleteRepository.findById(athleteId)
                .orElseThrow(() -> new IllegalArgumentException("运动员不存在"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        if (registrationRepository.existsByAthleteIdAndEventId(athleteId, eventId))
            throw new IllegalArgumentException("该运动员已报名此项目");
        if (!com.sports.common.GenderUtil.matches(event.getGenderLimit(), athlete.getGender()))
            throw new IllegalArgumentException("性别不符合项目要求");
        int maxPerClass = getConfig("maxAthletesPerEvent", 3);
        long classCount = registrationRepository.countByClassAndEvent(
                athlete.getClassInfo() != null ? athlete.getClassInfo().getId() : 0L, eventId);
        if (classCount >= maxPerClass)
            throw new IllegalArgumentException("该班级报名此项目已达上限(" + maxPerClass + "人)");
        int maxPerAthlete = getConfig("maxEventsPerAthlete", 3);
        long activeRegCount = registrationRepository.findByAthleteId(athleteId).stream()
                .filter(r -> !"withdrawn".equals(r.getStatus())).count();
        if (activeRegCount >= maxPerAthlete)
            throw new IllegalArgumentException("该运动员报名项目已达上限(" + maxPerAthlete + "项)");
        Registration reg = Registration.builder()
                .athlete(athlete).event(event)
                .status("pending")
                .registrationTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        Registration saved = registrationRepository.save(reg);
        log.info("报名成功: athleteId={}, eventId={}", athleteId, eventId);
        return saved;
    }

    /** 批量报名 */
    public List<Registration> batchRegister(List<Map<String, Long>> items) {
        List<Registration> results = new ArrayList<>();
        for (Map<String, Long> item : items) {
            try {
                results.add(create(item.get("athleteId"), item.get("eventId")));
            } catch (Exception e) {
                log.warn("批量报名单项失败: {}", e.getMessage());
            }
        }
        return results;
    }

    /** 取消报名（班主任仅限本班） */
    public void cancel(Long id) {
        Registration reg = getById(id); // 内含班主任班级越权校验
        reg.setStatus("withdrawn");
        reg.setUpdatedAt(LocalDateTime.now());
        registrationRepository.save(reg);
        log.info("取消报名: id={}", id);
    }

    // ==================== 报名表（表格1）导入 ====================
    //
    // 三种模式统一入口（列布局：年级|班级|姓名|性别|学号|项目|是否团体赛数量(0=个人)|成绩）：
    //   1. 班主任 · 现场报名   —— source=onsite，落库 status=pending，由体育老师审核；仅限本人绑定班级
    //   2. 班主任 · 后置导入   —— source=offline，落库 status=approved；仅限本人绑定班级
    //   3. 体育老师/管理员 · 后置导入 —— 一律按后置导入处理(status=approved)，任意班级
    // 角色强制：体育老师/管理员入口不接受 onsite（服务端自动归一为 offline），
    //          班主任仅可操作本人绑定班级（列表/详情/取消均受限，且无审核权限）。
    // 成绩列为预留列（可空）：当前导入环节不落成绩。

    /**
     * 导入报名表（Excel/CSV）。行内学生/项目缺失时自动按「学号/姓名+班级」匹配，
     * 匹配不到则自动建档（运动员 + 学号账号）。
     */
    public Map<String, Object> importSignupSheet(MultipartFile file, String source) {
        UserScope scope = currentUserScope();
        boolean restricted = scope.restricted;
        Set<Long> myClassIds = scope.classIds;
        // 角色 × 来源强制：体育老师/管理员 = 后置导入(approved)；班主任可 现场(pending)/后置(approved)
        String src;
        if (restricted) {
            src = "offline".equalsIgnoreCase(source) ? "offline" : "onsite";
        } else {
            if (!"offline".equalsIgnoreCase(source)) {
                log.warn("体育老师导入仅支持后置(offline)模式，已自动归一: source={}", source);
            }
            src = "offline";
        }
        String status = "offline".equals(src) ? "approved" : "pending";
        log.info("导入报名表: file={}, source={}, roleRestricted={}", file.getOriginalFilename(), src, restricted);
        if (restricted && myClassIds.isEmpty()) {
            throw new IllegalArgumentException("当前班主任账号尚未绑定班级，请联系管理员在「班级管理」中绑定后再导入");
        }

        List<Map<Integer, String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = readRows(in, file.getOriginalFilename());
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }

        // 表头探测 → 列索引映射；无法识别时按固定顺序 0..7
        int[] idx = detectColumns(rows);
        int colGrade = idx[0], colClass = idx[1], colName = idx[2], colGender = idx[3],
                colStudentNo = idx[4], colEvent = idx[5], colTeam = idx[6];

        int success = 0, skipped = 0, createdAthletes = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<Integer, String> row = rows.get(i);
            int rowNum = i + 2; // 含表头
            try {
                String name = cell(row, colName);
                String eventText = cell(row, colEvent);
                if (name.isEmpty() && eventText.isEmpty()) continue; // 空行
                if (isHeaderText(name) || isHeaderText(eventText)) continue; // 表头

                String gradeText = cell(row, colGrade);
                String classText = cell(row, colClass);
                String genderText = cell(row, colGender);
                String studentNo = cell(row, colStudentNo);
                String teamText = cell(row, colTeam);

                // ---- 定位班级（班主任受限时只允许自己绑定班；体育老师按表内 班级列 解析） ----
                ClassInfo classInfo = null;
                if (restricted) {
                    if (!classText.isEmpty()) {
                        // 表内填了班级 → 必须是班主任自己的班
                        ClassInfo byName = classInfoRepository.findByName(classText).orElse(null);
                        ClassInfo byGrade = (!gradeText.isEmpty() && byName == null)
                                ? classInfoRepository.findByGradeAndName(gradeText, classText).orElse(null)
                                : null;
                        ClassInfo hit = byName != null ? byName : byGrade;
                        if (hit != null && myClassIds.contains(hit.getId())) {
                            classInfo = hit;
                        }
                    }
                    // 班级列为空且恰好绑定 1 个班时，才自动归班；填了其他班绝不静默改绑
                    if (classInfo == null && classText.isEmpty() && myClassIds.size() == 1) {
                        classInfo = classInfoRepository.findById(myClassIds.iterator().next()).orElse(null);
                    }
                } else {
                    if (!classText.isEmpty()) {
                        classInfo = classInfoRepository.findByName(classText).orElse(null);
                        if (classInfo == null && !gradeText.isEmpty()) {
                            classInfo = classInfoRepository.findByGradeAndName(gradeText, classText).orElse(null);
                        }
                    }
                }
                if (classInfo == null) {
                    errors.add(err(rowNum, restricted
                            ? "只能导入本人绑定班级的报名（当前班级不在绑定范围或未绑定）"
                            : "无法确定目标班级，请填写「班级」列"));
                    continue;
                }

                // ---- 定位/建档运动员 ----
                Athlete athlete = resolveAthlete(classInfo, studentNo, name, genderText);
                if (athlete == null) {
                    errors.add(err(rowNum, "无法建档运动员: " + name));
                    continue;
                }
                if (athlete.getId() == null) {
                    athleteRepository.save(athlete);
                    createdAthletes++;
                } else if (athlete.getClassInfo() != null
                        && !athlete.getClassInfo().getName().equals(classInfo.getName())) {
                    // 学号重复且班级不一致 → 以学号为准更新其班级
                    athlete.setClassInfo(classInfo);
                    athlete.setGrade(classInfo.getGrade());
                    athleteRepository.save(athlete);
                }

                // ---- 定位项目 ----
                Event event = resolveEvent(eventText);
                if (event == null) {
                    errors.add(err(rowNum, "未找到项目: " + eventText
                            + "（请使用项目编码如 100M 或精确项目名称）"));
                    continue;
                }

                // ---- 校验 & 建档报名 ----
                if (registrationRepository.existsByAthleteIdAndEventId(athlete.getId(), event.getId())) {
                    skipped++; // 重复报名，幂等跳过
                    continue;
                }
                if (!com.sports.common.GenderUtil.matches(event.getGenderLimit(), athlete.getGender())) {
                    errors.add(err(rowNum, "性别不符合项目「" + event.getName() + "」要求"));
                    continue;
                }

                int teamCount = parseIntSafe(teamText, 0);
                boolean isTeam = teamCount > 0;
                int teamNo = isTeam ? nextTeamNo(athlete, event, teamCount) : 0;
                String teamName = isTeam && classInfo != null
                        ? classInfo.getName() + " " + teamNo + " 队" : null;
                Registration reg = Registration.builder()
                        .athlete(athlete)
                        .event(event)
                        .team(isTeam)
                        .teamNo(teamNo)
                        .teamName(teamName)
                        .source(src)
                        .status(status)
                        .registrationTime(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                registrationRepository.save(reg);
                success++;
            } catch (Exception e) {
                errors.add(err(rowNum, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("success", success);
        result.put("skipped", skipped);
        result.put("createdAthletes", createdAthletes);
        result.put("failed", errors.size());
        result.put("errors", errors);
        result.put("source", src);
        result.put("status", status);
        return result;
    }

    /**
     * 当前用户作用域：班主任(ROLE_CLASS_TEACHER)受限本人绑定班级；
     * 体育老师/管理员(ROLE_TEACHER/ROLE_SUPER_ADMIN)不受限（可导入任意班）。
     */
    private UserScope currentUserScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtUserDetails ud)) {
            return new UserScope(false, Set.of());
        }
        boolean restricted = "ROLE_CLASS_TEACHER".equals(ud.getRole());
        if (!restricted) return new UserScope(false, Set.of());
        List<ClassInfo> classes = classInfoRepository.findByTeacherUserId(ud.getUserId());
        Set<Long> ids = classes.stream().map(ClassInfo::getId).collect(Collectors.toSet());
        return new UserScope(true, ids);
    }

    private static class UserScope {
        final boolean restricted;
        final Set<Long> classIds;

        UserScope(boolean restricted, Set<Long> classIds) {
            this.restricted = restricted;
            this.classIds = classIds;
        }
    }

    /** 按学号 → (姓名+班级+性别) 匹配；都不存在则建档 */
    private Athlete resolveAthlete(ClassInfo classInfo, String studentNo, String name, String genderText) {
        String gender = mapGender(genderText);
        if (!studentNo.isEmpty()) {
            Optional<Athlete> byNo = athleteRepository.findByStudentId(studentNo);
            if (byNo.isPresent()) return byNo.get();
        }
        // 姓名+班级+性别 匹配
        for (Athlete a : athleteRepository.findByName(name)) {
            if (a.getClassInfo() != null && a.getClassInfo().getId().equals(classInfo.getId())
                    && (gender == null || gender.equals(a.getGender()))) {
                return a;
            }
        }
        // 建档
        return Athlete.builder()
                .name(name)
                .gender(gender)
                .grade(classInfo.getGrade())
                .classInfo(classInfo)
                .studentId(studentNo.isEmpty() ? null : studentNo)
                .number(generateUniqueNumber(classInfo))
                .status("normal")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 按号码簿规则生成不重复的运动员号码 */
    private String generateUniqueNumber(ClassInfo classInfo) {
        int seq = 1;
        String number;
        do {
            number = numberRuleService.generateNumber(
                    Athlete.builder().name("x").grade(classInfo.getGrade())
                            .classInfo(classInfo).build(),
                    classInfo, seq++);
        } while (athleteRepository.findByNumber(number).isPresent());
        return number;
    }

    /** 定位项目：先精确编码，再精确名称，再唯一名称包含 */
    private Event resolveEvent(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        Optional<Event> byCode = eventRepository.findByCode(t);
        if (byCode.isPresent()) return byCode.get();
        Optional<Event> byName = eventRepository.findByNameAndIsEnabledTrue(t);
        if (byName.isPresent()) return byName.get();
        List<Event> fuzzy = eventRepository.findByIsEnabledTrueAndNameContaining(t);
        return fuzzy.size() == 1 ? fuzzy.get(0) : null;
    }

    /** 同班同项目下一队序号（供团体赛） */
    private int nextTeamNo(Athlete athlete, Event event, int teamMembers) {
        List<Registration> existing = registrationRepository
                .findByEventIdAndClassId(event.getId(),
                        athlete.getClassInfo() != null ? athlete.getClassInfo().getId() : 0L);
        int teamCount = (int) Math.ceil(existing.size() / (double) Math.max(1, teamMembers));
        return teamCount + 1;
    }

    /** 读取 CSV/Excel 为行（CSV 自动识别 UTF-8/GB18030 等编码） */
    private List<Map<Integer, String>> readRows(InputStream in, String filename) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        if (filename != null && filename.toLowerCase().endsWith(".csv")) {
            String text = com.sports.common.FileEncoding.decode(in);
            String[] lines = text.split("\r?\n", -1);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] cols = line.split("[,，]", -1);
                Map<Integer, String> row = new HashMap<>();
                for (int i = 0; i < cols.length; i++) row.put(i, cols[i].trim());
                rows.add(row);
            }
        } else {
            List<Map<Integer, String>> excelRows = EasyExcel.read(in).sheet().headRowNumber(0).doReadSync();
            for (Map<Integer, String> r : excelRows) {
                Map<Integer, String> row = new HashMap<>();
                for (Map.Entry<Integer, String> e : r.entrySet()) row.put(e.getKey(),
                        e.getValue() == null ? "" : e.getValue().trim());
                rows.add(row);
            }
        }
        return rows;
    }

    /** 表头识别；识别不到按固定顺序（年级,班级,姓名,性别,学号,项目,数量,成绩） */
    private int[] detectColumns(List<Map<Integer, String>> rows) {
        if (!rows.isEmpty()) {
            Map<Integer, String> head = rows.get(0);
            Map<String, Integer> map = new HashMap<>();
            for (Map.Entry<Integer, String> e : head.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    map.putIfAbsent(e.getValue().trim(), e.getKey());
                }
            }
            if (map.containsKey("姓名") && (map.containsKey("项目") || map.containsKey("项目名称"))) {
                String eventKey = map.containsKey("项目") ? "项目" : "项目名称";
                return new int[]{
                        map.getOrDefault("年级", 0),
                        map.getOrDefault("班级", map.getOrDefault("班", 1)),
                        map.get("姓名"),
                        map.getOrDefault("性别", 3),
                        map.getOrDefault("学号", map.getOrDefault("学号/账号", 4)),
                        map.get(eventKey),
                        map.getOrDefault("是否团体", map.getOrDefault("数量", map.getOrDefault("团体人数", 6))),
                };
            }
        }
        return new int[]{0, 1, 2, 3, 4, 5, 6};
    }

    private static String cell(Map<Integer, String> row, int idx) {
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    private static boolean isHeaderText(String s) {
        if (s == null) return false;
        String t = s.trim();
        return "姓名".equals(t) || "项目".equals(t) || "项目名称".equals(t)
                || "班级".equals(t) || "年级".equals(t) || "性别".equals(t) || "学号".equals(t);
    }

    private static Map<String, Object> err(int rowNum, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", rowNum);
        m.put("message", message);
        return m;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String mapGender(String v) {
        if (v == null || v.isBlank()) return null;
        return switch (v.trim()) {
            case "男", "M", "m" -> "M";
            case "女", "F", "f" -> "F";
            default -> v.trim();
        };
    }


    /** 审核（仅体育老师/管理员；班主任无审核权限——现场报名由体育老师把关） */
    public Registration approve(Long id, String remark) {
        Registration reg = getById(id);
        assertNotRestricted("班主任无审核权限，现场报名的审核由体育老师完成");
        reg.setStatus("approved");
        reg.setAuditRemark(remark);
        reg.setAuditTime(LocalDateTime.now());
        reg.setUpdatedAt(LocalDateTime.now());
        Registration saved = registrationRepository.save(reg);
        log.info("审核通过: id={}", id);
        return saved;
    }

    /** 拒绝报名（仅体育老师/管理员） */
    public Registration reject(Long id) {
        Registration reg = getById(id);
        assertNotRestricted("班主任无审核权限，现场报名的审核由体育老师完成");
        reg.setStatus("rejected");
        reg.setAuditTime(LocalDateTime.now());
        reg.setUpdatedAt(LocalDateTime.now());
        Registration saved = registrationRepository.save(reg);
        log.info("拒绝报名: id={}", id);
        return saved;
    }

    /** 班主任无审核权限时抛错 */
    private void assertNotRestricted(String message) {
        UserScope scope = currentUserScope();
        if (scope.restricted) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 统计 */
    @Transactional(readOnly = true)
    public Map<String, Object> statistics() {
        List<Registration> all = registrationRepository.findAll();
        long total = all.size();
        long approved = all.stream().filter(r -> "approved".equals(r.getStatus())).count();
        long pending = all.stream().filter(r -> "pending".equals(r.getStatus())).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("approved", approved);
        stats.put("pending", pending);
        return stats;
    }

    /** 导出 */
    public void export(HttpServletResponse response) throws IOException {
        List<Registration> list = registrationRepository.findAll();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "报名信息_" + LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("运动员姓名", "号码簿", "班级", "报名项目", "状态", "报名时间"));
            for (Registration r : list) {
                Athlete a = r.getAthlete();
                data.add(List.of(
                    a != null ? (a.getName() != null ? a.getName() : "") : "",
                    a != null ? (a.getNumber() != null ? a.getNumber() : "") : "",
                    a != null && a.getClassInfo() != null ? a.getClassInfo().getName() : "",
                    r.getEvent() != null ? r.getEvent().getName() : "",
                    r.getStatus() != null ? r.getStatus() : "",
                    r.getRegistrationTime() != null ? r.getRegistrationTime().toString() : ""
                ));
            }
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                .head(headCols)
                .sheet("报名信息").doWrite(data.subList(1, data.size()));
            log.info("导出报名信息: 共{}条", list.size());
        }
    }

    private int getConfig(String key, int def) {
        return systemConfigRepository.findByConfigKey(key)
                .map(c -> { try { return Integer.parseInt(c.getConfigValue()); } catch (Exception e) { return def; } })
                .orElse(def);
    }

    /** 报名表（表格1）导入模板下载 */
    public void exportSignupTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "报名表模板_表格1.xlsx";
        String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);
        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("年级", "班级", "姓名", "性别", "学号", "项目(名称或代码)", "是否团体赛数量(0=个人)", "成绩(可空)"));
            data.add(List.of("高一年级", "高一1班", "张三", "男", "20260001", "100米", "0", ""));
            data.add(List.of("高一年级", "高一1班", "李四", "男", "20260002", "100M", "0", ""));
            data.add(List.of("高一年级", "高一1班", "王五", "男", "20260003", "4×100米接力", "4", ""));
            java.util.List<java.util.List<String>> headCols = data.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                    .head(headCols)
                    .sheet("报名表").doWrite(data.subList(1, data.size()));
        }
    }
}
