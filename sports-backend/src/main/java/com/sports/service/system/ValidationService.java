package com.sports.service.system;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.registration.Registration;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.registration.RegistrationRepository;
import com.sports.repository.result.ResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据一致性校验（U10 / B15）：
 * 跨「报名审核 → 编排 → 成绩录入 → 总分」各环节做差异核对，自动输出差异报告，
 * 避免“报名744 / 秩序册1047 / 班级人数0 / 各环节对不上却无告警”这类问题被隐藏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ValidationService {

    private final RegistrationRepository registrationRepository;
    private final ArrangementRepository arrangementRepository;
    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;

    public Map<String, Object> validateDataConsistency() {
        List<Event> events = eventRepository.findByIsEnabledTrueOrderBySortOrderAsc();

        // 总体口径
        List<Registration> approved = registrationRepository.findByStatus("approved");
        long registrationCount = approved.size();
        Set<Long> regAthletes = approved.stream()
                .map(Registration::getAthlete)
                .filter(Objects::nonNull)
                .map(Athlete::getId)
                .collect(Collectors.toSet());

        List<Arrangement> allArr = arrangementRepository.findAll();
        Set<Long> arrAthletes = allArr.stream()
                .map(Arrangement::getAthlete)
                .filter(Objects::nonNull)
                .map(com.sports.entity.athlete.Athlete::getId)
                .collect(Collectors.toSet());

        List<Result> allRes = resultRepository.findAllValid();
        Set<Long> resAthletes = allRes.stream()
                .map(Result::getAthlete)
                .filter(Objects::nonNull)
                .map(com.sports.entity.athlete.Athlete::getId)
                .collect(Collectors.toSet());

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> discrepancies = new ArrayList<>();

        for (Event e : events) {
            long regN = registrationRepository.findByEventIdAndStatus(e.getId(), "approved").size();
            Set<Long> arrSet = arrangementRepository.findByEventId(e.getId()).stream()
                    .map(Arrangement::getAthlete).filter(Objects::nonNull)
                    .map(com.sports.entity.athlete.Athlete::getId).collect(Collectors.toSet());
            long resN = resultRepository.findValidByEventId(e.getId()).size();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", e.getId());
            item.put("eventName", e.getName());
            item.put("registrationApproved", regN);
            item.put("arrangedAthletes", arrSet.size());
            item.put("resultCount", resN);
            items.add(item);

            if (regN != arrSet.size()) {
                discrepancies.add(disc("报名与编排人数不一致", e.getName(),
                        String.format("报名审核 %d 人，编排 %d 人，差 %d 人", regN, arrSet.size(),
                                Math.abs((long) regN - arrSet.size())), "P1"));
            }
            if (resN == 0 && arrSet.size() > 0) {
                discrepancies.add(disc("已编排但未录入成绩", e.getName(),
                        String.format("编排 %d 人，但成绩表为空（尚未录入或导入）", arrSet.size()), "P1"));
            }
            if (resN > 0 && regN == 0) {
                discrepancies.add(disc("有成绩但无报名", e.getName(),
                        String.format("成绩 %d 条，但报名审核为 0（数据来源异常）", resN), "P0"));
            }
        }

        // 全局跨环节差异
        if (regAthletes.size() != arrAthletes.size()) {
            discrepancies.add(disc("全局：报名与编排运动员数不一致", "全部项目",
                    String.format("报名审核去重 %d 人，编排去重 %d 人，差 %d 人",
                            regAthletes.size(), arrAthletes.size(),
                            Math.abs((long) regAthletes.size() - arrAthletes.size())), "P1"));
        }
        if (!arrAthletes.containsAll(regAthletes)) {
            long notArranged = regAthletes.stream().filter(id -> !arrAthletes.contains(id)).count();
            if (notArranged > 0) {
                discrepancies.add(disc("全局：已报名但未编排", "全部项目",
                        String.format("有 %d 名已审核报名运动员未出现在任何编排中", notArranged), "P1"));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventCount", events.size());
        summary.put("registrationApproved", registrationCount);
        summary.put("arrangedAthleteCount", arrAthletes.size());
        summary.put("resultAthleteCount", resAthletes.size());
        summary.put("discrepancyCount", discrepancies.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("perEvent", items);
        result.put("discrepancies", discrepancies);
        result.put("ok", discrepancies.isEmpty());
        return result;
    }

    private Map<String, Object> disc(String type, String scope, String message, String severity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("scope", scope);
        m.put("message", message);
        m.put("severity", severity);
        return m;
    }
}
