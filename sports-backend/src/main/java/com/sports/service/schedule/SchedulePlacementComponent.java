package com.sports.service.schedule;

import com.sports.entity.event.Event;
import com.sports.entity.event.EventSchedule;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.event.EventScheduleRepository;

import com.sports.schedule.opt.solver.Placement;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.sports.schedule.support.ScheduleSupport.*;
import com.sports.schedule.core.math.ScheduleAnalysisMath;
import com.sports.schedule.core.math.SchedulePlacementMath;
import com.sports.schedule.core.primitive.Cand;
import com.sports.schedule.core.primitive.Cursor;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Probe;
import com.sports.schedule.core.primitive.Slot;
import com.sports.schedule.core.placement.split.SlotSplit;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.service.arrange.ArrangementService;

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
                                Map<Long, List<int[]>> busy, int[] conflictStat,
                                Map<Long, List<int[]>> eventBlocked, String laneStyleRule) {
        if (p.getWindowIdx() < 0 || p.getWindowIdx() >= windows.size()) return false;
        if (p.getSlotIdx() < 0 || p.getSlotIdx() >= pool.cursors.size()) return false;
        Window w = windows.get(p.getWindowIdx());
        int rel = p.getStartMinute() - w.startMinute;
        if (rel < 0 || rel + u.duration > w.capacity) return false;
        // 行政时间保护：本项目（TEACHER 个人时段）在该时刻受保护 → 拒绝求解结果，回退贪心放置
        if (isBlocked(u.event.getId(), w.day, p.getStartMinute(), u.duration, eventBlocked)) return false;
        if (!pool.cursors.get(p.getSlotIdx()).reserve(p.getWindowIdx(), rel, u.duration, interval)) {
            return false;
        }
        int n = SchedulePlacementMath.countConflicts(u.athleteIds, w.day, p.getStartMinute(), u.duration, busy);
        if (n == 0) conflictStat[0]++; else conflictStat[1] += n;
        saveSchedule(u, new Slot(w, p.getStartMinute()), pool.venueOf.get(p.getSlotIdx()),
                saved, orderCounter, autoArrangeFails, warnings, compressionWarnRatio, busy, laneStyleRule);
        return true;
    }

    public void placeOne(Unit u, Pool pool, List<Window> windows, int defaultInterval, int minInterval,
                          double compressionWarnRatio, List<EventSchedule> saved, List<String> warnings,
                          int[] orderCounter, List<String> autoArrangeFails,
                          Map<Long, List<int[]>> busy, int[] conflictStat,
                          Map<Unit, Placement> solved, Map<Long, List<int[]>> eventBlocked,
                          String laneStyleRule) {
        placeOne(u, pool, windows, defaultInterval, minInterval, compressionWarnRatio, saved, warnings,
                orderCounter, autoArrangeFails, busy, conflictStat, solved, eventBlocked, laneStyleRule, true);
    }

    /**
     * 单个单元的放置（{@code allowSplit} = 是否允许中午临界点跨时段拆分）。
     *
     * <p>拆分只在「<b>整块确实放不下</b>」时才尝试：能整块放下的一律整块放——
     * 凭空多一条赛程行会让导出、秩序册、覆盖率统计都变复杂，不值得。
     * 能整块放但上午只剩零头时也不拆：那种情况整块挪到下午更整齐，
     * 拆两段反而让现场多一次集合、多一次转场。</p>
     */
    public void placeOne(Unit u, Pool pool, List<Window> windows, int defaultInterval, int minInterval,
                          double compressionWarnRatio, List<EventSchedule> saved, List<String> warnings,
                          int[] orderCounter, List<String> autoArrangeFails,
                          Map<Long, List<int[]>> busy, int[] conflictStat,
                          Map<Unit, Placement> solved, Map<Long, List<int[]>> eventBlocked,
                          String laneStyleRule, boolean allowSplit) {
        int interval = Math.max(SchedulePlacementMath.intervalOf(u, defaultInterval), minInterval);
        List<int[]> blocked = eventBlocked == null ? null : eventBlocked.get(u.event.getId());
        // U28/B25：优先采用约束求解结果（求解器已联合决定「位置 + 时长」）。
        // 落位失败（该位置已被占用）或该单元没有解时才回退贪心——两条路径都经过同一个 Cursor
        // 记账，因此后续单元的可用空间判断始终是准确的。
        Placement fixed = solved == null ? null : solved.get(u);
        if (fixed != null && fixed.getPoolLabel().equals(pool.label)
                && applySolved(u, pool, fixed, windows, interval, saved, warnings, orderCounter,
                        autoArrangeFails, compressionWarnRatio, busy, conflictStat, eventBlocked,
                        laneStyleRule)) {
            return;
        }
        Cand best = SchedulePlacementMath.findBestSlot(u, pool, windows, interval, busy, blocked);
        if (best == null) {
            // U26/B23：整块放置——放不下就试中午临界点跨时段拆分（上午末 + 下午初）。
            // 拆不出合法方案才如实报「排不下」，由用户按建议调整并发位/天数。
            if (allowSplit && placeSplit(u, pool, windows, interval, busy, blocked, saved, warnings,
                    orderCounter, autoArrangeFails, compressionWarnRatio, conflictStat, laneStyleRule)) {
                return;
            }
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
            if (allowSplit && placeSplit(u, pool, windows, interval, busy, blocked, saved, warnings,
                    orderCounter, autoArrangeFails, compressionWarnRatio, conflictStat, laneStyleRule)) {
                return;
            }
            warnings.add(String.format("项目「%s」（%s）因时段已排满未能安排", u.event.getName(),
                    u.grade == null ? "不分年级" : u.grade));
            return;
        }
        if (best.conflicts == 0) conflictStat[0]++;
        else conflictStat[1] += best.conflicts;
        saveSchedule(u, probe.slot, pool.venueOf.get(best.slotIdx), saved, orderCounter, autoArrangeFails,
                warnings, compressionWarnRatio, busy, laneStyleRule);
    }

    /**
     * 中午临界点跨时段拆分落位：把一个项目按<b>组次边界</b>切成上午一段 + 下午一段，
     * 落成<b>两条</b>赛程行（同 event 同 grade，remark 标注「跨时段第 N 段」）。
     *
     * <p>为什么落两条而不是一条：赛程行的 {@code timeSlot / startTime / endTime} 是现场要印在
     * 秩序册上的事实，「上午 10:40–11:30」与「下午 14:00–14:30」是两个不同的时段，
     * 硬塞进一行会得到一个 10:40–14:30 的荒谬时间窗。</p>
     *
     * <p>为什么只在整块放不下时调用：本方法产生的副作用是「多一条赛程行」，
     * 能整块放就该整块放（见 {@link #placeOne} 的说明）。</p>
     *
     * @return 拆分落位成功返回 true
     */
    private boolean placeSplit(Unit u, Pool pool, List<Window> windows, int interval,
                               Map<Long, List<int[]>> busy, List<int[]> blocked,
                               List<EventSchedule> saved, List<String> warnings, int[] orderCounter,
                               List<String> autoArrangeFails, double compressionWarnRatio,
                               int[] conflictStat, String laneStyleRule) {
        SlotSplit.SplitCand cand = SchedulePlacementMath.findSplit(u, pool, windows, interval, busy, blocked);
        if (cand == null) return false;
        // 时长守恒是拆分之一票否决项：切错了一个组次，后面全部字段都不可信
        if (!cand.durationConsistent(u.duration)) return false;
        if (cand.slotIdx() < 0 || cand.slotIdx() >= pool.cursors.size()) return false;

        Cursor cursor = pool.cursors.get(cand.slotIdx());
        Window head = windows.get(cand.headWindowIdx());
        Window tail = windows.get(cand.tailWindowIdx());
        int headRel = cand.headStart() - head.startMinute;
        int tailRel = cand.tailStart() - tail.startMinute;
        if (!cursor.reserveSplit(cand.headWindowIdx(), headRel, cand.headDuration(),
                cand.tailWindowIdx(), tailRel, cand.tailDuration(), interval)) {
            return false;   // 探测到、但落位时被占（并发趟内其他单元抢先）→ 如实回退「排不下」
        }
        String venue = pool.venueOf.get(cand.slotIdx());
        int headRounds = cand.headDuration() / u.perRoundMinutes();
        int tailRounds = u.rounds - headRounds;
        if (cand.conflicts() == 0) conflictStat[0]++;
        else conflictStat[1] += cand.conflicts();

        List<String> splitWarnings = new ArrayList<>();
        // 两段依次落库：head 段与 tail 段各自是一条赛程行，段序写进 remark 供现场按序集合
        if (saveScheduleSegment(u, head, cand.headStart(), cand.headDuration(),
                1, u.rounds, venue, saved, orderCounter, splitWarnings, busy) == null) {
            return false;
        }
        if (saveScheduleSegment(u, tail, cand.tailStart(), cand.tailDuration(),
                2, u.rounds, venue, saved, orderCounter, splitWarnings, busy) == null) {
            return false;
        }
        warnings.addAll(splitWarnings);
        // 道次编排只在两段都落库后做一次：编排表按 (项目,年级,性别,赛次) 唯一，
        // 分两次调用会互相 delete 掉对方刚写的组次（autoArrangeFor 每次先清旧记录）。
        if (u.participants > 0) {
            u.arranged = autoArrangeFor(u, autoArrangeFails, laneStyleRule);
        }
        log.info("跨时段拆分落位: 「{}」（{}）上午 {}min/{} 组 + 下午 {}min/{} 组 @{}",
                u.event.getName(), u.grade == null ? "不分年级" : u.grade,
                cand.headDuration(), headRounds, cand.tailDuration(), tailRounds, venue);
        return true;
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
                            Map<Long, List<int[]>> busy, int[] conflictStat, Map<Unit, Placement> solved,
                            Map<Long, List<int[]>> eventBlocked, String laneStyleRule) {
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
                            autoArrangeFails, compressionWarnRatio, busy, conflictStat, eventBlocked,
                            laneStyleRule)) {
                        placeOne(su, sp, windows, defaultInterval, minInterval, compressionWarnRatio,
                                saved, warnings, orderCounter, autoArrangeFails, busy, conflictStat, null,
                                eventBlocked, laneStyleRule);
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
                        saved, orderCounter, autoArrangeFails, warnings, compressionWarnRatio, busy,
                        laneStyleRule);
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

    /** 该项目在 (day, start, duration) 是否落入行政时间保护（TEACHER 个人时段）区间 */
    private static boolean isBlocked(Long eventId, int day, int startMin, int duration,
                                     Map<Long, List<int[]>> eventBlocked) {
        if (eventBlocked == null || eventBlocked.isEmpty()) return false;
        List<int[]> blocked = eventBlocked.get(eventId);
        if (blocked == null || blocked.isEmpty()) return false;
        for (int[] b : blocked) {
            if (b == null || b.length < 3) continue;
            if (b[0] != -1 && b[0] != day) continue;
            if (startMin < b[2] && b[1] < startMin + duration) return true;
        }
        return false;
    }

    /**
     * 落一条赛程行（跨时段拆分复用；单段路径也统一走这里）。
     *
     * <p>与 {@code saveSchedule} 的差别：起止/时长由调用方给定（拆分时上午、下午是两段），
     * remark 标注段序；{@code busy} 登记与压缩告警的口径保持一致。</p>
     *
     * @param roundIndex 本段是第几段（1-based）
     * @param roundTotal 总段数
     * @return 落库后的赛程行；落库失败返回 null
     */
    private EventSchedule saveScheduleSegment(Unit u, Window w, int startMinute, int duration,
                                              int roundIndex, int roundTotal, String venue,
                                              List<EventSchedule> saved, int[] orderCounter,
                                              List<String> warnings, Map<Long, List<int[]>> busy) {
        boolean needPrelim = u.track && Boolean.TRUE.equals(u.event.getNeedHeats());
        List<String> local = new ArrayList<>();
        EventSchedule s = buildScheduleRow(u, w, startMinute, duration, venue, orderCounter, local,
                roundIndex, roundTotal, needPrelim);
        warnings.addAll(local);
        EventSchedule row = scheduleRepository.save(s);
        saved.add(row);

        // busy 登记：两段都登记，后续单元才能同时避开「上午段」与「下午段」
        if (!u.athleteIds.isEmpty()) {
            int absStart = w.day * 1440 + startMinute;
            int[] span = {absStart, absStart + duration};
            for (Long aid : u.athleteIds) {
                busy.computeIfAbsent(aid, k -> new ArrayList<>()).add(span);
            }
        }
        return row;
    }

    /**
     * 组装一条赛程行（<b>不落库</b>）：字段装配的唯一出口。
     *
     * <p>抽出来的原因：跨时段拆分要落两行，若各写一份 builder 链，字段一旦漏改
     * （比如新增一个业务字段只改了一处）就会两段行为不一致——而这种 bug 在赛程表里
     * 表现为「上午那行有 remark、下午那行没有」，极难定位。</p>
     */
    private EventSchedule buildScheduleRow(Unit u, Window w, int startMinute, int duration, String venue,
                                           int[] orderCounter, List<String> warnings,
                                           int roundIndex, int roundTotal, boolean needPrelim) {
        List<String> remark = new ArrayList<>();
        if (roundTotal > 1) {
            // 跨时段：段序 + 该段承载的组次数（现场按「第 N 段」集合，顺序必须可读）
            remark.add(String.format("跨时段第%d/%d段（%d个组次）", roundIndex, roundTotal,
                    duration / Math.max(1, u.perRoundMinutes())));
        } else if (duration < u.rawDuration) {
            remark.add(String.format("预计%d分钟，实给%d分钟（按可用时段容量适配）", u.rawDuration, duration));
        }
        return EventSchedule.builder()
                .event(u.event)
                .day(w.day)
                .scheduleDate(w.date)
                .grade(u.grade)
                .timeSlot(w.slotName)
                .startTime(fmt(startMinute))
                .endTime(fmt(startMinute + duration))
                .venue(venue)
                .sortOrder(orderCounter[0]++)
                .durationMinutes(duration)
                .round(needPrelim ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL)
                .remark(remark.isEmpty() ? null : String.join("；", remark))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 登记一条赛程：排入后立即复用编排引擎生成道次/组次（needHeats 项目=预赛，其余=决赛）。径赛与田赛都在此自动编排——田赛按「项目内并发/工位数」自动分组成次（X 人一组），不再因 u.track 判断而被跳过。 */
    public void saveSchedule(Unit u, Slot placed, String venue, List<EventSchedule> saved,
                              int[] orderCounter, List<String> autoArrangeFails,
                              List<String> warnings, double compressionWarnRatio,
                              Map<Long, List<int[]>> busy, String laneStyleRule) {
        boolean needPrelim = u.track && Boolean.TRUE.equals(u.event.getNeedHeats());
        List<String> local = new ArrayList<>();
        EventSchedule s = buildScheduleRow(u, placed.window, placed.startMinute, u.duration, venue,
                orderCounter, local, 1, 1, needPrelim);
        // B05/U07 + U24/B21：逐条压缩告警只保留「项目显式配了 maxDurationMinutes 而被压」的情形——
        // 那是用户自己设的上限，值得逐项确认；容量不足造成的等比压缩由 autoSchedule 汇总成
        // 「一池一条」的可执行告警，避免十几条重复文案把真正的业务问题淹没。
        if (u.explicitMaxDuration > 0 && u.rawDuration > u.explicitMaxDuration) {
            local.add(String.format("⚠️ 项目时长上限告警：「%s」（%s）预计需 %d 分钟，受该项目显式上限 "
                            + "maxDurationMinutes=%d 约束压缩为 %d 分钟（约 %.0f%%）；如现场时间充足可上调该上限",
                    u.event.getName(), u.grade == null ? "不分年级" : u.grade,
                    u.rawDuration, u.explicitMaxDuration, u.duration,
                    u.explicitMaxDuration * 100.0 / u.rawDuration));
        }
        warnings.addAll(local);
        saved.add(scheduleRepository.save(s));

        // U23/B20：登记本单元占用的时间段，供后续单元做兼项冲突规避（绝对分钟便于跨天比较）
        if (!u.athleteIds.isEmpty()) {
            int absStart = placed.window.day * 1440 + placed.startMinute;
            int[] span = {absStart, absStart + u.duration};
            for (Long aid : u.athleteIds) {
                busy.computeIfAbsent(aid, k -> new ArrayList<>()).add(span);
            }
        }

        // 径赛与田赛都自动生成道次/组次编排：田赛按「项目内并发/工位数」分组成次（X 人一组），
        // 与手动道次编排的 resolveLanes 默认工位数(8) 保持一致，不再退化成每人一组。
        if (u.participants > 0) {
            u.arranged = autoArrangeFor(u, autoArrangeFails, laneStyleRule);
        }
    }

    /**
     * 为单个单元（径赛或田赛）自动生成道次/组次（先清理该 事件×年级×性别 的旧记录再重排）：
     * <ul>
     *   <li>needHeats（需预赛）项目 → 生成<b>预赛</b>道次（round=preliminary），
     *       录入预赛成绩并计算晋级后由 {@code computeQualifiers} 追加决赛道次与独立决赛赛程条目；</li>
     *   <li>其余项目（含田赛）→ 直接生成<b>决赛</b>道次（round=final）。田赛按 {@code concurrencyOf} 返回的
     *       项目内并发/工位数分组（缺配置时默认 8 人一组），与手动道次编排的 resolveLanes 保持一致。</li>
     * </ul>
     *
     * @return 成功生成的 性别组 数量（男/女各计 1）
     */
    public int autoArrangeFor(Unit u, List<String> arrFails, String laneStyleRule) {
        Event e = u.event;
        int lanes = ScheduleAnalysisMath.concurrencyOf(e);
        // 轮次判定与 saveSchedule 的赛程条目保持一致：仅径赛按 needHeats 走预赛，田赛恒为决赛（单轮组次）。
        // 旧实现只看 needHeats（该字段对所有项目默认 true），导致田赛被错误判为预赛、与赛程条目的 final 不一致。
        String round = (u.track && Boolean.TRUE.equals(e.getNeedHeats()))
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
                // laneStyleRule 非空 = 调用方（AI 模式）指定了道次款型，交给 ArrangementService 解析；
                // 传 null 保持既有口径（班级均衡款型）。AI 款型不可用时 ArrangementService 自动回退成绩种子。
                arrangementService.arrange(e.getId(), u.grade, g, lanes,
                        laneStyleRule == null ? null : Map.of("styleRule", laneStyleRule), round);
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
