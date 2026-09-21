package com.sports.service;

import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.schedule.core.*;
import com.sports.schedule.opt.Placement;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.sports.schedule.support.ScheduleSupport.*;

/**
 * 赛程放置 / 落库 / 道次组件（从 {@code ScheduleService} 抽出）：负责把「求解结果或贪心选择」
 * 落到赛程表——冲突感知放置、田赛分组批量同时开赛、占道冲突告警、赛程行登记、自动道次编排。
 *
 * <p>持有两个 Repository、编排服务与构建组件（报名查询），全部为 Spring 无关的纯逻辑。</p>
 */
@Slf4j
public class SchedulePlacementComponent {

    private final ArrangementService arrangementService;
    private final ArrangementRepository arrangementRepository;
    private final EventScheduleRepository scheduleRepository;
    private final ScheduleBuildComponent buildComponent;

    public SchedulePlacementComponent(ArrangementService arrangementService,
                                      ArrangementRepository arrangementRepository,
                                      EventScheduleRepository scheduleRepository,
                                      ScheduleBuildComponent buildComponent) {
        this.arrangementService = arrangementService;
        this.arrangementRepository = arrangementRepository;
        this.scheduleRepository = scheduleRepository;
        this.buildComponent = buildComponent;
    }

    // ==================== 以下方法由 scripts/refactor_extract_placement_component.py 从 ScheduleService 迁入 ====================
    /**
     * 把一个求解结果落到赛程表：预定区间（允许回填空隙）+ 登记赛程行（径赛顺带道次编排）。
     *
     * @return 落位是否成功；越界或与已有赛程冲突时返回 false（调用方回退贪心放置）
     */
    public boolean applySolved(Unit u, Pool pool, Placement p, List<Window> windows, int interval,
                                List<EventSchedule> saved, List<String> warnings, int[] orderCounter,
                                List<String> autoArrangeFails, double compressionWarnRatio,
                                Map<Long, List<int[]>> busy, int[] conflictStat) {
        if (p.getWindowIdx() < 0 || p.getWindowIdx() >= windows.size()) return false;
        if (p.getSlotIdx() < 0 || p.getSlotIdx() >= pool.cursors.size()) return false;
        Window w = windows.get(p.getWindowIdx());
        int rel = p.getStartMinute() - w.startMinute;
        if (rel < 0 || rel + u.duration > w.capacity) return false;
        if (!pool.cursors.get(p.getSlotIdx()).reserve(p.getWindowIdx(), rel, u.duration, interval)) {
            return false;
        }
        int n = SchedulePlacementMath.countConflicts(u.athleteIds, w.day, p.getStartMinute(), u.duration, busy);
        if (n == 0) conflictStat[0]++; else conflictStat[1] += n;
        saveSchedule(u, new Slot(w, p.getStartMinute()), pool.venueOf.get(p.getSlotIdx()),
                saved, orderCounter, autoArrangeFails, warnings, compressionWarnRatio, busy);
        return true;
    }

    public void placeOne(Unit u, Pool pool, List<Window> windows, int defaultInterval, int minInterval,
                          double compressionWarnRatio, List<EventSchedule> saved, List<String> warnings,
                          int[] orderCounter, List<String> autoArrangeFails,
                          Map<Long, List<int[]>> busy, int[] conflictStat,
                          Map<Unit, Placement> solved) {
        int interval = Math.max(SchedulePlacementMath.intervalOf(u, defaultInterval), minInterval);
        // U28/B25：优先采用约束求解结果（求解器已联合决定「位置 + 时长」）。
        // 落位失败（该位置已被占用）或该单元没有解时才回退贪心——两条路径都经过同一个 Cursor
        // 记账，因此后续单元的可用空间判断始终是准确的。
        Placement fixed = solved == null ? null : solved.get(u);
        if (fixed != null && fixed.getPoolLabel().equals(pool.label)
                && applySolved(u, pool, fixed, windows, interval, saved, warnings, orderCounter,
                        autoArrangeFails, compressionWarnRatio, busy, conflictStat)) {
            return;
        }
        Cand best = SchedulePlacementMath.findBestSlot(u, pool, windows, interval, busy);
        if (best == null) {
            warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                    u.grade == null ? "不分年级" : u.grade));
            return;
        }
        // U26/B23：整块放置——u.duration 就是该项目的编排时间单位，不做任何「就地缩短」。
        // 若找不到能整块放下的位置，就如实报「排不下」，由用户按建议调整并发位/天数，
        // 而不是把一个 200 分钟的项目偷偷塞进 60 分钟的缝隙里。
        Cursor cursor = pool.cursors.get(best.slotIdx);
        Probe probe = cursor.placeAt(windows, best.windowIdx, best.startMinute, u.duration);
        if (probe == null) {   // 理论上不会发生（findBestSlot 已校验容量）
            warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                    u.grade == null ? "不分年级" : u.grade));
            return;
        }
        if (best.conflicts == 0) conflictStat[0]++;
        else conflictStat[1] += best.conflicts;
        saveSchedule(u, probe.slot, pool.venueOf.get(best.slotIdx), saved, orderCounter, autoArrangeFails,
                warnings, compressionWarnRatio, busy);
    }

    /**
     * 田赛分组 / 并行捆绑组批量放置：同组单元安排在同一时段、**同一时刻同时开始**。
     *
     * <p>实现：先探测各单元（各自场地池）的最早可用位置（不改状态），取其中「最晚的窗口 + 该窗口内最晚的起点」
     * 作为本批共同起点，再把各项目都放到该起点（各占所属池的一个并发位）；若个别槽位在该起点放不下
     * （时段容量不足），退化为各自最早位置并记 warning。</p>
     */
    public void placeBatch(List<Integer> batch, List<Unit> units, List<Pool> unitPools, List<Window> windows,
                            int defaultInterval, int minInterval, double compressionWarnRatio, String group,
                            List<EventSchedule> saved, List<String> warnings,
                            int[] orderCounter, List<String> autoArrangeFails,
                            Map<Long, List<int[]>> busy, int[] conflictStat, Map<Unit, Placement> solved) {
        // U28/B25：组内单元全部拿到求解结果时直接按解落库——「同组同时开赛」由求解器的硬约束
        // 保证（同 groupKey 必须落在同一天同一分钟），无需再做波次编排。个别落位失败只回退该单元，
        // 不牵连整组。
        if (solved != null && !batch.isEmpty()) {
            boolean allSolved = true;
            for (Integer idx : batch) {
                if (!solved.containsKey(units.get(idx))) { allSolved = false; break; }
            }
            if (allSolved) {
                for (int k = 0; k < batch.size(); k++) {
                    Unit su = units.get(batch.get(k));
                    Pool sp = unitPools.get(k);
                    int iv = Math.max(SchedulePlacementMath.intervalOf(su, defaultInterval), minInterval);
                    if (!applySolved(su, sp, solved.get(su), windows, iv, saved, warnings, orderCounter,
                            autoArrangeFails, compressionWarnRatio, busy, conflictStat)) {
                        placeOne(su, sp, windows, defaultInterval, minInterval, compressionWarnRatio,
                                saved, warnings, orderCounter, autoArrangeFails, busy, conflictStat, null);
                    }
                }
                return;
            }
        }
        int maxSlots = 1;
        for (Pool p : unitPools) maxSlots = Math.max(maxSlots, p.slots);
        for (int from = 0; from < batch.size(); from += maxSlots) {
            List<Integer> wave = batch.subList(from, Math.min(batch.size(), from + maxSlots));

            // 起始窗口下界：各参与槽位当前窗口的最大值（不倒退到已用尽的时段之前）
            int minWindow = 0;
            for (int k = 0; k < wave.size(); k++) {
                Cursor c = SchedulePlacementMath.cursorOf(unitPools.get(k), k);
                minWindow = Math.max(minWindow, c.windowIdx);
            }

            // ① 各单元最早可用位置（只探测，不推进游标）
            List<Probe> earliest = new ArrayList<>();
            for (int k = 0; k < wave.size(); k++) {
                Unit u = units.get(wave.get(k));
                Pool p = unitPools.get(k);
                earliest.add(SchedulePlacementMath.cursorOf(p, k).probe(windows, u.duration,
                        Math.max(SchedulePlacementMath.intervalOf(u, defaultInterval), minInterval), minWindow));
            }

            // ② 共同起点 = 最晚的可用窗口 + 该窗口内最晚的可用起点
            int targetWindow = minWindow;
            for (Probe p : earliest) {
                if (p != null) targetWindow = Math.max(targetWindow, p.windowIdx);
            }
            boolean sameStart = true;
            if (targetWindow >= windows.size()) {
                warnings.add(String.format("田赛分组「%s」因时段已排满未能安排", group));
                continue;
            }
            int commonStart = windows.get(targetWindow).startMinute;
            for (Probe p : earliest) {
                if (p != null && p.windowIdx == targetWindow) {
                    commonStart = Math.max(commonStart, p.slot.startMinute);
                }
            }

            // ③ 各项目放到共同起点；放不下则该项退化为各自最早位置（仍推进游标，避免重叠）
            for (int k = 0; k < wave.size(); k++) {
                Unit u = units.get(wave.get(k));
                Pool p = unitPools.get(k);
                Cursor cursor = SchedulePlacementMath.cursorOf(p, k);
                Probe probe = cursor.placeAt(windows, targetWindow, commonStart, u.duration);
                if (probe == null) {
                    sameStart = false;
                    Probe fb = earliest.get(k);   // 退化为该槽位各自最早可用位置
                    if (fb != null) {
                        probe = cursor.placeAt(windows, fb.windowIdx, fb.slot.startMinute, u.duration);
                    }
                }
                if (probe == null) {
                    warnings.add(String.format("田赛分组「%s」中项目「%s」因时段已排满未能安排", group, u.event.getName()));
                    continue;
                }
                saveSchedule(u, probe.slot, p.venueOf.get(Math.min(k, p.venueOf.size() - 1)),
                        saved, orderCounter, autoArrangeFails, warnings, compressionWarnRatio, busy);
            }
            if (!sameStart) {
                warnings.add(String.format("田赛分组「%s」部分项目未能同时开始（并发位或时段容量不足），已按各自最早时段顺延", group));
            }
        }
    }

    /**
     * 占道冲突告警：实体占用跑道的项目（真实径赛 flag=true 或 占道但用田赛法 occupiesTrack=true）
     * 彼此时间不得重叠——否则跑道被同时占用。趣味运动会(funSports)走田赛并行逻辑、不占道，可正常与径赛并行。
     * 这里只做告警（不擅自挪动位置），把「哪些项目在何时撞了跑道」如实列清，由编排者错开。
     */
    public void warnTrackOccupancy(List<String> warnings, List<EventSchedule> saved) {
        List<long[]> spans = new ArrayList<>();
        List<EventSchedule> items = new ArrayList<>();
        for (EventSchedule s : saved) {
            Event e = s.getEvent();
            if (e == null || s.getDurationMinutes() == null) continue;
            boolean occupiesTrack = Boolean.TRUE.equals(e.getTrack()) || Boolean.TRUE.equals(e.getOccupiesTrack());
            if (!occupiesTrack) continue;
            int start = SchedulePlacementMath.parseHHmm(s.getStartTime());
            if (start < 0) continue;
            int absStart = (s.getDay() != null ? (s.getDay() - 1) : 0) * 1440 + start;
            spans.add(new long[]{absStart, absStart + s.getDurationMinutes()});
            items.add(s);
        }
        List<String> clashes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                long[] a = spans.get(i), b = spans.get(j);
                if (a[0] < b[1] && b[0] < a[1]) {
                    clashes.add(String.format("「%s」(%s %s) 与「%s」(%s %s)",
                            items.get(i).getEvent().getName(), items.get(i).getScheduleDate(), items.get(i).getStartTime(),
                            items.get(j).getEvent().getName(), items.get(j).getScheduleDate(), items.get(j).getStartTime()));
                }
            }
        }
        if (!clashes.isEmpty()) {
            warnings.add(String.format("占道冲突告警：以下占用跑道的项目时间重叠（跑道被同时占用，需错开）：%s",
                    String.join("；", clashes)));
        }
    }

    /** 登记一条赛程：径赛排入后立即复用编排引擎生成道次（needHeats 项目=预赛，其余=决赛） */
    public void saveSchedule(Unit u, Slot placed, String venue, List<EventSchedule> saved,
                              int[] orderCounter, List<String> autoArrangeFails,
                              List<String> warnings, double compressionWarnRatio,
                              Map<Long, List<int[]>> busy) {
        boolean needPrelim = u.track && Boolean.TRUE.equals(u.event.getNeedHeats());
        EventSchedule s = EventSchedule.builder()
                .event(u.event)
                .day(placed.window.day)
                .scheduleDate(placed.window.date)
                .grade(u.grade)
                .timeSlot(placed.window.slotName)
                .startTime(fmt(placed.startMinute))
                .endTime(fmt(placed.startMinute + u.duration))
                .venue(venue)
                .sortOrder(orderCounter[0]++)
                .durationMinutes(u.duration)
                .round(needPrelim ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL)
                .remark(u.duration >= u.rawDuration ? null
                        : String.format("预计%d分钟，实给%d分钟（按可用时段容量适配）", u.rawDuration, u.duration))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        // B05/U07 + U24/B21：逐条压缩告警只保留「项目显式配了 maxDurationMinutes 而被压」的情形——
        // 那是用户自己设的上限，值得逐项确认；容量不足造成的等比压缩由 autoSchedule 汇总成
        // 「一池一条」的可执行告警，避免十几条重复文案把真正的业务问题淹没。
        if (u.explicitMaxDuration > 0 && u.rawDuration > u.explicitMaxDuration) {
            warnings.add(String.format("⚠️ 项目时长上限告警：「%s」（%s）预计需 %d 分钟，受该项目显式上限 "
                            + "maxDurationMinutes=%d 约束压缩为 %d 分钟（约 %.0f%%）；如现场时间充足可上调该上限",
                    u.event.getName(), u.grade == null ? "不分年级" : u.grade,
                    u.rawDuration, u.explicitMaxDuration, u.duration,
                    u.explicitMaxDuration * 100.0 / u.rawDuration));
        }
        saved.add(scheduleRepository.save(s));

        // U23/B20：登记本单元占用的时间段，供后续单元做兼项冲突规避（绝对分钟便于跨天比较）
        if (!u.athleteIds.isEmpty()) {
            int absStart = placed.window.day * 1440 + placed.startMinute;
            int[] span = {absStart, absStart + u.duration};
            for (Long aid : u.athleteIds) {
                busy.computeIfAbsent(aid, k -> new ArrayList<>()).add(span);
            }
        }

        if (u.track && u.participants > 0) {
            u.arranged = autoArrangeFor(u, autoArrangeFails);
        }
    }

    /**
     * 为单个径赛单元自动生成道次（先清理该 事件×年级×性别 的旧记录再重排）：
     * <ul>
     *   <li>needHeats（需预赛）项目 → 生成<b>预赛</b>道次（round=preliminary），
     *       录入预赛成绩并计算晋级后由 {@code computeQualifiers} 追加决赛道次与独立决赛赛程条目；</li>
     *   <li>其余项目 → 直接生成<b>决赛</b>道次（round=final）。</li>
     * </ul>
     *
     * @return 成功生成的 性别组 数量（男/女各计 1）
     */
    public int autoArrangeFor(Unit u, List<String> arrFails) {
        Event e = u.event;
        int lanes = ScheduleAnalysisMath.concurrencyOf(e);
        String round = Boolean.TRUE.equals(e.getNeedHeats())
                ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL;
        // U22/B19 根因修复：性别组必须从「真实已审核报名」推导，不能只看 event.genderLimit。
        // 现实中 genderLimit 常为空（项目名写着「男子组/女子组」，但该字段没落库/导入时缺失），
        // 旧实现此时会退回「男女各排一版」：男子项目去排 F 组时，
        // registrationRepository 查无报名 → arrange 抛「没有符合条件的已审核报名记录」。
        // 该异常虽被下面的 try/catch 吞掉，却已把外层 autoSchedule 的 @Transactional 事务
        // 标记为 rollback-only，提交时抛 UnexpectedRollbackException → 整个自动编排接口 500，
        // 且整张赛程表被回滚（event_schedule 一行不剩）。
        // 正确口径与 batchArrange 一致：按报名中实际出现的性别逐组编排；没有报名的性别直接跳过，
        // 既不是失败也不产生告警。
        List<String> genders = buildComponent.approvedGenders(e.getId(), u.grade);
        int ok = 0;
        for (String g : genders) {
            try {
                arrangementRepository.deleteByEventRoundGradeGender(e.getId(), round, u.grade, g);
                arrangementService.arrange(e.getId(), u.grade, g, lanes, null, round);
                ok++;
            } catch (Exception ex) {
                arrFails.add(String.format("%s（%s %s）：%s", e.getName(), u.grade,
                        SchedulePlacementMath.genderLabel(g),
                        ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        if (ok > 0) {
            log.info("自动道次编排: event={}({}), grade={}, genders={}, round={}", e.getName(), e.getId(), u.grade, genders, round);
        }
        return ok;
    }
}
