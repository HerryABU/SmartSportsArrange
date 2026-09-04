package com.sports.controller;

import com.sports.common.ApiResponse;
import com.sports.entity.*;
import com.sports.repository.*;
import com.sports.security.JwtUserDetails;
import com.sports.service.NumberRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 班主任端控制器 — 名单导入 / 运动员管理 / 报名 / 赛程 / 成绩
 * 班主任只能操作自己绑定的班级
 */
@Slf4j
@RestController
@RequestMapping("/api/class-teacher")
@RequiredArgsConstructor
public class ClassTeacherController {

    private final AthleteRepository athleteRepository;
    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final UserRepository userRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final PasswordEncoder passwordEncoder;
    private final NumberRuleService numberRuleService;

    // ===== 获取当前班主任绑定的班级列表 =====
    private List<ClassInfo> getMyClasses() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtUserDetails userDetails) {
            return classInfoRepository.findByTeacherUserId(userDetails.getUserId());
        }
        return List.of();
    }

    private Long getMyClassId() {
        List<ClassInfo> classes = getMyClasses();
        if (classes.isEmpty()) throw new IllegalArgumentException("当前用户未关联班级，请联系管理员绑定");
        return classes.get(0).getId();
    }

    // ===== 导入全班名单 Excel（学号/姓名/性别）→ 自动创建学生账号 + 运动员 =====
    @PostMapping("/import-roster")
    @Transactional
    public ApiResponse<?> importRoster(@RequestParam MultipartFile file,
                                        @RequestParam(required = false) Long classId) {
        Long myClassId = classId != null ? classId : getMyClassId();
        ClassInfo classInfo = classInfoRepository.findById(myClassId)
                .orElseThrow(() -> new RuntimeException("班级不存在"));

        int createdUsers = 0, createdAthletes = 0, skipped = 0;
        List<String> errors = new ArrayList<>();

        List<Map<Integer, String>> rows;
        boolean isCsv = file.getOriginalFilename() != null
                && file.getOriginalFilename().toLowerCase().endsWith(".csv");
        try {
            if (isCsv) {
                // CSV：自动识别 UTF-8/GB18030 等编码，跳过「学号,姓名,性别」表头
                rows = new ArrayList<>();
                String text = com.sports.common.FileEncoding.decode(file.getBytes());
                String[] lines = text.split("\r?\n", -1);
                boolean isFirst = true;
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    if (isFirst) { isFirst = false; continue; }
                    String[] cols = line.split("[,，]", -1);
                    Map<Integer, String> row = new HashMap<>();
                    for (int i = 0; i < cols.length; i++) row.put(i, cols[i].trim());
                    rows.add(row);
                }
            } else {
                rows = com.alibaba.excel.EasyExcel.read(file.getInputStream())
                        .sheet().headRowNumber(1).doReadSync();
            }

            for (int i = 0; i < rows.size(); i++) {
                Map<Integer, String> row = rows.get(i);
                try {
                    String studentId = row.getOrDefault(0, "").trim();  // 学号
                    String name = row.getOrDefault(1, "").trim();         // 姓名
                    String gender = row.getOrDefault(2, "").trim();        // 性别

                    if (studentId.isEmpty() || name.isEmpty()) {
                        skipped++;
                        continue;
                    }

                    // 1. 创建或更新学生用户账号（学号=用户名）
                    User studentUser = userRepository.findByUsername(studentId).orElse(null);
                    if (studentUser == null) {
                        studentUser = User.builder()
                                .username(studentId)
                                .password(passwordEncoder.encode(studentId))
                                .name(name)
                                .role("ROLE_STUDENT")
                                .status("active")
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();
                        userRepository.save(studentUser);
                        createdUsers++;
                    }

                    // 2. 创建运动员记录（如果不存在）
                    if (athleteRepository.findByStudentId(studentId).isEmpty()) {
                        // 按自定义号码簿规则生成号码（保证全局唯一）
                        int seq = 1;
                        String number;
                        do {
                            number = numberRuleService.generateNumber(
                                    Athlete.builder().name(name).gender(mapGender(gender))
                                            .grade(classInfo.getGrade()).classInfo(classInfo).build(),
                                    classInfo, seq++);
                        } while (athleteRepository.findByNumber(number).isPresent());

                        Athlete athlete = Athlete.builder()
                                .name(name)
                                .gender(mapGender(gender))
                                .grade(classInfo.getGrade())
                                .classInfo(classInfo)
                                .studentId(studentId)
                                .number(number)
                                .status("normal")
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();
                        athleteRepository.save(athlete);
                        createdAthletes++;
                    }
                } catch (Exception e) {
                    errors.add("行" + (i + 2) + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("读取Excel失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdUsers", createdUsers);
        result.put("createdAthletes", createdAthletes);
        result.put("skipped", skipped);
        result.put("errors", errors);
        log.info("导入名单: classId={}, 用户{}个, 运动员{}个", myClassId, createdUsers, createdAthletes);
        return ApiResponse.success("导入完成", result);
    }

    // ===== 手动添加单个运动员（学号/姓名/性别）→ 自动创建学生账号 + 运动员 =====
    @PostMapping("/athlete")
    @Transactional
    public ApiResponse<?> addAthlete(@RequestBody Map<String, Object> body) {
        String studentId = String.valueOf(body.getOrDefault("studentId", "")).trim();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String gender = mapGender(String.valueOf(body.getOrDefault("gender", "")).trim());
        if (studentId.isEmpty() || name.isEmpty()) {
            throw new RuntimeException("学号与姓名不能为空");
        }
        Long classId = getMyClassId();
        ClassInfo classInfo = classInfoRepository.findById(classId)
                .orElseThrow(() -> new RuntimeException("班级不存在，请先绑定班级"));

        if (athleteRepository.findByStudentId(studentId).isPresent()) {
            throw new RuntimeException("该学号已存在（" + studentId + "），请勿重复添加");
        }
        if (userRepository.findByUsername(studentId).isEmpty()) {
            userRepository.save(User.builder()
                    .username(studentId)
                    .password(passwordEncoder.encode(studentId))
                    .name(name)
                    .role("ROLE_STUDENT")
                    .status("active")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        int seq = 1;
        String number;
        do {
            number = numberRuleService.generateNumber(
                    Athlete.builder().name(name).gender(gender)
                            .grade(classInfo.getGrade()).classInfo(classInfo).build(),
                    classInfo, seq++);
        } while (athleteRepository.findByNumber(number).isPresent());

        Athlete athlete = athleteRepository.save(Athlete.builder()
                .name(name)
                .gender(gender)
                .grade(classInfo.getGrade())
                .classInfo(classInfo)
                .studentId(studentId)
                .number(number)
                .status("normal")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        log.info("班主任手动添加运动员: classId={}, studentId={}, name={}", classId, studentId, name);
        return ApiResponse.success("添加成功", athlete);
    }

    // ===== 获取本班运动员列表 =====
    @GetMapping("/athletes")
    @Transactional(readOnly = true)
    public ApiResponse<?> athletes(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Long classId = getMyClassId();
        List<Athlete> all = athleteRepository.findByClassInfoId(classId);
        int from = (page - 1) * size;
        int to = Math.min(from + size, all.size());
        List<Athlete> pageList = all.subList(Math.min(from, all.size()), to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", pageList);
        result.put("total", all.size());
        return ApiResponse.success(result);
    }

    // ===== 仪表盘 =====
    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public ApiResponse<?> dashboard() {
        Long classId = getMyClassId();
        ClassInfo ci = classInfoRepository.findById(classId).orElse(null);
        List<Athlete> athletes = athleteRepository.findByClassInfoId(classId);

        long athleteCount = athletes.size();
        List<Long> athleteIds = athletes.stream().map(Athlete::getId).toList();
        List<Registration> classRegs = athleteIds.isEmpty() ? List.of()
                : registrationRepository.findByAthleteIdIn(athleteIds);
        long approvedCount = classRegs.stream().filter(r -> "approved".equals(r.getStatus())).count();
        long awardCount = athleteIds.isEmpty() ? 0
                : resultRepository.findByAthleteIdIn(athleteIds).stream()
                .filter(r -> r.getTotalRank() != null && r.getTotalRank() <= 3).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("athleteCount", athleteCount);
        stats.put("className", ci != null ? ci.getName() : "");
        stats.put("registrationCount", (long) classRegs.size());
        stats.put("approvedCount", approvedCount);
        stats.put("awardCount", awardCount);

        List<Map<String, Object>> regList = classRegs.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())).limit(10)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("athleteName", r.getAthlete().getName());
                    m.put("eventName", r.getEvent().getName());
                    m.put("status", r.getStatus());
                    m.put("createdAt", r.getCreatedAt());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("registrations", regList);
        result.put("schedules", getSchedules(classId));
        return ApiResponse.success(result);
    }

    private List<Map<String, Object>> getSchedules(Long classId) {
        List<Map<String, Object>> list = new ArrayList<>();
        List<Athlete> athletes = athleteRepository.findByClassInfoId(classId);
        if (athletes.isEmpty()) return list;

        List<Long> athleteIds = athletes.stream().map(Athlete::getId).toList();
        Map<Long, String> athleteNames = athletes.stream()
                .collect(Collectors.toMap(Athlete::getId, Athlete::getName, (a, b) -> a));

        List<Registration> allRegs = registrationRepository.findActiveByAthleteIdIn(athleteIds);
        List<Registration> approvedRegs = allRegs.stream()
                .filter(r -> "approved".equals(r.getStatus()))
                .toList();

        List<Long> eventIds = approvedRegs.stream().map(r -> r.getEvent().getId()).distinct().toList();
        Map<Long, String> eventNames = approvedRegs.stream()
                .collect(Collectors.toMap(r -> r.getEvent().getId(), r -> r.getEvent().getName(), (a, b) -> a));

        Map<Long, List<Registration>> regsByAthlete = approvedRegs.stream()
                .collect(Collectors.groupingBy(r -> r.getAthlete().getId()));

        for (Athlete a : athletes) {
            List<Arrangement> allArrs = arrangementRepository.findByClassId(classId);
            List<Registration> aRegs = regsByAthlete.getOrDefault(a.getId(), List.of());
            for (Registration r : aRegs) {
                for (Arrangement arr : allArrs) {
                    if (arr.getEvent().getId().equals(r.getEvent().getId()) && arr.getAthlete().getId().equals(a.getId())) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("eventName", eventNames.getOrDefault(r.getEvent().getId(), r.getEvent().getName()));
                        m.put("athleteName", athleteNames.get(a.getId()));
                        m.put("heat", arr.getHeat());
                        m.put("laneNumber", arr.getLane());
                        com.sports.entity.Event ev = arr.getEvent();
                        m.put("eventType", ev != null && ev.getCategory() != null ? ev.getCategory() : "");
                        m.put("gender", ev != null && ev.getGenderLimit() != null ? ev.getGenderLimit() : "");
                        list.add(m);
                    }
                }
            }
        }
        return list;
    }

    // ===== 为运动员报名项目 =====
    @PostMapping("/register")
    @Transactional
    public ApiResponse<?> registerAthlete(@RequestBody Map<String, Object> body) {
        Object athleteIdObj = body.get("athleteId");
        Object eventIdObj = body.get("eventId");
        if (athleteIdObj == null || eventIdObj == null) {
            throw new RuntimeException("缺少运动员ID或项目ID");
        }
        Long athleteId = athleteIdObj instanceof Number n ? n.longValue() : Long.parseLong(athleteIdObj.toString());
        Long eventId = eventIdObj instanceof Number n ? n.longValue() : Long.parseLong(eventIdObj.toString());

        Athlete athlete = athleteRepository.findById(athleteId)
                .orElseThrow(() -> new RuntimeException("运动员不存在"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // 性别检查（归一化：兼容 男子组/M、女子组/F 双轨写法）
        if (!com.sports.common.GenderUtil.matches(event.getGenderLimit(), athlete.getGender())) {
            throw new RuntimeException("性别不符合项目要求");
        }

        // 每人最多N项（体育老师可配置，默认3）
        int maxPerAthlete = getConfigInt("maxEventsPerAthlete", 3);
        long athleteRegCount = registrationRepository.findByAthleteId(athleteId).stream()
                .filter(r -> !"withdrawn".equals(r.getStatus())).count();
        if (athleteRegCount >= maxPerAthlete)
            throw new RuntimeException("该运动员已报满" + maxPerAthlete + "项");

        // 每班每项最多N人（体育老师可配置，默认3）
        int maxPerClassEvent = getConfigInt("maxAthletesPerEvent", 3);
        long classEventCount = registrationRepository.countByClassAndEvent(
                athlete.getClassInfo().getId(), eventId);
        if (classEventCount >= maxPerClassEvent)
            throw new RuntimeException("该班级本项目报名已达上限(" + maxPerClassEvent + "人)");

        // 重复报名
        if (registrationRepository.existsByAthleteIdAndEventId(athleteId, eventId))
            throw new RuntimeException("该运动员已报名此项目");

        Registration reg = Registration.builder()
                .athlete(athlete).event(event).status("pending").source("onsite")
                .registrationTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        registrationRepository.save(reg);

        log.info("班主任现场报名(待审核): athlete={}, event={}", athlete.getName(), event.getName());
        return ApiResponse.success("报名已提交，等待体育老师审核", reg);
    }

    // ===== 取消报名 =====
    @DeleteMapping("/register/{id}")
    @Transactional
    public ApiResponse<?> cancelRegistration(@PathVariable Long id) {
        Registration reg = registrationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("报名记录不存在"));
        reg.setStatus("withdrawn");
        reg.setUpdatedAt(LocalDateTime.now());
        registrationRepository.save(reg);
        return ApiResponse.success("已取消", null);
    }

    // ===== 报名列表 =====
    @GetMapping("/registrations")
    @Transactional(readOnly = true)
    public ApiResponse<?> registrations() {
        Long classId = getMyClassId();
        List<Athlete> athletes = athleteRepository.findByClassInfoId(classId);
        if (athletes.isEmpty()) {
            return ApiResponse.success(Map.of("records", List.of(), "total", 0));
        }

        List<Long> athleteIds = athletes.stream().map(Athlete::getId).toList();
        Map<Long, Athlete> athleteMap = athletes.stream()
                .collect(Collectors.toMap(Athlete::getId, a -> a, (a, b) -> a));
        List<Registration> allRegs = registrationRepository.findByAthleteIdIn(athleteIds);

        List<Map<String, Object>> allRegMaps = allRegs.stream().map(r -> {
            Athlete a = athleteMap.get(r.getAthlete().getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("athleteName", a != null ? a.getName() : "");
            m.put("className", a != null && a.getClassInfo() != null ? a.getClassInfo().getName() : "");
            m.put("grade", a != null ? a.getGrade() : "");
            m.put("eventName", r.getEvent().getName());
            m.put("eventType", r.getEvent().getCategory());
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", allRegMaps);
        result.put("total", allRegMaps.size());
        return ApiResponse.success(result);
    }

    // ===== 导出报名表 =====
    @GetMapping("/registrations/export")
    @Transactional(readOnly = true)
    public void exportRegistrations(HttpServletResponse response) throws IOException {
        Long classId = getMyClassId();
        ClassInfo ci = classInfoRepository.findById(classId).orElse(null);
        String className = ci != null ? ci.getName() : "班级";
        List<Athlete> athletes = athleteRepository.findByClassInfoId(classId);

        List<List<String>> data = new ArrayList<>();
        data.add(List.of("学号", "姓名", "性别", "报名项目", "项目类型", "状态"));

        List<Long> athleteIds = athletes.stream().map(Athlete::getId).toList();
        List<Registration> allRegs = athleteIds.isEmpty() ? List.of()
                : registrationRepository.findByAthleteIdIn(athleteIds);
        Map<Long, List<Registration>> regsByAthlete = allRegs.stream()
                .collect(Collectors.groupingBy(r -> r.getAthlete().getId()));

        for (Athlete a : athletes) {
            List<Registration> regs = regsByAthlete.getOrDefault(a.getId(), List.of());
            if (regs.isEmpty()) {
                data.add(List.of(
                        a.getStudentId() != null ? a.getStudentId() : "",
                        a.getName(),
                        "M".equals(a.getGender()) ? "男" : "F".equals(a.getGender()) ? "女" : "",
                        "未报名", "", ""
                ));
            } else {
                for (Registration r : regs) {
                    data.add(List.of(
                            a.getStudentId() != null ? a.getStudentId() : "",
                            a.getName(),
                            "M".equals(a.getGender()) ? "男" : "F".equals(a.getGender()) ? "女" : "",
                            r.getEvent().getName(),
                            r.getEvent().getCategory(),
                            "withdrawn".equals(r.getStatus()) ? "已取消" : "approved".equals(r.getStatus()) ? "已通过" : "待审核"
                    ));
                }
            }
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String filename = URLEncoder.encode(className + "报名表.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);

        com.alibaba.excel.EasyExcel.write(response.getOutputStream())
                .sheet(className + "报名表")
                .doWrite(data);
    }

    // ===== 赛程查看 =====
    @GetMapping("/schedule")
    @Transactional(readOnly = true)
    public ApiResponse<?> schedule() {
        return ApiResponse.success(getSchedules(getMyClassId()));
    }

    // ===== 成绩查看 =====
    @GetMapping("/results")
    @Transactional(readOnly = true)
    public ApiResponse<?> results() {
        Long classId = getMyClassId();
        List<Athlete> athletes = athleteRepository.findByClassInfoId(classId);
        if (athletes.isEmpty()) {
            return ApiResponse.success(Map.of("records", List.of(), "summary", Map.of("totalPoints", 0, "medalCount", 0, "goldCount", 0, "silverCount", 0, "bronzeCount", 0)));
        }

        List<Long> athleteIds = athletes.stream().map(Athlete::getId).toList();
        Map<Long, Athlete> athleteMap = athletes.stream()
                .collect(Collectors.toMap(Athlete::getId, a -> a, (a, b) -> a));
        List<Result> allResults = resultRepository.findByAthleteIdIn(athleteIds);

        List<Map<String, Object>> list = new ArrayList<>();
        double totalPoints = 0;
        int gold = 0, silver = 0, bronze = 0;

        for (Result r : allResults) {
            Athlete a = athleteMap.get(r.getAthlete().getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("athleteName", a != null ? a.getName() : "");
            m.put("eventName", r.getEvent().getName());
            m.put("score", r.getRawTime());
            m.put("rank", r.getTotalRank());
            m.put("points", r.getScore());
            m.put("isRecord", Boolean.TRUE.equals(r.getIsRecord()));
            list.add(m);

            if (r.getScore() != null) totalPoints += r.getScore();
            if (r.getTotalRank() != null && r.getTotalRank() == 1) gold++;
            else if (r.getTotalRank() != null && r.getTotalRank() == 2) silver++;
            else if (r.getTotalRank() != null && r.getTotalRank() == 3) bronze++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPoints", totalPoints);
        summary.put("medalCount", gold + silver + bronze);
        summary.put("goldCount", gold);
        summary.put("silverCount", silver);
        summary.put("bronzeCount", bronze);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", list);
        result.put("summary", summary);
        return ApiResponse.success(result);
    }

    // ===== 可用项目列表（供班主任报名使用） =====
    @GetMapping("/events")
    @Transactional(readOnly = true)
    public ApiResponse<?> events() {
        return ApiResponse.success(eventRepository.findByIsEnabledTrueOrderBySortOrderAsc());
    }

    private static String mapGender(String v) {
        if (v == null) return null;
        return switch (v.trim()) {
            case "男","M","m" -> "M";
            case "女","F","f" -> "F";
            default -> v.trim();
        };
    }

    /** 从系统配置读取整数值，不存在则返回默认值 */
    private int getConfigInt(String key, int defaultValue) {
        return systemConfigRepository.findByConfigKey(key)
                .map(c -> {
                    try { return Integer.parseInt(c.getConfigValue()); }
                    catch (NumberFormatException e) { return defaultValue; }
                })
                .orElse(defaultValue);
    }
}
