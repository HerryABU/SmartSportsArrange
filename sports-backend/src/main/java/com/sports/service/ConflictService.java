package com.sports.service;

import com.sports.entity.Arrangement;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.AthleteRepository;
import com.sports.repository.EventScheduleRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * B06 / U05：兼项冲突检测。
 *
 * <p>同一运动员报名了多个项目，若其中两个项目的赛程时间窗在「同一天且重叠（或间隔过小）」，
 * 则判定为兼项冲突——该运动员可能被同时叫到两个场地。</p>
 *
 * <p>本版相较早期实现修正/增强了四点，都是会直接影响现场判断的：</p>
 * <ol>
 *   <li><b>间隔判定对称化（修 bug）</b>：旧实现用 {@code |y.start - x.end|} 判「间隔过小」，
 *       只覆盖「后一项排在前一项之后」的情形；当检索顺序反过来（后一项的起点早于前一项的终点）
 *       该式会算出一个很大的数而被判为无冲突 → <b>漏报</b>。现改为对称间隔
 *       {@code gap = max(yStart - xEnd, xStart - yEnd)}；</li>
 *   <li><b>严重度分级</b>：时间窗重叠 = 严重（运动员会同时被叫到两个场地）；
 *       仅间隔不足 = 一般；</li>
 *   <li><b>根因识别</b>：两项落在同一场地且同一开始时刻 → 类型标为「同场地同时开赛」，
 *       这是现场最典型的撞车（如跳远与铅球都在田赛A区 08:00 开始）；</li>
 *   <li><b>去重与可行动建议</b>：同一 (运动员 × 项目对 × 时间对) 只保留一条；
 *       建议按根因给出可执行动作（换场地 / 错开时段 / 预留缓冲），而非泛泛一句「建议错开」。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictService {

    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository eventScheduleRepository;
    private final AthleteRepository athleteRepository;

    /**
     * 相邻项目「结束-开始」间隔小于该值（分钟）也视为冲突。
     *
     * <p>U23/B20：改为 public —— 排程端（{@link ScheduleService}）要**事前规避**兼项冲突，
     * 必须与检测端用同一个缓冲口径，否则「排的时候觉得不冲突、检出来又冲突」。</p>
     */
    public static final int CONFLICT_BUFFER_MIN = 15;

    public static final String SEVERITY_BLOCKER = "严重";
    public static final String SEVERITY_WARN = "一般";

    public static final String TYPE_SAME_VENUE = "同场地同时开赛";
    public static final String TYPE_OVERLAP = "时间重叠";
    public static final String TYPE_TIGHT_GAP = "间隔不足";

    /**
     * 检测全部运动员的兼项冲突。
     *
     * @return 冲突清单（按严重度、再按运动员名排序），元素含
     *         athleteId/athleteName/athleteNumber/severity/type/eventA/eventB/
     *         windowA/windowB/gapMinutes/suggestion
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> detectConflicts() {
        // 1) 项目 -> 赛程时间窗（一个项目可能有多条：预赛 + 决赛，或多天）
        Map<Long, List<EventSchedule>> eventSchedules = new HashMap<>();
        for (EventSchedule s : eventScheduleRepository.findAll()) {
            if (s.getEvent() == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            eventSchedules.computeIfAbsent(s.getEvent().getId(), k -> new ArrayList<>()).add(s);
        }

        // 2) 运动员 -> 参赛项目集合（来自编排表，天然只含真正被排入的运动员）
        Map<Long, Set<Long>> athleteEvents = new HashMap<>();
        for (Arrangement a : arrangementRepository.findAll()) {
            if (a.getAthlete() == null || a.getEvent() == null) continue;
            athleteEvents.computeIfAbsent(a.getAthlete().getId(), k -> new HashSet<>()).add(a.getEvent().getId());
        }

        // 3) 两两比较同一运动员的不同项目时间窗；同一 (运动员, 项目对, 时间对) 去重
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> entry : athleteEvents.entrySet()) {
            Long aid = entry.getKey();
            List<Long> evs = new ArrayList<>(entry.getValue());
            if (evs.size() < 2) continue;
            Map<String, Object> ath = athleteNameNumber(aid);
            for (int i = 0; i < evs.size(); i++) {
                for (int j = i + 1; j < evs.size(); j++) {
                    List<EventSchedule> sa = eventSchedules.get(evs.get(i));
                    List<EventSchedule> sb = eventSchedules.get(evs.get(j));
                    if (sa == null || sb == null) continue;
                    for (EventSchedule x : sa) {
                        for (EventSchedule y : sb) {
                            Gap gap = gapOf(x, y);
                            if (gap == null) continue;      // 不同天 / 无法解析
                            String key = aid + "|" + x.getId() + "|" + y.getId();
                            if (!seen.add(key)) continue;
                            conflicts.add(buildConflict(aid, ath, x, y, gap));
                        }
                    }
                }
            }
        }

        conflicts.sort(Comparator
                .comparingInt((Map<String, Object> c) -> SEVERITY_BLOCKER.equals(c.get("severity")) ? 0 : 1)
                .thenComparing(c -> String.valueOf(c.get("athleteName")))
                .thenComparing(c -> String.valueOf(c.get("windowA"))));
        log.info("兼项冲突检测完成: 共 {} 处（严重 {} 处）", conflicts.size(), countSevere(conflicts));
        return conflicts;
    }

    /** 冲突汇总统计，供接口与导出附带展示 */
    public Map<String, Object> summary(List<Map<String, Object>> conflicts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", conflicts.size());
        m.put("blocker", countSevere(conflicts));
        m.put("warn", conflicts.size() - countSevere(conflicts));
        Set<Long> athletes = new HashSet<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Map<String, Object> c : conflicts) {
            if (c.get("athleteId") instanceof Number n) athletes.add(n.longValue());
            String t = String.valueOf(c.get("type"));
            byType.merge(t, 1, Integer::sum);
        }
        m.put("athleteCount", athletes.size());
        m.put("byType", byType);
        m.put("bufferMinutes", CONFLICT_BUFFER_MIN);
        return m;
    }

    private int countSevere(List<Map<String, Object>> conflicts) {
        int n = 0;
        for (Map<String, Object> c : conflicts) {
            if (SEVERITY_BLOCKER.equals(c.get("severity"))) n++;
        }
        return n;
    }

    /** 冲突判定结果：类型 + 严重度 + 实际间隔（分钟，重叠时为 0） */
    private static class Gap {
        final String type;
        final String severity;
        final int gapMinutes;

        Gap(String type, String severity, int gapMinutes) {
            this.type = type;
            this.severity = severity;
            this.gapMinutes = gapMinutes;
        }
    }

    /**
     * 判定两个赛程条目是否冲突；不同天返回 null。
     *
     * <p>对称间隔：{@code gap = max(yStart - xEnd, xStart - yEnd)}。两段不重叠时，
     * 该式恰好等于真实间隔（无论谁在前）；重叠时为负值。</p>
     */
    private Gap gapOf(EventSchedule x, EventSchedule y) {
        int dx = x.getDay() == null ? 1 : x.getDay();
        int dy = y.getDay() == null ? 1 : y.getDay();
        if (dx != dy) return null; // 不同天，不可能冲突

        int xs = toMin(x.getStartTime()), xe = toMin(x.getEndTime());
        int ys = toMin(y.getStartTime()), ye = toMin(y.getEndTime());
        if (xe <= xs) xe = xs + 1;   // 容错：结束时间缺失/非法时按 1 分钟处理
        if (ye <= ys) ye = ys + 1;

        boolean overlapping = xs < ye && ys < xe;
        int gap = Math.max(ys - xe, xs - ye);   // 对称间隔

        if (overlapping) {
            boolean sameVenueSameStart = sameText(x.getVenue(), y.getVenue()) && xs == ys;
            return new Gap(sameVenueSameStart ? TYPE_SAME_VENUE : TYPE_OVERLAP,
                    SEVERITY_BLOCKER, 0);
        }
        if (gap < CONFLICT_BUFFER_MIN) {
            return new Gap(TYPE_TIGHT_GAP, SEVERITY_WARN, gap);
        }
        return null;
    }

    private Map<String, Object> buildConflict(Long aid, Map<String, Object> ath,
                                              EventSchedule x, EventSchedule y, Gap gap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("athleteId", aid);
        m.put("athleteName", ath.get("name"));
        m.put("athleteNumber", ath.get("number"));
        m.put("severity", gap.severity);
        m.put("type", gap.type);
        m.put("gapMinutes", gap.gapMinutes);
        m.put("eventA", eventSummary(x));
        m.put("eventB", eventSummary(y));
        m.put("windowA", windowStr(x));
        m.put("windowB", windowStr(y));
        m.put("suggestion", suggestion(ath, x, y, gap));
        return m;
    }

    /** 按根因给出可执行的调整动作，避免「请错开」这种无法落地的建议 */
    private String suggestion(Map<String, Object> ath, EventSchedule x, EventSchedule y, Gap gap) {
        String name = String.valueOf(ath.get("name"));
        String ea = nameOf(x);
        String eb = nameOf(y);
        if (TYPE_SAME_VENUE.equals(gap.type)) {
            return String.format("「%s」同时报了「%s」与「%s」，两者同在 %s、同在 %s 开始："
                            + "建议把「%s」调整到其它场地（或把两项拆到不同时段），否则该运动员只能二选一。",
                    name, ea, eb, x.getVenue(), x.getStartTime(), eb);
        }
        if (TYPE_OVERLAP.equals(gap.type)) {
            return String.format("「%s」的「%s」（%s~%s @%s）与「%s」（%s~%s @%s）时间重叠："
                            + "建议把「%s」整体后移到「%s」结束之后，或更换该项场地/时段。",
                    name, ea, x.getStartTime(), x.getEndTime(), x.getVenue(),
                    eb, y.getStartTime(), y.getEndTime(), y.getVenue(), eb, ea);
        }
        return String.format("「%s」的「%s」与「%s」之间仅间隔 %d 分钟（小于 %d 分钟缓冲）："
                        + "建议拉开两项间隔，给检录、换场地留出时间。",
                name, ea, eb, gap.gapMinutes, CONFLICT_BUFFER_MIN);
    }

    private Map<String, Object> eventSummary(EventSchedule s) {
        Event e = s.getEvent();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e != null && e.getCode() != null ? e.getCode() : "");
        m.put("name", e != null && e.getName() != null ? e.getName() : "");
        m.put("venue", s.getVenue() != null ? s.getVenue() : "");
        m.put("round", s.getRound() != null ? s.getRound() : "final");
        m.put("startTime", s.getStartTime());
        m.put("endTime", s.getEndTime());
        return m;
    }

    private String nameOf(EventSchedule s) {
        Event e = s.getEvent();
        return e != null && e.getName() != null ? e.getName() : "未知项目";
    }

    private String windowStr(EventSchedule s) {
        int day = s.getDay() == null ? 1 : s.getDay();
        return "第" + day + "天 " + s.getStartTime() + "~" + s.getEndTime()
                + (s.getVenue() != null ? " @" + s.getVenue() : "");
    }

    private Map<String, Object> athleteNameNumber(Long aid) {
        Map<String, Object> m = new LinkedHashMap<>();
        athleteRepository.findById(aid).ifPresent(a -> {
            m.put("name", a.getName());
            m.put("number", a.getNumber());
        });
        if (m.isEmpty()) {
            m.put("name", "未知");
            m.put("number", "");
        }
        return m;
    }

    /** B06/U05：冲突清单导出为 Excel，供现场调表使用 */
    @Transactional(readOnly = true)
    public void exportConflicts(HttpServletResponse response) {
        List<Map<String, Object>> conflicts = detectConflicts();
        Map<String, Object> sum = summary(conflicts);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "兼项冲突清单_" + com.sports.common.ExportNaming.stage(true)
                + "_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".xlsx";
        String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);

        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("序号", "严重度", "冲突类型", "运动员", "号码布", "间隔(分钟)",
                    "项目A", "项目A时间", "项目A场地", "项目B", "项目B时间", "项目B场地", "调整建议"));
            int i = 1;
            for (Map<String, Object> c : conflicts) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ea = (Map<String, Object>) c.get("eventA");
                @SuppressWarnings("unchecked")
                Map<String, Object> eb = (Map<String, Object>) c.get("eventB");
                data.add(List.of(
                        String.valueOf(i++),
                        n(c.get("severity")),
                        n(c.get("type")),
                        n(c.get("athleteName")),
                        n(c.get("athleteNumber")),
                        String.valueOf(c.get("gapMinutes") == null ? 0 : c.get("gapMinutes")),
                        n(ea.get("name")), n(c.get("windowA")), n(ea.get("venue")),
                        n(eb.get("name")), n(c.get("windowB")), n(eb.get("venue")),
                        n(c.get("suggestion"))));
            }
            data.add(List.of());
            data.add(List.of("汇总", "共 " + sum.get("total") + " 处（严重 " + sum.get("blocker")
                    + " / 一般 " + sum.get("warn") + "），涉及运动员 " + sum.get("athleteCount") + " 人",
                    "", "", "", "", "", "", "", "", "", "", ""));
            List<List<String>> head = data.get(0).stream().map(List::of).collect(java.util.stream.Collectors.toList());
            com.alibaba.excel.EasyExcel.write(out).head(head).sheet("兼项冲突")
                    .doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出兼项冲突清单失败: " + e.getMessage());
        }
        log.info("导出兼项冲突清单: 共{}处", conflicts.size());
    }

    private static String n(Object s) { return s != null ? String.valueOf(s) : ""; }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.trim().equals(b.trim());
    }

    private int toMin(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return 0;
        String[] p = hhmm.split(":");
        try {
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
