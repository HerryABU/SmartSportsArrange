package com.sports.service.arrange;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.event.EventScheduleRepository;
import com.sports.schedule.core.arrange.heat.HeatStaggerMath;
import com.sports.schedule.core.arrange.heat.HeatStaggerMath.HeatSlot;
import com.sports.schedule.core.arrange.heat.HeatStaggerMath.Move;
import com.sports.schedule.core.arrange.heat.HeatStaggerMath.SlotRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组次错开消解（微调算法的<b>组次维度</b>）：项目时间窗一分不动，
 * 只改换运动员在项目内的组次顺序，让两场错开。
 *
 * <h2>它在编排链里的位置</h2>
 * 排在<b>分块（Fix-and-Optimize 切片重排）之后</b>：切片重排能把整个项目挪到别的时段，
 * 但受「同并发位不重叠 + 组次必须连续」约束，实测总有一些顽固冲突挪不动。
 * 挪不动时，剩下这一招就是<b>在组内换顺序</b>——它是唯一不动时段容量的自由度。
 *
 * <h2>为什么它能消解「挪不动」的冲突</h2>
 * 项目级判定看的是整条赛程行，于是「A 10:00–11:00 与 B 10:00–10:30 重叠」被判为冲突。
 * 但甲在 A 的第 5 组（10:40–10:50）、B 的第 1 组（10:00–10:10），实际间隔 30 分钟，
 * 人根本赶得上——这类冲突只要把甲在 A 里的组次往后挪一位就消失了，
 * <b>既不用动任何项目的时间，也不用加天数或加并发位</b>。
 *
 * <p>本服务只做「换组次」这一件事，绝不挪项目时间：后者是编排主链的职责，
 * 两边都去动时间会导致评估口径与交付口径不一致（编排消解循环已经踩过这个坑）。</p>
 *
 * <p>判定与求解全部委托纯函数 {@link HeatStaggerMath}，本类只负责
 * 「读库 → 转视图 → 求解 → 落库」与如实上报。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeatStaggerService {

    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository eventScheduleRepository;
    /**
     * 组次错开 AI（可选增强）走<b>分层调度门面</b>，不直接持有 Advisor：
     * 门面是「哪一环该用哪个模型」的唯一答案，并统一统计调用次数
     * （直接持有 Advisor 会让 /api/ai/status 里的 calls 永远是 0，
     * 变成「接线正确但从未通电」这类故障的唯一可观测信号失效）。
     *
     * <p>模型只在<b>已判定合法</b>的候选里重排，不参与合法性判断；
     * 模型缺失/推理失败时自动回退规则择优（间隔最大者）。</p>
     */
    private final com.sports.schedule.ai.AiTiers aiTiers;

    /**
     * 消解组次级兼项冲突。
     *
     * @param bufferMin 赶场缓冲（分钟），须与检测端同口径（默认沿用 {@link ConflictService#CONFLICT_BUFFER_MIN}）
     * @return 消解报告：{@code {resolved, examined, moves:[...], unresolved}}；无可消解冲突时 resolved=0
     */
    @Transactional
    public Map<String, Object> resolve(int bufferMin) {
        List<Arrangement> arrangements = arrangementRepository.findAll();
        List<EventSchedule> schedules = eventScheduleRepository.findAll();
        Map<String, Object> report = new LinkedHashMap<>();

        // ① 赛程行 → 组次时间轴：按 (项目, 年级, 赛次) 归并，取该批次最早的一条赛程行作为轴心
        Map<String, Batch> batches = buildBatches(schedules);
        if (batches.isEmpty()) {
            report.put("resolved", 0);
            report.put("examined", 0);
            report.put("moves", List.of());
            report.put("note", "赛程表为空，无需组次错开");
            return report;
        }

        // ② 编排记录 → 占位视图（组次时间窗按各批次真实的组次数与每组用时推导）
        List<SlotRef> refs = new ArrayList<>();
        Map<Long, Integer> lanesOf = new HashMap<>();
        for (Arrangement a : arrangements) {
            Event e = a.getEvent();
            if (e == null || a.getAthlete() == null) continue;
            String round = a.getRound() == null || a.getRound().isBlank()
                    ? ArrangementService.ROUND_FINAL : a.getRound();
            String key = batchKey(e.getId(), a.getGrade(), a.getGender(), round);
            Batch b = batches.get(key);
            if (b == null) continue;   // 找不到赛程行 = 该项未排入本轮赛程，无从错开
            Integer heat = a.getHeat();
            if (heat == null || heat < 1 || heat > b.heatCount()) continue;
            Athlete ath = a.getAthlete();
            String classId = ath.getClassInfo() != null && ath.getClassInfo().getId() != null
                    ? String.valueOf(ath.getClassInfo().getId()) : "";
            refs.add(new SlotRef(
                    ath.getId(),
                    ath.getName() == null ? ("运动员#" + ath.getId()) : ath.getName(),
                    classId,
                    new HeatSlot(e.getId(), e.getName() == null ? "" : e.getName(),
                            a.getGrade(), a.getGender(), round,
                            heat, b.heatCount(), b.day, b.startMinute, b.perRound()),
                    Boolean.TRUE.equals(a.getIsManual())));
            lanesOf.putIfAbsent(e.getId(), lanesOf(e));
        }

        // ③ 纯函数求解（挂了模型建议；模型不可用时内部自动回退规则）
        int[] aiHits = {0};
        List<Move> moves = HeatStaggerMath.resolve(refs, lanesOf, bufferMin, suggester(aiHits));
        int aiAdvised = 0;
        for (Move m : moves) {
            if (m.aiAdvised()) {
                aiAdvised++;
            }
        }
        if (moves.isEmpty()) {
            report.put("resolved", 0);
            report.put("examined", refs.size());
            report.put("moves", List.of());
            report.put("note", "组次维度无冲突可解（要么本就错得开，要么受同班/容量约束换不了）");
            return report;
        }

        // ④ 落库：只改 heat，其余字段一律不动（道次、赛次、成绩全部保持原样）
        Map<Long, Integer> targetHeat = new HashMap<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Move m : moves) {
            String key = batchKey(m.from().eventId(), m.from().grade(), m.from().gender(), m.from().round());
            Batch b = batches.get(key);
            if (b == null) continue;
            for (Arrangement a : b.arrangements) {
                if (a.getAthlete() == null || a.getAthlete().getId() == null) continue;
                if (!a.getAthlete().getId().equals(m.athleteId())) continue;
                if (a.getHeat() == null || a.getHeat() != m.from().heat()) continue;
                a.setHeat(m.to().heat());
                a.setUpdatedAt(LocalDateTime.now());
                targetHeat.put(a.getId(), m.to().heat());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("athleteId", m.athleteId());
            row.put("athleteName", m.athleteName());
            row.put("eventName", m.from().eventName());
            row.put("grade", m.from().grade());
            row.put("gender", m.from().gender());
            row.put("fromHeat", m.from().heat());
            row.put("toHeat", m.to().heat());
            row.put("gapBefore", m.gapBefore());
            row.put("gapAfter", m.gapAfter());
            detail.add(row);
        }
        if (!targetHeat.isEmpty()) {
            arrangementRepository.saveAll(arrangements);
        }

        report.put("resolved", detail.size());
        report.put("examined", refs.size());
        report.put("aiAdvised", aiAdvised);
        report.put("aiAvailable", aiTiers != null);
        report.put("moves", detail);
        log.info("组次错开消解: 检查 {} 条编排记录，换组 {} 人次（项目时间窗未改动）", refs.size(), detail.size());
        for (Map<String, Object> row : detail) {
            log.info("  组次错开: 运动员「{}」在「{}」（{} {}）第{}组 → 第{}组（间隔 {} → {} 分钟）",
                    row.get("athleteName"), row.get("eventName"), row.get("grade"), row.get("gender"),
                    row.get("fromHeat"), row.get("toHeat"), row.get("gapBefore"), row.get("gapAfter"));
        }
        return report;
    }

    /**
     * 把 AI 建议包成 {@link HeatStaggerMath.HeatSuggester}。
     *
     * <p>模型不可用时返回 null，算法内部走规则兜底 —— 这条链路上
     * AI 是「锦上添花」而非交付前提，缺模型不能影响功能可用性。</p>
     */
    private HeatStaggerMath.HeatSuggester suggester(int[] aiHits) {
        if (aiTiers == null) {
            return null;
        }
        return (heatCount, perRound, legal, curHeat, gapOf, fillOf) -> {
            java.util.Optional<Integer> r = aiTiers.adviseHeatStagger(
                    heatCount, perRound, legal, curHeat, gapOf, fillOf);
            r.ifPresent(x -> aiHits[0]++);
            return r;
        };
    }

    /** 兼项冲突消解报告（供编排响应与接口直接透出） */
    public Map<String, Object> summary() {
        return resolve(ConflictService.CONFLICT_BUFFER_MIN);
    }

    /**
     * 编排批次：同 (项目, 年级, 性别, 赛次) 下所有组次共用一条时间轴。
     *
     * @param day          该批次落在第几天
     * @param startMinute  赛程行起点（当日分钟）
     * @param perRound     每组用时（分钟）= 赛程行时长 ÷ 组次数
     * @param heatCount    组次数
     * @param arrangements 该批次的编排记录（落库时按 (运动员, 原组次) 定位）
     */
    private record Batch(int day, int startMinute, int perRound, int heatCount,
                         List<Arrangement> arrangements) {
    }

    /**
     * 装配全部批次的组次时间轴。
     *
     * <h3>每组用时（perRound）为什么必须取自项目配置，而不能「用时长 ÷ 组数」反推</h3>
     * 反推的前提是「组数已知且连续」，而组数有两个不可靠来源：
     * 组号可能<b>缺号</b>（某组无人报名、道次编排不建该组），也可能被款型调整。
     * 一旦组数被低估，每组用时就会偏大，组次时间窗整体错位——
     * 错开算法会据此把甲换到一个「看起来错开、实际仍撞车」的组次，
     * 而且因为改完仍然满足全部硬约束，<b>完全静默</b>。
     *
     * <p>因此口径改为：{@code perRound} 取项目显式配置的每批用时
     * （{@code perBatchMinutes}，与编排期 {@code estimateDurations} 同一来源），
     * 缺配置时回退编排默认值；组次数则由「时长 ÷ perRound」推得，
     * 并与编排表实际最大组号取较大者（防止时长被压缩后组数偏小）。</p>
     *
     * <p><b>为什么一条赛程行服务多个性别</b>：编排落库时一个赛程行 = 一个单元 = (项目, 年级)，
     * 男/女共用这一行、组次连续排下去。因此轴的组次数取<b>各性别组次数的最大值</b>
     * （与编排期「时长 = 总轮次 × 组次用时」的口径一致），而编排记录仍按性别各自归集。</p>
     */
    private Map<String, Batch> buildBatches(List<EventSchedule> schedules) {
        // ① 赛程行 → 时间轴（键不含性别：一条行服务该 (项目,年级,赛次) 下的所有性别）
        Map<String, EventSchedule> axis = new LinkedHashMap<>();
        for (EventSchedule s : schedules) {
            if (s.getEvent() == null || s.getStartTime() == null || s.getEndTime() == null) continue;
            axis.putIfAbsent(axisKey(s.getEvent().getId(), s.getGrade(), roundOf(s)), s);
        }
        if (axis.isEmpty()) return Map.of();

        // ② 编排记录 → 批次（按性别归集；同时记下实际出现过的最大组号，作为组数下界）
        Map<String, List<Arrangement>> byBatch = new LinkedHashMap<>();
        Map<String, Integer> maxHeatByAxis = new HashMap<>();
        for (Arrangement a : arrangementRepository.findAll()) {
            if (a.getEvent() == null || a.getHeat() == null || a.getHeat() < 1) continue;
            String full = batchKey(a.getEvent().getId(), a.getGrade(), a.getGender(), roundOfArr(a));
            byBatch.computeIfAbsent(full, k -> new ArrayList<>()).add(a);
            String ak = axisKey(a.getEvent().getId(), a.getGrade(), roundOfArr(a));
            maxHeatByAxis.merge(ak, a.getHeat(), Math::max);
        }

        // ③ 逐批次成轴
        Map<String, Batch> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Arrangement>> e : byBatch.entrySet()) {
            Arrangement head = e.getValue().get(0);
            String ak = axisKey(head.getEvent().getId(), head.getGrade(), roundOfArr(head));
            EventSchedule s = axis.get(ak);
            if (s == null) continue;   // 找不到赛程行 = 未排入本轮赛程，无从错开
            int start = parseMinute(s.getStartTime());
            int end = parseMinute(s.getEndTime());
            if (start < 0 || end <= start) continue;
            int perRound = perRoundOf(head.getEvent());
            if (perRound <= 0) continue;
            // 组数 = 时长 ÷ 每组用时，并与编排表实际最大组号取较大者（时长被压缩时兜底）
            int heatCount = Math.max((end - start) / perRound, maxHeatByAxis.getOrDefault(ak, 0));
            if (heatCount < 1) continue;
            out.put(e.getKey(), new Batch(s.getDay() == null ? 1 : s.getDay(),
                    start, perRound, heatCount, e.getValue()));
        }
        return out;
    }

    /**
     * 单个组次（径赛的一组 / 田赛的一批）的用时（分钟）。
     *
     * <p>与编排期 {@code ScheduleBuildComponent.estimateDurations} <b>同一来源</b>：
     * {@code perBatchMinutes} 优先，其次径赛取 heatMinutes、田赛取 fieldPerAthleteMinutes。
     * 这里的默认值（6 / 3）与 {@code ScheduleService} 的内置默认保持一致。</p>
     */
    private int perRoundOf(Event e) {
        if (e == null) return 0;
        Integer perBatch = e.getPerBatchMinutes();
        if (perBatch != null && perBatch > 0) return perBatch;
        return Boolean.FALSE.equals(e.getTrack()) ? 3 : 6;
    }

    /** 组次数未配置时的兜底：田赛默认工位数 8 组、径赛按 6 分钟/组估算，保证每组至少 1 人 */
    private int lanesOf(Event e) {
        if (e.getConcurrency() != null && e.getConcurrency() > 0) return e.getConcurrency();
        if (Boolean.FALSE.equals(e.getTrack())) {
            return e.getGroupSize() != null && e.getGroupSize() > 0 ? e.getGroupSize() : 8;
        }
        if (e.getLaneCount() != null && e.getLaneCount() > 0) return e.getLaneCount();
        if (e.getDefaultLanes() != null && e.getDefaultLanes() > 0) return e.getDefaultLanes();
        return 8;
    }

    private static int parseMinute(String hhmm) {
        if (hhmm == null) return -1;
        String[] p = hhmm.split(":");
        if (p.length < 2) return -1;
        try {
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 批次键（含性别）：编排表侧的分组口径，与道次编排的「(项目,年级,性别,赛次) 唯一」一致 */
    private static String batchKey(Long eventId, String grade, String gender, String round) {
        return eventId + "|" + nz(grade) + "|" + nz(gender) + "|" + nz(round);
    }

    /** 轴键（不含性别）：赛程表侧一行 = 一个 (项目,年级,赛次)，男/女共用 */
    private static String axisKey(Long eventId, String grade, String round) {
        return eventId + "|" + nz(grade) + "|" + nz(round);
    }

    private static String roundOf(EventSchedule s) {
        return s.getRound() == null || s.getRound().isBlank()
                ? ArrangementService.ROUND_FINAL : s.getRound();
    }

    private static String roundOfArr(Arrangement a) {
        return a.getRound() == null || a.getRound().isBlank()
                ? ArrangementService.ROUND_FINAL : a.getRound();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
