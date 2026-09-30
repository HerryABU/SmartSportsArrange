package com.sports.service.arrange;

import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 兼项高频统计（项目共现分析）。
 *
 * <p>回答编排前最关键的一个问题：<b>哪些项目经常被同一批运动员同时报名</b>。
 * 这些「高频共现」项目一旦被排到相近时段，就会在同一运动员身上产生兼项冲突——
 * 因此它应作为设定「项目出场顺序（eventOrder）」与「兼项冲突规避」的第一手依据。</p>
 *
 * <p>口径：只统计 {@code approved} 状态的报名；同一运动员报了 A、B 两个项目，
 * 则 (A,B) 共现计数 +1。输出按共现人数降序，附每项目的「兼项热度」（该项目参与了多少对高频共现）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCooccurrenceService {

    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;

    /** 项目简要信息（供输出序列化） */
    private Map<String, Object> eventBrief(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("code", e.getCode());
        m.put("category", e.getCategory());
        return m;
    }

    /**
     * 全量共现分析。
     *
     * @return { totalApproved, athleteCount, multiEventAthletes, pairs, eventHeat }
     */
    @Transactional(readOnly = true)
    public Map<String, Object> analyze() {
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
        for (Set<Long> evs : athleteEvents.values()) {
            if (evs.size() < 2) continue;
            multiEventAthletes++;
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
        out.put("pairs", pairs);
        out.put("eventHeat", heat);
        log.info("兼项高频统计: {} 对高频共现, {} 名兼项运动员", pairs.size(), multiEventAthletes);
        return out;
    }
}
