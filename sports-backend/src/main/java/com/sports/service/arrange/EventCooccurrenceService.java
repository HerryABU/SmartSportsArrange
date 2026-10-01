package com.sports.service.arrange;

import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
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
import java.util.stream.Collectors;

/**
 * 兼项统计（项目共现分析 + 兼项运动员名单）。
 *
 * <p>回答编排前最关键的两个问题：</p>
 * <ol>
 *   <li><b>哪些项目经常被同一批运动员同时报名</b>——这些「高频共现」项目一旦被排到相近时段，
 *       就会在同一运动员身上产生兼项冲突，是设定「项目出场顺序（eventOrder）」的第一手依据；</li>
 *   <li><b>哪些运动员兼了项、兼了几项</b>——兼项运动员是编排的「高风险人群」，
 *       名单表给现场排表 / 临时改项提供直接依据。</li>
 * </ol>
 *
 * <p>口径：只统计 {@code approved} 状态的报名；同一运动员报了 A、B 两个项目，
 * 则 (A,B) 共现计数 +1。输出按共现人数降序，附每项目的「兼项热度」（该项目参与了多少对高频共现）。</p>
 *
 * <p>全部是<b>纯查询、无副作用</b>的只读口径：前端进入「赛程编排」即自动加载，不必再点一次按钮；
 * 报名审核变化后重新调用即可刷新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCooccurrenceService {

    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;
    private final AthleteRepository athleteRepository;

    /** 兼项项数分布的分桶标签（自低到高），5 项及以上归入最后一档 */
    private static final String[] DIST_BUCKETS = {"2 项", "3 项", "4 项", "5 项及以上"};

    /** 项目简要信息（供输出序列化） */
    private Map<String, Object> eventBrief(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("code", e.getCode());
        m.put("category", e.getCategory());
        return m;
    }

    /** 空值归一：避免导出的 Excel 里出现空格难读 */
    private static String n(Object o) {
        return o == null || o.toString().trim().isEmpty() ? "" : o.toString();
    }

    /**
     * 全量兼项分析（含兼项运动员名单与项数分布）。
     *
     * @param limit 兼项运动员名单返回条数上限（按兼项数降序），{@code <=0} 表示不截断
     * @return { totalApproved, athleteCount, multiEventAthletes, multiRatio, maxEvents,
     *           distribution, athletes, pairs, eventHeat }
     */
    @Transactional(readOnly = true)
    public Map<String, Object> analyze(int limit) {
        List<Registration> regs = registrationRepository.findByStatus("approved");
        Map<Long, Event> events = eventRepository.findAll().stream()
                .collect(Collectors.toMap(Event::getId, e -> e, (a, b) -> a));

        // 运动员 -> 已报项目集合（去重，且项目必须在 event 表内）
        Map<Long, Set<Long>> athleteEvents = new LinkedHashMap<>();
        for (Registration r : regs) {
            if (r.getAthlete() == null || r.getAthlete().getId() == null) continue;
            if (r.getEvent() == null || r.getEvent().getId() == null) continue;
            if (!events.containsKey(r.getEvent().getId())) continue;
            athleteEvents.computeIfAbsent(r.getAthlete().getId(), k -> new LinkedHashSet<>())
                    .add(r.getEvent().getId());
        }

        // 项目对 -> 共现运动员数（无向，统一小 id 在前作键）
        Map<String, long[]> pairCount = new LinkedHashMap<>();
        Map<Long, long[]> eventHeat = new LinkedHashMap<>();   // 项目 -> 共现次数累加
        int multiEventAthletes = 0;
        int maxEvents = 0;
        for (Set<Long> evs : athleteEvents.values()) {
            if (evs.size() < 2) continue;
            multiEventAthletes++;
            if (evs.size() > maxEvents) maxEvents = evs.size();
            List<Long> list = new ArrayList<>(evs);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    long a = Math.min(list.get(i), list.get(j));
                    long b = Math.max(list.get(i), list.get(j));
                    pairCount.computeIfAbsent(a + "|" + b, k -> new long[1])[0]++;
                    eventHeat.computeIfAbsent(a, k -> new long[1])[0]++;
                    eventHeat.computeIfAbsent(b, k -> new long[1])[0]++;
                }
            }
        }

        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Map.Entry<String, long[]> e : pairCount.entrySet()) {
            String[] ids = e.getKey().split("\\|");
            long a = Long.parseLong(ids[0]);
            long b = Long.parseLong(ids[1]);
            Event ea = events.get(a);
            Event eb = events.get(b);
            if (ea == null || eb == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("eventA", eventBrief(ea));
            p.put("eventB", eventBrief(eb));
            p.put("commonAthletes", e.getValue()[0]);
            pairs.add(p);
        }
        pairs.sort((x, y) -> Long.compare(
                ((Number) y.get("commonAthletes")).longValue(),
                ((Number) x.get("commonAthletes")).longValue()));

        List<Map<String, Object>> heat = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : eventHeat.entrySet()) {
            Event ev = events.get(e.getKey());
            if (ev == null) continue;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("eventId", e.getKey());
            h.put("eventName", ev.getName());
            h.put("code", ev.getCode());
            h.put("category", ev.getCategory());
            h.put("heat", e.getValue()[0]);
            heat.add(h);
        }
        heat.sort((x, y) -> Long.compare(
                ((Number) y.get("heat")).longValue(),
                ((Number) x.get("heat")).longValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalApproved", regs.size());
        out.put("athleteCount", athleteEvents.size());
        out.put("multiEventAthletes", multiEventAthletes);
        out.put("multiRatio", athleteEvents.isEmpty() ? 0.0 : round4(multiEventAthletes * 1.0 / athleteEvents.size()));
        out.put("maxEvents", maxEvents);
        out.put("distribution", distribution(athleteEvents));
        out.put("athletes", athleteRoster(athleteEvents, events, limit));
        out.put("pairs", pairs);
        out.put("eventHeat", heat);
        log.info("兼项统计: {} 名运动员（其中 {} 名兼项，最多兼 {} 项），{} 对高频共现",
                athleteEvents.size(), multiEventAthletes, maxEvents, pairs.size());
        return out;
    }

    /** 无参重载：兼容旧调用（不截断名单） */
    public Map<String, Object> analyze() {
        return analyze(0);
    }

    /** 兼项项数分布：{2 项, 3 项, 4 项, 5 项及以上} 各多少人（占比相对全部参赛运动员） */
    private List<Map<String, Object>> distribution(Map<Long, Set<Long>> athleteEvents) {
        long[] buckets = new long[DIST_BUCKETS.length];
        for (Set<Long> evs : athleteEvents.values()) {
            int idx = evs.size() - 2;
            if (idx < 0) continue;                                   // 只报 1 项的不算兼项
            if (idx >= DIST_BUCKETS.length) idx = DIST_BUCKETS.length - 1;
            buckets[idx]++;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < DIST_BUCKETS.length; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", DIST_BUCKETS[i]);
            m.put("count", buckets[i]);
            m.put("ratio", athleteEvents.isEmpty() ? 0.0
                    : round4(buckets[i] * 1.0 / athleteEvents.size()));
            list.add(m);
        }
        return list;
    }

    /**
     * 兼项运动员名单：{athleteId, name, number, gender, className, grade, eventCount,
     * pairCount, eventNames, eventNamesText}
     * 排序：兼项数降序 → 涉及项目对降序 → 姓名，{@code limit>0} 时截断。
     */
    private List<Map<String, Object>> athleteRoster(Map<Long, Set<Long>> athleteEvents,
                                                    Map<Long, Event> events, int limit) {
        // 本事务内 athlete -> 实体 的局部缓存（避免 N 次 findById 打爆 Hibernate 一级缓存）
        Map<Long, Athlete> cache = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> en : athleteEvents.entrySet()) {
            Set<Long> evs = en.getValue();
            if (evs.size() < 2) continue;

            Athlete a = cache.computeIfAbsent(en.getKey(), id -> athleteRepository.findById(id).orElse(null));

            List<String> names = new ArrayList<>();
            for (Long eid : evs) {
                Event e = events.get(eid);
                if (e != null) names.add(n(e.getName()));
            }
            names.sort(String::compareTo);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("athleteId", en.getKey());
            row.put("name", a == null ? "" : n(a.getName()));
            row.put("number", a == null ? "" : n(a.getNumber()));
            row.put("gender", a == null ? "" : n(a.getGender()));
            row.put("className", a == null ? "" : classNameOf(a));
            row.put("grade", a == null ? "" : n(a.getGrade()));
            row.put("eventCount", evs.size());
            row.put("pairCount", evs.size() * (evs.size() - 1) / 2);
            row.put("eventNames", names);
            row.put("eventNamesText", String.join("、", names));
            rows.add(row);
        }
        rows.sort((x, y) -> {
            int c = Integer.compare(((Number) y.get("eventCount")).intValue(),
                    ((Number) x.get("eventCount")).intValue());
            if (c != 0) return c;
            int p = Integer.compare(((Number) y.get("pairCount")).intValue(),
                    ((Number) x.get("pairCount")).intValue());
            if (p != 0) return p;
            return String.valueOf(x.get("name")).compareTo(String.valueOf(y.get("name")));
        });
        if (limit > 0 && rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));
        return rows;
    }

    /** 班级优先取绑定的 ClassInfo，退回手工填写的班级名；懒加载失效也不该中断统计 */
    private static String classNameOf(Athlete a) {
        try {
            if (a.getClassInfo() != null && a.getClassInfo().getName() != null) {
                return a.getClassInfo().getName();
            }
        } catch (Exception ignored) {
            // 会话已关闭：回落到 classNameInput
        }
        return n(a.getClassNameInput());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 兼项运动员名单导出（Excel，EasyExcel 与兼项冲突清单同款）：
     * 现场排表 / 临时改项用的直接依据。
     */
    @Transactional(readOnly = true)
    public void exportMultiEventAthletes(HttpServletResponse response) throws IOException {
        Map<String, Object> data = analyze(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("athletes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) data.get("pairs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dist = (List<Map<String, Object>>) data.get("distribution");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = "兼项运动员统计_" + com.sports.common.util.ExportNaming.stage(true)
                + "_v" + com.sports.common.util.ExportNaming.appVersion()
                + "_" + com.sports.common.util.ExportNaming.stamp() + ".xlsx";
        String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);

        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> head = List.of(List.<String>of(
                    "序号", "姓名", "号码布", "性别", "班级", "年级",
                    "兼项数量", "涉及项目对", "报名项目"));
            List<List<String>> body = new ArrayList<>();
            int i = 1;
            for (Map<String, Object> r : rows) {
                body.add(List.of(
                        String.valueOf(i++),
                        n(r.get("name")), n(r.get("number")), n(r.get("gender")),
                        n(r.get("className")), n(r.get("grade")),
                        String.valueOf(r.get("eventCount")),
                        String.valueOf(r.get("pairCount")),
                        n(r.get("eventNamesText"))));
            }
            body.add(List.<String>of());
            body.add(List.of("汇总", "报名 " + data.get("totalApproved") + " 条 / 参赛 "
                    + data.get("athleteCount") + " 人 / 兼项 "
                    + data.get("multiEventAthletes") + " 人（占比 "
                    + round4((Double) data.get("multiRatio")) * 100 + "%）",
                    "", "", "", "", "", "",
                    "项数分布：" + dist.stream()
                            .map(d -> n(d.get("label")) + " " + n(d.get("count")) + " 人")
                            .collect(Collectors.joining(" / "))));
            body.add(List.<String>of());
            body.add(List.of("高频共现项目对（同料人数）"));
            int k = 1;
            for (Map<String, Object> p : pairs) {
                body.add(List.of(String.valueOf(k++),
                        n(((Map<?, ?>) p.get("eventA")).get("name")),
                        n(((Map<?, ?>) p.get("eventB")).get("name")),
                        n(p.get("commonAthletes")) + " 人"));
            }
            com.alibaba.excel.EasyExcel.write(out).head(head).sheet("兼项运动员")
                    .doWrite(body);
        }
        log.info("导出兼项运动员名单 {} 条", rows.size());
    }
}
