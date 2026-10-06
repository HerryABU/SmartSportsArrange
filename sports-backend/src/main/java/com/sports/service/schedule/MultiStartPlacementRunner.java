package com.sports.service.schedule;

import com.sports.repository.event.EventScheduleRepository;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import com.sports.schedule.opt.solver.Placement;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.arrange.ConflictService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sports.schedule.support.ScheduleSupport.sameGrade;

/**
 * 多趟放置择优 + 真实冲突精修（从 {@code ScheduleService.autoSchedule} 抽出）。
 *
 * <p>职责：把「同一批单元用不同放置顺序排多趟、保留残余兼项冲突最少的那一趟」这件事做完整，
 * 并把最终最优的一趟<b>落到库里</b>（每趟都是「删干净 → 重排 → 即时落库」，所以
 * 「保证最后一次落库的是最优趟」本身就是交付口径）。</p>
 *
 * <p>为什么放置顺序会影响冲突总数：先排「参与人数多 / 兼项度高」的项目，能让它们先占住无冲突
 * 时段，后续项目据此避让（busy 记账按放置顺序累积）。这是一个纯启发式维度，跑多种策略并择优
 * 比任何单一策略都稳。</p>
 *
 * <p>★ 口径一致性（2026-09-27，「越消解冲突反而越多」的主根因）：
 * 每趟 {@link #runPass} 落库的都是「无决赛条目」的中间态（{@code deleteAllSchedules} 把决赛条目
 * 一并删了，决赛条目要等 {@code ArrangementService.restoreFinalScheduleRows} 从编排表补回）。
 * 若在这一步直接 {@code countConflicts} 会<b>系统性低估</b>：决赛条目按「预赛结束 + 45min」追加，
 * 与重排后的其它项目时间窗紧贴/重叠，会制造新的兼项冲突，而这部分从未被任何一趟的评估看见——
 * 于是循环内选出的「最优趟」在最终补回决赛条目后冲突跳升。
 * 修复：初值、每趟、重跑最优趟三处都<b>先补回决赛条目再计数</b>，保证「评估口径 ≡ 交付口径」。</p>
 */
@Slf4j
public class MultiStartPlacementRunner {

    /** 无限轮模式的安全上限：无论如何不超过这么多趟，避免极端数据下死循环。 */
    public static final int UNLIMITED_PASS_CAP = 150;
    /** 无限轮模式的收敛判据：连续这么多趟残余冲突都不再下降，即认定已收敛到该启发式下最低。 */
    public static final int UNLIMITED_CONVERGE_STALE = 16;

    private final EventScheduleRepository scheduleRepository;
    private final ArrangementService arrangementService;
    private final ConflictService conflictService;
    private final ScheduleBuildComponent buildComponent;
    private final SchedulePlacementComponent placementComponent;

    public MultiStartPlacementRunner(EventScheduleRepository scheduleRepository,
                                     ArrangementService arrangementService,
                                     ConflictService conflictService,
                                     ScheduleBuildComponent buildComponent,
                                     SchedulePlacementComponent placementComponent) {
        this.scheduleRepository = scheduleRepository;
        this.arrangementService = arrangementService;
        this.conflictService = conflictService;
        this.buildComponent = buildComponent;
        this.placementComponent = placementComponent;
    }

    /**
     * 一趟放置所需的全部环境。
     *
     * <p>用 record 打包而不是把 18 个参数在「搜索循环 → 单趟放置」之间来回传：这些量在整轮搜索里
     * 恒定不变（唯一变化的是放置顺序），打包后新增环境项只需改这一处。</p>
     */
    public record PassEnv(
            List<Unit> units,
            List<Window> windows,
            int trackSlots,
            int fieldSlots,
            String mainVenue,
            List<String> fieldVenues,
            String mainVenueCode,
            Set<String> fieldVenueCodes,
            Map<String, String> codeToName,
            Map<String, Integer> codeToParallelMax,
            Map<Long, String> event2Group,
            int defaultInterval,
            int minInterval,
            double compressionWarnRatio,
            Map<Unit, Placement> solvedPlacement,
            Set<Long> eventsWithRegs,
            Map<Long, List<int[]>> eventBlocked,
            String laneStyleRule) {
    }

    /** 搜索结论：最优趟 + 它的顺序/策略名 + 观测数据（直接喂 {@code portfolioInfo}）。 */
    public record SearchOutcome(
            PlacementPassResult best,
            List<Integer> bestOrder,
            String winningStrategy,
            int passesRun,
            boolean unlimited,
            boolean converged,
            Map<String, Object> realRefineInfo) {
    }

    /**
     * 多趟放置择优 + 真实冲突精修。
     *
     * @param hardCap        放置趟数上限（无限轮模式下为 {@link #UNLIMITED_PASS_CAP}）
     * @param unlimited      是否无限轮模式（按收敛判据提前结束）
     * @param convergeStale  无限轮模式的收敛阈值
     */
    public SearchOutcome search(PassEnv env, int hardCap, boolean unlimited, int convergeStale) {
        List<Unit> units = env.units();
        PlacementPassResult best = null;
        List<Integer> bestOrder = null;
        String winningStrategy = null;
        boolean bestIsLastExecuted = false;
        int stale = 0;            // 连续未改进趟数（无限轮模式的收敛判据）
        int passesRun = 0;

        for (int p = 0; p < hardCap; p++) {
            List<Integer> order = MultiStartPlacementStrategy.strategyAt(units, p);
            String name = MultiStartPlacementStrategy.strategyNameAt(p);
            PlacementPassResult r = runPass(env, order);
            passesRun++;
            if (best == null || MultiStartPlacementStrategy.passIsBetter(r, best)) {
                best = r;
                bestOrder = order;
                winningStrategy = name;
                bestIsLastExecuted = true;
                stale = 0;
            } else {
                bestIsLastExecuted = false;
                stale++;
            }
            // 残余冲突已归零，无需再试（自适应提前结束）
            if (best.conflictStat[1] <= 0) {
                break;
            }
            // 无限轮模式：连续 convergeStale 趟残余冲突都不再下降 → 已收敛到该启发式下最低，停止
            if (unlimited && stale >= convergeStale) {
                break;
            }
        }

        // ===== U33/B30 增强：用「真实事后冲突数」(与 GET /api/arrange/conflicts 同口径) 做精修 =====
        // Phase 1 用的是放置期内存计数（conflictStat），可能与事后检测口径存在偏差；这里用真实口径
        // 重新评估每趟落库后的冲突数并继续扰动，直到真实冲突归零或连续 stale 趟不再下降（收敛）。
        // 尊重求解器「纪律」：本段纯属服务层编排，不触碰纯求解器（RuleBasedScheduler / ScheduleOptimizer）内部。
        try {
            arrangementService.restoreFinalScheduleRows();
        } catch (Exception ex) {
            log.warn("精修初值评估前补回决赛条目失败，将按无决赛条目口径评估: {}", ex.getMessage());
        }
        int[] realBest = conflictService.countConflicts();
        int realStale = 0;
        final int refineCap = unlimited ? 60 : Math.min(24, Math.max(6, hardCap));
        final int realConvergeStale = 8;
        if (realBest[0] > 0) {
            for (int q = 0; q < refineCap; q++) {
                List<Integer> order = MultiStartPlacementStrategy.orderByShuffle(
                        units.size(), 0xC0FFEEL + ((long) q) * 0x85ebca6bL);
                PlacementPassResult r = runPass(env, order);
                try {
                    arrangementService.restoreFinalScheduleRows();   // ★ 补回决赛条目再评估（口径＝交付）
                } catch (Exception ex) {
                    log.warn("精修趟 {} 补回决赛条目失败: {}", q, ex.getMessage());
                }
                int[] rc = conflictService.countConflicts();
                boolean better = rc[0] < realBest[0] || (rc[0] == realBest[0] && rc[1] < realBest[1]);
                if (better) {
                    best = r;
                    bestOrder = order;
                    winningStrategy = "refine#" + q;
                    realBest = rc;
                    realStale = 0;
                    bestIsLastExecuted = true;   // 本趟是最优且已即时落库
                    if (rc[0] == 0) {
                        break;                   // 真实冲突已归零，无需再试
                    }
                } else {
                    bestIsLastExecuted = false;  // 本趟非最优，DB 当前是更差的一趟
                    realStale++;
                    if (realStale >= realConvergeStale) {
                        break;                   // 已收敛到该扰动下最低
                    }
                }
            }
        }
        Map<String, Object> realRefineInfo = Map.of(
                "cap", refineCap, "finalRealTotal", realBest[0], "finalRealSevere", realBest[1]);

        // 保证最终落库的是最优一趟：若最优不是最后执行的，再跑一次把它落库
        if (!bestIsLastExecuted) {
            best = runPass(env, bestOrder);
            try {
                arrangementService.restoreFinalScheduleRows();   // ★ 重跑最优趟后同样补回（口径＝交付）
            } catch (Exception ex) {
                log.warn("重跑最优趟后补回决赛条目失败: {}", ex.getMessage());
            }
        }
        return new SearchOutcome(best, bestOrder, winningStrategy, passesRun,
                unlimited, unlimited && stale >= convergeStale, realRefineInfo);
    }

    /**
     * 单趟赛程放置：独立 {@code deleteAllSchedules} + 按给定单元顺序放置，返回本趟结果。
     *
     * <p>每趟都即时落库（{@code placementComponent} 内部 save），故「保留最优一趟」只需保证
     * 最后落库的是最优即可。</p>
     */
    public PlacementPassResult runPass(PassEnv env, List<Integer> order) {
        List<Unit> units = env.units();
        Map<Long, String> event2Group = env.event2Group();
        Set<Long> eventsWithRegs = env.eventsWithRegs();

        PlacementPassResult r = new PlacementPassResult();
        scheduleRepository.deleteAllSchedules();
        // 每趟重建池（游标状态不可复用）+ 专用池表（resolvePool 会按需新建并缓存）
        Pool trackPool = new Pool("径赛", env.trackSlots(), new ArrayList<>(List.of(env.mainVenue())));
        Pool fieldPool = new Pool("田赛", env.fieldSlots(), env.fieldVenues());
        Map<String, Pool> dedicatedPools = new LinkedHashMap<>();
        int[] orderCounter = {1};
        Set<Integer> done = new HashSet<>();
        Map<Long, List<int[]>> busy = new HashMap<>();

        for (int pos = 0; pos < order.size(); pos++) {
            int i = order.get(pos);
            Unit u = units.get(i);
            if (u.participants <= 0) {
                // U22/B19：只有项目「整体无报名」才告警；跨年级空单元（本就不该在该年级进行）静默跳过
                if (!eventsWithRegs.contains(u.event.getId())) {
                    r.warnings.add(String.format("项目「%s」（%s）暂无有效报名，已跳过",
                            u.event.getName(), u.grade == null ? "不分年级" : u.grade));
                }
                done.add(i);
                continue;
            }

            Pool pool = buildComponent.resolvePool(u, trackPool, fieldPool, env.mainVenueCode(),
                    env.fieldVenueCodes(), env.codeToName(), env.codeToParallelMax(), dedicatedPools,
                    env.trackSlots(), env.fieldSlots());
            // 分组：田赛或「小组合作」项目按 event2Group 分组（合作项目哪怕是径赛也并入同组并行）
            String group = event2Group.get(u.event.getId());

            if (group == null) {
                // 普通单元：占用并发池中最空闲的一个槽位
                placementComponent.placeOne(u, pool, env.windows(), env.defaultInterval(), env.minInterval(),
                        env.compressionWarnRatio(), r.saved, r.warnings, orderCounter, r.autoArrangeFails, busy,
                        r.conflictStat, env.solvedPlacement(), env.eventBlocked(), env.laneStyleRule());
                r.autoArrangeOk += u.arranged;
                done.add(i);
            } else {
                // 田赛分组：同组 + 同年级 的单元安排在同一时段并行进行
                List<Integer> batch = new ArrayList<>();
                for (int pos2 = 0; pos2 < order.size(); pos2++) {
                    int j = order.get(pos2);
                    if (done.contains(j)) {
                        continue;
                    }
                    Unit v = units.get(j);
                    // 同组并行：同年级 + 同组；合作项目允许径赛/田赛混合（场地池各自解析，仅时间协调）
                    if (!sameGrade(v.grade, u.grade)) {
                        continue;
                    }
                    if (!group.equals(event2Group.get(v.event.getId()))) {
                        continue;
                    }
                    if (v.participants <= 0) {
                        // U22/B19：同上——只有项目整体无报名才告警，跨年级空单元静默跳过
                        if (!eventsWithRegs.contains(v.event.getId())) {
                            r.warnings.add(String.format("项目「%s」（%s）暂无有效报名，已跳过",
                                    v.event.getName(), v.grade == null ? "不分年级" : v.grade));
                        }
                        done.add(j);
                        continue;
                    }
                    batch.add(j);
                }
                if (batch.isEmpty()) {
                    done.add(i);
                    continue;
                }
                // Bug1 修复：组内各单元先各自解析场地池（含 defaultVenueCode 绑定的专用池），
                // 场地输出取各自池的场地名；时间协调跨池进行（同一时刻同时开赛）。
                List<Pool> unitPools = new ArrayList<>();
                for (Integer idx : batch) {
                    unitPools.add(buildComponent.resolvePool(units.get(idx), trackPool, fieldPool, env.mainVenueCode(),
                            env.fieldVenueCodes(), env.codeToName(), env.codeToParallelMax(), dedicatedPools,
                            env.trackSlots(), env.fieldSlots()));
                }
                placementComponent.placeBatch(batch, units, unitPools, env.windows(), env.defaultInterval(),
                        env.minInterval(), env.compressionWarnRatio(), group, r.saved, r.warnings, orderCounter,
                        r.autoArrangeFails, busy, r.conflictStat, env.solvedPlacement(), env.eventBlocked(),
                        env.laneStyleRule());
                for (Integer idx : batch) {
                    r.autoArrangeOk += units.get(idx).arranged;
                    done.add(idx);
                }
            }
        }
        return r;
    }
}
