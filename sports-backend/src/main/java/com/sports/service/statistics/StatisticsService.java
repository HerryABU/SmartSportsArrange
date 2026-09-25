package com.sports.service.statistics;

import com.sports.common.util.Grades;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.clazz.ClassInfo;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.entity.meet.SportsMeet;
import com.sports.entity.registration.Registration;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.clazz.ClassInfoRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.service.meet.MeetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatisticsService {

    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final ClassInfoRepository classInfoRepository;
    private final AthleteRepository athleteRepository;
    private final EventScheduleRepository scheduleRepository;
    private final MeetService meetService;

    /**
     * 获取报名统计
     */
    public Map<String, Object> getRegistrationStats() {
        List<Registration> allRegs = registrationRepository.findAll();
        List<Event> events = eventRepository.findAll();
        List<ClassInfo> classes = classInfoRepository.findAll();

        // 按项目统计。
        // 同名项目可能分布在多个年级组（如三个年级各有「100米」），若按 name 做 key 直接
        // put 会后者覆盖前者 → 只显示最后一个年级的数字。此处改为同名聚合（数量相加、名额累加），
        // 未配置名额上限(max_participants 为空/0)时 capacity 合计为 0，前端展示「不限」。
        Map<String, Map<String, Long>> byEvent = new LinkedHashMap<>();
        for (Event event : events) {
            String key = event.getName() != null && !event.getName().isBlank() ? event.getName() : "未命名项目";
            Map<String, Long> counts = byEvent.computeIfAbsent(key, k -> {
                Map<String, Long> c = new LinkedHashMap<>();
                c.put("total", 0L);
                c.put("approved", 0L);
                c.put("pending", 0L);
                c.put("rejected", 0L);
                c.put("cancelled", 0L);
                c.put("capacity", 0L);
                return c;
            });
            long approved = registrationRepository.countApprovedByEventId(event.getId());
            List<Registration> eventRegs = registrationRepository.findByEventId(event.getId());
            long pending = eventRegs.stream().filter(r -> "pending".equals(r.getStatus())).count();
            long rejected = eventRegs.stream().filter(r -> "rejected".equals(r.getStatus())).count();
            long cancelled = eventRegs.stream().filter(r -> "cancelled".equals(r.getStatus())).count();

            counts.merge("total", (long) eventRegs.size(), Long::sum);
            counts.merge("approved", approved, Long::sum);
            counts.merge("pending", pending, Long::sum);
            counts.merge("rejected", rejected, Long::sum);
            counts.merge("cancelled", cancelled, Long::sum);
            counts.merge("capacity", event.getMaxParticipants() != null
                    ? event.getMaxParticipants().longValue() : 0L, Long::sum);
        }

        // 修复（启发式层）：原实现对每个班级都全量扫描 allRegs（O(classes × regs)），年级统计又重复一遍。
        // 改为一次性按 classId 分组计数，班级与年级统计直接查这张表。
        Map<Long, Long> countByClass = allRegs.stream()
                .filter(r -> r.getAthlete() != null && r.getAthlete().getClassInfo() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getAthlete().getClassInfo().getId(),
                        Collectors.counting()));

        // 按班级统计
        Map<String, Long> byClass = new LinkedHashMap<>();
        for (ClassInfo ci : classes) {
            byClass.put(ci.getName(), countByClass.getOrDefault(ci.getId(), 0L));
        }

        // 按年级统计（供报表「各年级参赛人数」）
        Map<String, Long> byGrade = new LinkedHashMap<>();
        for (ClassInfo ci : classes) {
            String g = ci.getGrade() != null ? ci.getGrade() : "未分年级";
            byGrade.merge(g, countByClass.getOrDefault(ci.getId(), 0L), Long::sum);
        }

        // 按状态统计
        long totalPending = allRegs.stream().filter(r -> "pending".equals(r.getStatus())).count();
        long totalApproved = allRegs.stream().filter(r -> "approved".equals(r.getStatus())).count();
        long totalRejected = allRegs.stream().filter(r -> "rejected".equals(r.getStatus())).count();
        long totalCancelled = allRegs.stream().filter(r -> "cancelled".equals(r.getStatus())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRegistrations", allRegs.size());
        result.put("totalClasses", classes.size());
        result.put("totalAthletes", athleteRepository.count());
        result.put("totalEvents", events.size());
        result.put("total", allRegs.size());
        result.put("byStatus", Map.of(
                "pending", totalPending,
                "approved", totalApproved,
                "rejected", totalRejected,
                "cancelled", totalCancelled
        ));
        result.put("byEvent", byEvent);
        result.put("byClass", byClass);
        result.put("byGrade", byGrade);

        return result;
    }

    /**
     * 获取成绩统计
     */
    public Map<String, Object> getScoreStats() {
        List<Result> allResults = resultRepository.findAllValid();
        List<Event> events = eventRepository.findAll();

        // 按项目统计
        Map<String, Map<String, Object>> byEvent = new LinkedHashMap<>();
        for (Event event : events) {
            List<Result> eventResults = allResults.stream()
                    .filter(r -> r.getEvent().getId().equals(event.getId()))
                    .collect(Collectors.toList());

            if (eventResults.isEmpty()) continue;

            Map<String, Object> eventStats = new LinkedHashMap<>();
            eventStats.put("total", eventResults.size());
            eventStats.put("hasRanking", eventResults.stream().anyMatch(r -> r.getTotalRank() != null));

            // 最高分
            eventResults.stream()
                    .filter(r -> r.getScore() != null)
                    .max(Comparator.comparing(Result::getScore))
                    .ifPresent(r -> eventStats.put("maxScore", r.getScore()));

            // 破纪录数
            long records = eventResults.stream().filter(Result::getIsRecord).count();
            eventStats.put("records", records);

            byEvent.put(event.getName(), eventStats);
        }

        // 总分统计
        double totalScore = allResults.stream()
                .filter(r -> r.getScore() != null)
                .mapToDouble(Result::getScore)
                .sum();

        long totalRecords = allResults.stream().filter(Result::getIsRecord).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalResults", allResults.size());
        result.put("totalScore", Math.round(totalScore * 100.0) / 100.0);
        result.put("totalRecords", totalRecords);
        result.put("byEvent", byEvent);

        return result;
    }

    /**
     * 生成秩序册数据
     */
    public Map<String, Object> generateOrderBook(String grade) {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();

        // 项目分组
        Map<String, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();
        byCategory.put("径赛", new ArrayList<>());
        byCategory.put("田赛", new ArrayList<>());
        byCategory.put("其他", new ArrayList<>());

        for (Event event : events) {
            Map<String, Object> eventInfo = new LinkedHashMap<>();
            eventInfo.put("id", event.getId());
            eventInfo.put("name", event.getName());
            eventInfo.put("code", event.getCode());
            eventInfo.put("genderLimit", event.getGenderLimit());
            eventInfo.put("distanceType", event.getDistanceType());
            eventInfo.put("gradeGroup", event.getGradeGroup());
            eventInfo.put("registrationStart", event.getRegistrationStart());
            eventInfo.put("registrationEnd", event.getRegistrationEnd());
            eventInfo.put("record", event.getRecord());

            // 获取该项目已编排信息
            List<Arrangement> arrangements = arrangementRepository.findByEventId(event.getId());
            eventInfo.put("arrangedCount", arrangements.size());
            eventInfo.put("heatCount", arrangements.stream()
                    .map(Arrangement::getHeat)
                    .distinct()
                    .count());

            String category = event.getCategory();
            if (category == null) category = "其他";
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(eventInfo);
        }

        // 班级信息
        List<Map<String, Object>> classList = new ArrayList<>();
        for (ClassInfo ci : classes) {
            Map<String, Object> classInfo = new LinkedHashMap<>();
            classInfo.put("id", ci.getId());
            classInfo.put("name", ci.getName());
            classInfo.put("grade", ci.getGrade());
            classInfo.put("teacherName", ci.getTeacherName());
            classInfo.put("studentCount", ci.getStudentCount());
            classList.add(classInfo);
        }

        // ============ 统一预览 sections（前端逐 section 渲染表格） ============
        List<Map<String, Object>> sections = new ArrayList<>();

        // 1) 竞赛日程：来自一键编排生成的 event_schedule
        List<EventSchedule> scheds = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();
        List<Map<String, Object>> scheduleRows = new ArrayList<>();
        for (EventSchedule s : scheds) {
            if (grade != null && !grade.isBlank()) {
                Event e0 = s.getEvent();
                boolean evMatch = e0 != null && Grades.same(grade, e0.getGradeGroup());
                boolean schMatch = Grades.same(grade, s.getGrade());
                if (!evMatch && !schMatch) continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", s.getDay());
            m.put("date", s.getScheduleDate());
            m.put("slot", s.getTimeSlot());
            m.put("time", (s.getStartTime() == null ? "" : s.getStartTime()) + "~" + (s.getEndTime() == null ? "" : s.getEndTime()));
            m.put("eventName", s.getEvent() != null ? s.getEvent().getName() : "-");
            m.put("gender", s.getEvent() != null ? s.getEvent().getGenderLimit() : "");
            m.put("grade", s.getGrade());
            m.put("venue", s.getVenue());
            scheduleRows.add(m);
        }
        if (!scheduleRows.isEmpty()) {
            sections.add(Map.of(
                    "title", "竞赛日程（" + (grade == null || grade.isBlank() ? "全部年级" : grade) + "）",
                    "columns", List.of(
                            Map.of("prop", "day", "label", "天次", "width", 60),
                            Map.of("prop", "date", "label", "日期", "width", 110),
                            Map.of("prop", "slot", "label", "时段", "width", 80),
                            Map.of("prop", "time", "label", "时间", "width", 130),
                            Map.of("prop", "eventName", "label", "项目", "minWidth", 130),
                            Map.of("prop", "gender", "label", "性别", "width", 80),
                            Map.of("prop", "grade", "label", "年级", "width", 90),
                            Map.of("prop", "venue", "label", "场地", "width", 100)),
                    "items", scheduleRows));
        }

        // 2) 项目分册（径赛 / 田赛 / 其他）
        List<String> catOrder = List.of("径赛", "田赛", "其他");
        List<Map<String, Object>> projectColumns = List.of(
                Map.of("prop", "name", "label", "项目名称"),
                Map.of("prop", "code", "label", "编码", "width", 100),
                Map.of("prop", "genderLimit", "label", "性别", "width", 90),
                Map.of("prop", "arrangedCount", "label", "已编排人数", "width", 100),
                Map.of("prop", "heatCount", "label", "组数", "width", 70));
        for (String cat : catOrder) {
            List<Map<String, Object>> items = byCategory.get(cat);
            if (items == null || items.isEmpty()) continue;
            sections.add(Map.of("title", cat + "项目", "columns", projectColumns, "items", items));
        }

        // 3) 参赛班级
        sections.add(Map.of(
                "title", "参赛班级（" + classes.size() + " 个）",
                "columns", List.of(
                        Map.of("prop", "name", "label", "班级名称"),
                        Map.of("prop", "grade", "label", "年级", "width", 90),
                        Map.of("prop", "teacherName", "label", "班主任", "width", 100),
                        Map.of("prop", "studentCount", "label", "人数", "width", 70)),
                "items", classList));

        // 4) 各编排项目：分组道次名单（决赛优先，无决赛则用预赛）
        List<Map<String, Object>> laneColumns = List.of(
                Map.of("prop", "heat", "label", "组次", "width", 60),
                Map.of("prop", "lane", "label", "道次", "width", 60),
                Map.of("prop", "number", "label", "号码", "width", 90),
                Map.of("prop", "name", "label", "姓名", "width", 100),
                Map.of("prop", "className", "label", "班级", "width", 120),
                Map.of("prop", "grade", "label", "年级", "width", 90));
        for (Event event : events) {
            List<Arrangement> all = arrangementRepository.findByEventId(event.getId());
            if (all.isEmpty()) continue;
            boolean hasFinal = all.stream().anyMatch(a -> "final".equals(a.getRound()));
            List<Arrangement> pool = hasFinal
                    ? all.stream().filter(a -> "final".equals(a.getRound())).collect(Collectors.toList())
                    : all;
            pool.sort(Comparator
                    .comparingInt((Arrangement a) -> a.getHeat() == null ? 0 : a.getHeat())
                    .thenComparingInt(a -> a.getLane() == null ? 0 : a.getLane()));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Arrangement a : pool) {
                Athlete at = a.getAthlete();
                if (at == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("heat", a.getHeat());
                row.put("lane", a.getLane());
                row.put("number", at.getNumber());
                row.put("name", at.getName());
                row.put("className", at.getClassInfo() != null ? at.getClassInfo().getName() : "-");
                row.put("grade", at.getGrade());
                rows.add(row);
            }
            if (rows.isEmpty()) continue;
            sections.add(Map.of(
                    "title", event.getName() + (event.getGenderLimit() == null ? "" : "（" + event.getGenderLimit() + "）"),
                    "columns", laneColumns,
                    "items", rows));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "运动会秩序册");
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.put("events", byCategory);
        result.put("classes", classList);
        result.put("sections", sections);
        result.put("totalEvents", events.size());
        result.put("totalClasses", classes.size());
        return result;
    }

    /**
     * 生成成绩册数据
     */
    public Map<String, Object> generateResultBook() {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<Result> allResults = resultRepository.findAllValid();

        List<Map<String, Object>> eventResults = new ArrayList<>();
        Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();

        for (Event event : events) {
            List<Result> eventResultList = allResults.stream()
                    .filter(r -> r.getEvent().getId().equals(event.getId()))
                    .sorted(Comparator.comparing(Result::getTotalRank,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());

            if (eventResultList.isEmpty()) continue;

            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("eventId", event.getId());
            eventData.put("eventName", event.getName());
            eventData.put("category", event.getCategory());
            eventData.put("record", event.getRecord());

            List<Map<String, Object>> rankings = new ArrayList<>();
            for (Result r : eventResultList) {
                Map<String, Object> rankData = new LinkedHashMap<>();
                rankData.put("rank", r.getTotalRank());
                rankData.put("athleteName", r.getAthlete().getName());
                rankData.put("number", r.getAthlete().getNumber());
                rankData.put("className", r.getAthlete().getClassInfo() != null
                        ? r.getAthlete().getClassInfo().getName() : "未知");
                rankData.put("grade", r.getAthlete().getGrade());
                rankData.put("rawTime", r.getRawTime());
                rankData.put("score", r.getScore());
                rankData.put("isRecord", r.getIsRecord());
                rankings.add(rankData);

                // 收集破纪录信息
                if (Boolean.TRUE.equals(r.getIsRecord())) {
                    records.computeIfAbsent(event.getName(), k -> new ArrayList<>()).add(rankData);
                }
            }

            eventData.put("rankings", rankings);
            eventData.put("totalRanked", rankings.size());
            eventResults.add(eventData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "运动会成绩册");
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.put("eventResults", eventResults);
        result.put("records", records);
        result.put("totalEvents", eventResults.size());

        return result;
    }

    /**
     * 获取待办事项统计（Dashboard用）
     */
    public Map<String, Object> getTodoStats() {
        long pendingRegistrations = registrationRepository.countPending();
        long totalEvents = eventRepository.findByIsEnabledTrue().size();
        long arrangedEvents = arrangementRepository.findDistinctEventCount();
        long unarrangedEvents = totalEvents - arrangedEvents;
        long pendingScores = Math.max(0, arrangedEvents - resultRepository.countDistinctEventWithResults());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingRegistrations", pendingRegistrations);
        result.put("unarrangedEvents", unarrangedEvents);
        result.put("pendingScores", pendingScores);
        result.put("completed", totalEvents + pendingRegistrations);
        return result;
    }

    /**
     * 获取报名进度（按年级，Dashboard用）
     *
     * <p>口径与「班级管理」一致：分母取该年级<b>花名册真实运动员人数</b>
     * （不依赖手填/陈旧的 student_count 列，历史数据该列常为 0 导致进度恒 0%），
     * 分子取该年级<b>已有审核通过报名</b>的去重运动员数。单位统一为「人」，
     * 避免「报名人次 / 总人数」口径错配（人次会超过总人数）。
     *
     * <p>2026-09-25 修订：<b>未报名（一个项目都没报）的运动员不计入报名进度分母</b>——
     * 分母改为「该年级至少有一个报名记录的去重运动员数」，仅作「已报名学生中审核通过占比」
     * 语义；rosterTotal 仍保留在返回里作为「在册总数（含未报名）」参考，供前端按需展示。
     */
    public List<Map<String, Object>> getRegistrationProgress() {
        List<ClassInfo> classes = classInfoRepository.findByIsParticipatingTrue();

        // 该年级在册运动员总数（剔除软删）——仅用于校验 grade 归属与提供参考值
        Map<String, Integer> rosterByGrade = new LinkedHashMap<>();
        for (ClassInfo ci : classes) {
            String grade = ci.getGrade() != null ? ci.getGrade() : "未知";
            long roster = athleteRepository.findByClassInfoId(ci.getId()).stream()
                    .filter(a -> a.getDeletedAt() == null)
                    .count();
            rosterByGrade.merge(grade, (int) roster, Integer::sum);
        }

        // 分母：该年级「至少有一个报名记录」的去重运动员（未报名者不计入报名进度）
        Map<String, Set<Long>> registeredAthleteByGrade = new HashMap<>();
        for (Registration reg : registrationRepository.findAll()) {
            Athlete a = reg.getAthlete();
            if (a == null || a.getDeletedAt() != null) continue;
            String grade = a.getGrade();
            if (grade == null || !rosterByGrade.containsKey(grade)) continue;
            registeredAthleteByGrade.computeIfAbsent(grade, k -> new HashSet<>()).add(a.getId());
        }

        // 分子：该年级已有审核通过报名的去重运动员数
        Map<String, Set<Long>> approvedAthleteByGrade = new HashMap<>();
        for (Registration reg : registrationRepository.findByStatus("approved")) {
            Athlete a = reg.getAthlete();
            if (a == null || a.getDeletedAt() != null) continue;
            String grade = a.getGrade();
            if (grade == null || !rosterByGrade.containsKey(grade)) continue;
            approvedAthleteByGrade.computeIfAbsent(grade, k -> new HashSet<>()).add(a.getId());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        rosterByGrade.forEach((grade, rosterTotal) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", grade);
            item.put("total", registeredAthleteByGrade.getOrDefault(grade, Set.of()).size());
            item.put("registered", approvedAthleteByGrade.getOrDefault(grade, Set.of()).size());
            item.put("rosterTotal", rosterTotal);
            result.add(item);
        });
        return result;
    }

    /**
     * 获取今日赛程（Dashboard用）
     */
    public List<Map<String, Object>> getTodaySchedule() {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();
        List<Map<String, Object>> schedule = new ArrayList<>();
        int id = 1;

        for (Event event : events) {
            List<Arrangement> arrangements = arrangementRepository.findByEventId(event.getId());
            if (arrangements.isEmpty()) continue;

            long distinctHeats = arrangements.stream().map(Arrangement::getHeat).distinct().count();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id++);
            item.put("eventName", event.getName());
            item.put("gender", event.getGenderLimit() != null ? event.getGenderLimit() : "");
            item.put("heat", (int) distinctHeats + " 组");
            item.put("time", event.getRegistrationEnd() != null ?
                event.getRegistrationEnd().toLocalTime().toString().substring(0, 5) : "待定");
            item.put("location", "田径场");
            item.put("statusCode", arrangements.isEmpty() ? "preparing" : "in_progress");
            item.put("status", arrangements.isEmpty() ? "待编排" : "进行中");
            schedule.add(item);
        }

        return schedule;
    }

    /**
     * 跨届进步榜（同一学生跨多届同一项目的排名进步）。
     *
     * <p>口径：取每位运动员在指定项目（或全部项目）上，<b>最近两届</b>的有效成绩，
     * 比较名次变化 {@code 进步 = 上一届名次 − 本届名次}（正数=名次提前=进步）。
     * 仅需 {@code totalRank} 非空的有效成绩。</p>
     *
     * @param eventId 指定项目（null=汇总：跨项目名次进步之和，体现综合进步最大的学生）
     * @param limit   返回条数（null=全部）
     */
    public Map<String, Object> getProgressLeaderboard(Long eventId, Integer limit) {
        List<SportsMeet> meets = meetService.list();   // year DESC
        Map<Long, Integer> meetYear = new LinkedHashMap<>();
        for (SportsMeet m : meets) meetYear.put(m.getId(), m.getYear());
        Map<Long, SportsMeet> meetById = new LinkedHashMap<>();
        for (SportsMeet m : meets) meetById.put(m.getId(), m);

        List<Result> results = eventId != null
                ? resultRepository.findByEventId(eventId)
                : resultRepository.findAll();

        // 运动员 → 项目 → 成绩列表
        Map<Long, Map<Long, List<Result>>> byAthleteEvent = new LinkedHashMap<>();
        for (Result r : results) {
            if (r.getMeet() == null || r.getTotalRank() == null) continue;
            if (r.getStatus() != null && !"valid".equals(r.getStatus())) continue;
            if (r.getAthlete() == null || r.getEvent() == null) continue;
            byAthleteEvent
                    .computeIfAbsent(r.getAthlete().getId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.getEvent().getId(), k -> new ArrayList<>())
                    .add(r);
        }

        // 单项目模式：直接产出每个人的进步明细
        if (eventId != null) {
            List<Map<String, Object>> ranked = new ArrayList<>();
            for (Map.Entry<Long, Map<Long, List<Result>>> ae : byAthleteEvent.entrySet()) {
                for (Map.Entry<Long, List<Result>> e : ae.getValue().entrySet()) {
                    Map<String, Object> row = buildProgressRow(e.getValue(), meetYear, meetById);
                    if (row != null) ranked.add(row);
                }
            }
            ranked.sort((a, b) -> Integer.compare((Integer) b.get("rankImprovement"), (Integer) a.get("rankImprovement")));
            if (limit != null && ranked.size() > limit) ranked = ranked.subList(0, limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("eventId", eventId);
            out.put("ranked", ranked);
            out.put("meets", meets.stream().map(m -> Map.of(
                    "id", m.getId(), "name", m.getName(), "year", m.getYear())).toList());
            return out;
        }

        // 汇总模式：跨项目名次进步之和
        List<Map<String, Object>> overall = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, List<Result>>> ae : byAthleteEvent.entrySet()) {
            int total = 0;
            int events = 0;
            String name = null, className = null, grade = null;
            for (Map.Entry<Long, List<Result>> e : ae.getValue().entrySet()) {
                Map<String, Object> row = buildProgressRow(e.getValue(), meetYear, meetById);
                if (row != null) {
                    total += (Integer) row.get("rankImprovement");
                    events++;
                    if (name == null) {
                        name = (String) row.get("athleteName");
                        className = (String) row.get("className");
                        grade = (String) row.get("grade");
                    }
                }
            }
            if (events > 0) {
                Map<String, Object> agg = new LinkedHashMap<>();
                agg.put("athleteId", ae.getKey());
                agg.put("athleteName", name);
                agg.put("className", className);
                agg.put("grade", grade);
                agg.put("events", events);
                agg.put("totalImprovement", total);
                overall.add(agg);
            }
        }
        overall.sort((a, b) -> Integer.compare((Integer) b.get("totalImprovement"), (Integer) a.get("totalImprovement")));
        if (limit != null && overall.size() > limit) overall = overall.subList(0, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", null);
        out.put("ranked", overall);
        out.put("meets", meets.stream().map(m -> Map.of(
                "id", m.getId(), "name", m.getName(), "year", m.getYear())).toList());
        return out;
    }

    /** 取最近两届（按 meet.year 升序后取末两）计算进步；不足两届返回 null */
    private Map<String, Object> buildProgressRow(List<Result> list, Map<Long, Integer> meetYear,
                                                Map<Long, SportsMeet> meetById) {
        if (list.size() < 2) return null;
        list.sort(Comparator.comparingInt(r -> meetYear.getOrDefault(r.getMeet().getId(), 0)));
        Result prev = list.get(list.size() - 2);
        Result cur = list.get(list.size() - 1);
        Integer prevRank = prev.getTotalRank();
        Integer curRank = cur.getTotalRank();
        if (prevRank == null || curRank == null) return null;
        int improvement = prevRank - curRank;
        SportsMeet prevMeet = meetById.get(prev.getMeet().getId());
        SportsMeet curMeet = meetById.get(cur.getMeet().getId());
        Map<String, Object> row = new LinkedHashMap<>();
        Athlete a = cur.getAthlete();
        row.put("athleteId", a.getId());
        row.put("athleteName", a.getName());
        row.put("className", a.getClassInfo() != null ? a.getClassInfo().getName() : "未知");
        row.put("grade", a.getGrade());
        row.put("eventId", cur.getEvent().getId());
        row.put("eventName", cur.getEvent().getName());
        row.put("prevMeet", prevMeet != null ? prevMeet.getName() : null);
        row.put("prevYear", prevMeet != null ? prevMeet.getYear() : null);
        row.put("curMeet", curMeet != null ? curMeet.getName() : null);
        row.put("curYear", curMeet != null ? curMeet.getYear() : null);
        row.put("prevRank", prevRank);
        row.put("curRank", curRank);
        row.put("rankImprovement", improvement);
        return row;
    }

    // ============ Controller 兼容别名 + export ============

    /** Controller 别名 */
    public Map<String, Object> registrationStats() {
        return getRegistrationStats();
    }

    /** Controller 别名 */
    public Map<String, Object> scoreStats() {
        return getScoreStats();
    }

    /** 导出统计 */
    public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        Map<String, Object> data = getRegistrationStats();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "报名统计_" + java.time.LocalDateTime.now().toString().replace(":", "-") + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                + ";filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        try (java.io.OutputStream out = response.getOutputStream()) {
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            // Sheet1: 按项目统计
            rows.add(java.util.List.of("项目名称", "总报名数", "已通过", "待审核"));
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Long>> byEvent = (Map<String, Map<String, Long>>) data.get("byEvent");
            if (byEvent != null) {
                for (Map.Entry<String, Map<String, Long>> entry : byEvent.entrySet()) {
                    Map<String, Long> counts = entry.getValue();
                    rows.add(java.util.List.of(
                        entry.getKey(),
                        String.valueOf(counts.getOrDefault("total", 0L)),
                        String.valueOf(counts.getOrDefault("approved", 0L)),
                        String.valueOf(counts.getOrDefault("pending", 0L))
                    ));
                }
            }
            java.util.List<java.util.List<String>> headCols = rows.get(0).stream()
                    .map(java.util.List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out)
                .head(headCols)
                .sheet("报名统计").doWrite(rows.subList(1, rows.size()));
            log.info("导出统计数据完成");
        }
    }
}
