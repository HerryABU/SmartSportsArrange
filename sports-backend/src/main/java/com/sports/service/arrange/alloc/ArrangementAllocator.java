package com.sports.service.arrange.alloc;

import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.schedule.core.math.ScheduleAnalysisMath;
import com.sports.schedule.exact.HungarianAssignment;
import com.sports.schedule.rule.grouping.SnakeGrouping;
import com.sports.schedule.rule.style.ArrangeStyle;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 入组 / 分道算法（2026-10-06 从 {@code ArrangementService} 抽出）。
 *
 * <p>本类只做一件事：<b>给定一批运动员 + 道次数，产出一份合法的入组结果</b>。
 * 不碰数据库、不碰事务、不碰视图装配 —— 输入输出全是内存对象，因此可以脱离
 * Spring 容器单测。这正是它被抽出来的主要理由：原先它埋在 3000 行的服务里，
 * 想单独验证一条款型的合法性，必须先把整个服务连同 15 个依赖装配起来。</p>
 *
 * <p>三个入组款型共用同一条<b>公共下游</b> {@link #assignLanes}：</p>
 * <ul>
 *   <li>{@link #allocate} —— 班级均衡（硬约束「同组不同班」+ 对抗式随机重排）；</li>
 *   <li>{@link #allocatePlan} —— 规划层（贪心打底 + 显式代价局部搜索，确定性）；</li>
 *   <li>{@link #allocateSnake} —— 蛇形 / 种子蛇形（S 形分散，<b>有意</b>放开同组不同班）。</li>
 * </ul>
 *
 * <p>分道必须共用一处：它是 L1~L4 四档的公共下游，若各款型各写一份，就会出现
 * 「同一份报名用不同款型排，道次公平性却不一样」这种说不清的问题。</p>
 */
public class ArrangementAllocator {

    private final RuleInjectionScorer ruleScorer;

    public ArrangementAllocator(RuleInjectionScorer ruleScorer) {
        this.ruleScorer = ruleScorer;
    }

    /** 田赛缺省工位数（X 人一组）：与 ScheduleAnalysisMath.DEFAULT_FIELD_GROUP 单一真相源保持一致 */
    private static final int DEFAULT_FIELD_GROUP = ScheduleAnalysisMath.DEFAULT_FIELD_GROUP;

    /** 把「组 → 单元列表」矩阵打包成结果对象。 */
    private static AllocationResult result(int heats, List<Arrangement>[] matrix,
                                           List<String> warnings) {
        List<List<Arrangement>> out = new ArrayList<>(heats);
        for (int h = 0; h < heats; h++) {
            out.add(matrix[h]);
        }
        return new AllocationResult(heats, out, warnings);
    }

    /**
     * 核心分配：同一组不能同班。
     *
     * <p>组数 = max(ceil((自动池 + 锁定项)/道数), 最大单班人数(含锁定项), 锁定项最大组号)，
     * 保证每个班的运动员可分到不同组，且自动编排不会把组号回退到锁定项之前。
     * 贪心按「当前组人数最少」选择，命中同班已占用的组则跳过。</p>
     *
     * <p><b>人工锁定项（U12/B18）</b>：先按人工指定的 (heat, lane) 预置进矩阵并占位——
     * 该组占用数 +1、该班标记该组已用、该道次标记已占。此后自动分配只能落到剩余位置，
     * 因此不会再出现「自动项与人工锁定项同组同道」的重复落位（旧实现只把锁定运动员
     * 排除出池，却没占位，重排后组号从 1 重算，锁定道次可能被新项顶掉）。</p>
     */
    public AllocationResult allocate(Event event, List<Athlete> athletes, int lanes, List<Arrangement> locked,
                                Long seed, boolean lottery, String styleRule, Map<Long, Integer> seedRank) {
        int n = athletes.size();
        List<String> warnings = new ArrayList<>();
        List<Arrangement> locks = locked == null ? List.of() : locked;
        Random rnd = seed != null ? new Random(seed) : null;

        // 「蛇形」款型（蛇形排布 / 种子蛇形）：确定性 S 形分散，不依赖随机、不强制同组不同班
        if (isSnakeRule(styleRule)) {
            return allocateSnake(event, athletes, lanes, locks, warnings, styleRule, seedRank);
        }

        // 「规划层」款型：贪心打底 + 显式代价的局部搜索。
        // ⚠️ 它**强制「同组不同班」**（与班级均衡同口径），所以必须走这条分支，
        //    不能混进蛇形家族 —— 蛇形有意放开「同组不同班」，两者的硬约束不同。
        if (ArrangeStyle.of(styleRule).isPlan()) {
            return allocatePlan(event, athletes, lanes, locks, warnings);
        }

        // 按班级分组（班级缺失归为 0）
        Map<Long, List<Athlete>> byClass = athletes.stream()
                .collect(Collectors.groupingBy(
                        a -> AthleteKeys.classIdOf(a),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // 锁定项按班级计数（决定组数下界与「同组不同班」是否可满足）
        Map<Long, Integer> lockedPerClass = new HashMap<>();
        int maxLockedHeat = 0;
        for (Arrangement lock : locks) {
            if (lock.getAthlete() != null) {
                lockedPerClass.merge(AthleteKeys.classIdOf(lock.getAthlete()), 1, Integer::sum);
            }
            maxLockedHeat = Math.max(maxLockedHeat, lock.getHeat() == null ? 0 : lock.getHeat());
        }

        Set<Long> allClasses = new LinkedHashSet<>(byClass.keySet());
        allClasses.addAll(lockedPerClass.keySet());

        int maxClassSize = 0;
        for (Long cid : allClasses) {
            maxClassSize = Math.max(maxClassSize,
                    byClass.getOrDefault(cid, List.of()).size() + lockedPerClass.getOrDefault(cid, 0));
        }

        int total = n + locks.size();
        int minHeats = (int) Math.ceil((double) total / Math.max(1, lanes));
        int heats = Math.max(Math.max(minHeats, maxClassSize), maxLockedHeat);
        // 组数上限保护：若道次很少而班级很大，组数可能过大，放宽「同组不同班」并警告
        if (heats > Math.max(12, minHeats * 2)) {
            warnings.add(String.format("班级人数差异过大（最大班%d人），为满足「同组不同班」需排%d组，建议减少该班报名人数",
                    maxClassSize, heats));
        }
        if (heats <= 0) heats = 1;

        int[] occupancy = new int[heats];
        // 记录每个班已在哪些组出现
        Map<Long, boolean[]> classHeatFlags = new HashMap<>();
        for (Long cid : allClasses) {
            classHeatFlags.put(cid, new boolean[heats]);
        }
        // 记录每个班在各道次的使用次数（preferDiffLane 软约束）
        Map<Long, int[]> classLaneUse = new HashMap<>();
        for (Long cid : allClasses) {
            classLaneUse.put(cid, new int[lanes]);
        }
        // 每条道是否已被占用（锁定项先占，自动项避开）
        boolean[][] laneTaken = new boolean[heats][lanes];

        @SuppressWarnings("unchecked")
        List<Arrangement>[] matrix = new List[heats];
        for (int h = 0; h < heats; h++) matrix[h] = new ArrayList<>();

        // 阶段零：预置人工锁定项 —— 占住其 (组, 道)，自动分配必须避开
        for (Arrangement lock : locks) {
            int hIdx = lock.getHeat() != null ? lock.getHeat() - 1 : 0;
            if (hIdx < 0 || hIdx >= heats) {
                warnings.add("人工锁定项组号越界（第" + lock.getHeat() + "组），已跳过占位");
                continue;
            }
            int lane = lock.getLane() != null ? lock.getLane() : 0;
            if (lane >= 1 && lane <= lanes && laneTaken[hIdx][lane - 1]) {
                warnings.add(String.format("人工锁定项道次冲突：第%d组第%d道被两条锁定项同时占用",
                        hIdx + 1, lane));
            }
            matrix[hIdx].add(lock);
            occupancy[hIdx]++;
            Long cid = lock.getAthlete() != null ? AthleteKeys.classIdOf(lock.getAthlete()) : 0L;
            boolean[] flags = classHeatFlags.get(cid);
            if (flags == null) { flags = new boolean[heats]; classHeatFlags.put(cid, flags); }
            flags[hIdx] = true;
            if (lane >= 1 && lane <= lanes) {
                laneTaken[hIdx][lane - 1] = true;
                int[] laneUse = classLaneUse.get(cid);
                if (laneUse == null) { laneUse = new int[lanes]; classLaneUse.put(cid, laneUse); }
                laneUse[lane - 1]++;
            }
        }

        List<Map.Entry<Long, List<Athlete>>> sortedClasses = byClass.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toList());
        // 对抗式：随机化班级处理顺序（仍保证可行性，因为 heats>=maxClassSize）
        if (rnd != null) Collections.shuffle(sortedClasses, rnd);

        // 阶段一：分班入组（同组不同班）
        for (Map.Entry<Long, List<Athlete>> entry : sortedClasses) {
            Long classId = entry.getKey();
            boolean[] usedHeat = classHeatFlags.get(classId);
            List<Athlete> members = entry.getValue();
            if (rnd != null) {
                members = new ArrayList<>(members);
                Collections.shuffle(members, rnd);
            }

            for (Athlete athlete : members) {
                int bestHeat = -1;
                int minOcc = Integer.MAX_VALUE;
                List<Integer> candidates = new ArrayList<>();
                for (int h = 0; h < heats; h++) {
                    if (usedHeat[h]) continue;          // 硬约束：同班同组禁止
                    if (occupancy[h] >= lanes) continue;
                    if (occupancy[h] < minOcc) {
                        minOcc = occupancy[h];
                        candidates.clear();
                        candidates.add(h);
                    } else if (occupancy[h] == minOcc) {
                        candidates.add(h);
                    }
                }
                if (candidates.isEmpty()) {
                    // 理论不可达（heats>=maxClassSize）；保险兜底：挑人最少的组
                    for (int h = 0; h < heats; h++) {
                        if (occupancy[h] < lanes && (bestHeat < 0 || occupancy[h] < occupancy[bestHeat])) {
                            bestHeat = h;
                        }
                    }
                    if (bestHeat >= 0) {
                        warnings.add(String.format("无法满足「同组不同班」：%s 有同班同学挤在同一组", athlete.getName()));
                    } else {
                        warnings.add("无法为运动员 " + athlete.getName() + " 分配合适的组");
                        continue;
                    }
                } else {
                    // 规则注入优先：先取规则惩罚最小的候选；完全平局时才随机（保持对抗式重排能力）
                    bestHeat = ruleScorer.pickAmongCandidates(event, athlete, candidates, heats, rnd);
                }
                Arrangement arr = new Arrangement();
                arr.setAthlete(athlete);
                matrix[bestHeat].add(arr);
                occupancy[bestHeat]++;
                usedHeat[bestHeat] = true;
            }
        }

        // 阶段二：组内分道（抽签 / 匈牙利精确分道 —— 见 assignLanes 的说明）
        assignLanes(matrix, heats, lanes, laneTaken, classLaneUse, lottery, rnd);

        return result(heats, matrix, warnings);
    }


    /**
     * 组内分道（抽签 / 匈牙利精确分道）—— 从 {@link #allocate} 抽出，供各款型复用。
     *
     * <p><b>为什么必须抽出来</b>：分道口径若在各款型里各写一份，就会出现
     * 「同一份报名用不同款型排，道次公平性却不一样」这种说不清的问题。
     * 分道只认「该班已用该道的次数」这一个代价，与选哪款入组规则无关，
     * 所以它属于<b>公共下游</b>，不该跟着入组款型一起分叉。</p>
     *
     * @param matrix       组 → 单元列表（已落位者的 lane 可能已被锁定占位，只处理 lane == null 的）
     * @param laneTaken    已占用的 (组, 道) 标记
     * @param classLaneUse 班级 → 各道已用次数（软约束「同班道次错开」的累积口径）
     */
    private void assignLanes(List<Arrangement>[] matrix, int heats, int lanes,
                             boolean[][] laneTaken, Map<Long, int[]> classLaneUse,
                             boolean lottery, Random rnd) {
        for (int h = 0; h < heats; h++) {
            List<Arrangement> inHeat = matrix[h];
            if (inHeat.isEmpty()) continue;

            if (lottery) {
                // 抽签：运动员随机抽到道次（仅占用未被锁定占用的道次），实现 xxx、yyy 同组随机占位，
                // 而非按班级顺序固定 x 在 1 道、y 在 2 道
                List<Integer> freeLanes = new ArrayList<>();
                for (int l = 0; l < lanes; l++) if (!laneTaken[h][l]) freeLanes.add(l + 1);
                List<Arrangement> pending = inHeat.stream()
                        .filter(a -> a.getLane() == null).collect(Collectors.toList());
                List<Arrangement> shuffled = new ArrayList<>(pending);
                if (rnd != null) {
                    Collections.shuffle(shuffled, rnd);
                    Collections.shuffle(freeLanes, rnd);
                } else {
                    Collections.shuffle(shuffled);
                    Collections.shuffle(freeLanes);
                }
                int i = 0;
                for (Arrangement a : shuffled) {
                    int lane = freeLanes.get(i++);
                    a.setLane(lane);
                    laneTaken[h][lane - 1] = true;
                    Long cid = AthleteKeys.classIdOf(a.getAthlete());
                    classLaneUse.computeIfAbsent(cid, k -> new int[lanes])[lane - 1]++;
                }
                continue;
            }

            // 非抽签：用匈牙利算法对「组内分道」求精确最优——把运动员→道次建模为最小代价
            // 分配（代价 = 该班已用该道的次数），一次求出整组最优分道，而非逐道贪心。
            // 贪心的隐患：先分的班总能抢到「没怎么用过」的道，后分的班被逼到重复道次；
            // 匈牙利在整组层面同时决定所有道次，代价和严格 ≤ 贪心，且结果确定可复现。
            List<Arrangement> pending = inHeat.stream()
                    .filter(a -> a.getLane() == null).collect(Collectors.toList());
            if (!pending.isEmpty()) {
                List<Integer> free = new ArrayList<>();
                for (int l = 0; l < lanes; l++) {
                    if (!laneTaken[h][l]) free.add(l + 1);
                }
                int[] freeLanes = free.stream().mapToInt(Integer::intValue).toArray();

                long[][] cost = new long[pending.size()][freeLanes.length];
                for (int i = 0; i < pending.size(); i++) {
                    Long cid = AthleteKeys.classIdOf(pending.get(i).getAthlete());
                    int[] use = classLaneUse.computeIfAbsent(cid, k -> new int[lanes]);
                    for (int j = 0; j < freeLanes.length; j++) {
                        cost[i][j] = use[freeLanes[j] - 1];
                    }
                }
                int[] laneNos = HungarianAssignment.assignAthletesToLanes(cost, freeLanes);
                for (int i = 0; i < pending.size(); i++) {
                    Arrangement a = pending.get(i);
                    int laneNo = laneNos[i];
                    a.setLane(laneNo);
                    laneTaken[h][laneNo - 1] = true;
                    Long cid = AthleteKeys.classIdOf(a.getAthlete());
                    classLaneUse.computeIfAbsent(cid, k -> new int[lanes])[laneNo - 1]++;
                }
            }
        }
    }


    /**
     * 「规划层」款型（{@link ArrangeStyle#PLAN}）：贪心打底 + **显式代价的局部搜索**。
     *
     * <h2>为什么要单独一款</h2>
     *
     * <p>「班级均衡」只有一条贪心规则「放进人最少的组」。它在**人数分布均匀**时够用，
     * 但在「少数大班 + 多数小班」这类真实报名结构下会走歪：大班被硬性摊到各组后，
     * 小班再填进去就只剩「塞哪个组」这一问，而贪心只认当前最空的那个 ——
     * 前几步一旦把分布走歪，后面再也纠不回来（空道次与人数不均衡被固化）。</p>
     *
     * <p>规划层把这件事写成**代价**并用局部搜索去压：</p>
     *
     * <pre>
     * 代价 = 3 × 组人数不均衡            （max 组人数 − min 组人数；越小越公平）
     *      +  2 × 同班相邻组配对数        （同班两人连着两趟上场 = 连轴转；越小越好）
     * </pre>
     *
     * <p>先与班级均衡同款的贪心打底（保证一开始就有一份合法解），
     * 再反复尝试**搬迁**（一个人换组）与**交换**（两人互换组）——
     * <b>只接受代价严格下降且不破坏硬约束</b>的移动。
     * 于是它同「班级均衡」一样必定合法，但分组更均衡、更不会让学生连轴转。</p>
     *
     * <p>⚠️ 两条硬约束在每次试移动时都要重查，不能只看「人数」：
     * ① 同组不同班（同班两人不得同组）；② 组人数不得超过道次数。
     * 只查其一会出现「换完更均衡，但把同班凑到一组」这种非法解 ——
     * 而校验在外部（{@code validatePlacement}）才做的话，表现为「重排多轮仍违反」。</p>
     *
     * <p>确定性：无随机、无抽签，同报名必得同分组（与「班级均衡」一致）。</p>
     */
    private AllocationResult allocatePlan(Event event, List<Athlete> athletes, int lanes,
                                   List<Arrangement> locks, List<String> warnings) {
        int n = athletes.size();
        List<Arrangement> lockList = locks == null ? List.of() : locks;

        Map<Long, List<Athlete>> byClass = athletes.stream()
                .collect(Collectors.groupingBy(
                        a -> AthleteKeys.classIdOf(a),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<Long, Integer> lockedPerClass = new HashMap<>();
        int maxLockedHeat = 0;
        for (Arrangement lock : lockList) {
            if (lock.getAthlete() != null) {
                lockedPerClass.merge(AthleteKeys.classIdOf(lock.getAthlete()), 1, Integer::sum);
            }
            maxLockedHeat = Math.max(maxLockedHeat, lock.getHeat() == null ? 0 : lock.getHeat());
        }
        Set<Long> allClasses = new LinkedHashSet<>(byClass.keySet());
        allClasses.addAll(lockedPerClass.keySet());

        int maxClassSize = 0;
        for (Long cid : allClasses) {
            maxClassSize = Math.max(maxClassSize,
                    byClass.getOrDefault(cid, List.of()).size() + lockedPerClass.getOrDefault(cid, 0));
        }

        int total = n + lockList.size();
        int minHeats = (int) Math.ceil((double) total / Math.max(1, lanes));
        // heats 与「班级均衡」同口径：既要装得下（按道次），又要满足「同组不同班」（按最大班）
        int heats = Math.max(Math.max(minHeats, maxClassSize), maxLockedHeat);
        if (heats > Math.max(12, minHeats * 2)) {
            warnings.add(String.format("班级人数差异过大（最大班%d人），为满足「同组不同班」需排%d组，建议减少该班报名人数",
                    maxClassSize, heats));
        }
        if (heats <= 0) heats = 1;
        // lambda 里要用 heats：必须先固化（heats 上面被重新赋值过，不是 effectively final）
        final int nHeats = heats;

        int[] occupancy = new int[heats];
        Map<Long, boolean[]> classHeatFlags = new HashMap<>();
        for (Long cid : allClasses) classHeatFlags.put(cid, new boolean[heats]);
        boolean[][] laneTaken = new boolean[heats][lanes];
        Map<Long, int[]> classLaneUse = new HashMap<>();
        for (Long cid : allClasses) classLaneUse.put(cid, new int[lanes]);

        @SuppressWarnings("unchecked")
        List<Arrangement>[] matrix = new List[heats];
        for (int h = 0; h < heats; h++) matrix[h] = new ArrayList<>();

        // 阶段零：预置人工锁定项（占位；自动分配必须避开）
        for (Arrangement lock : lockList) {
            int hIdx = lock.getHeat() != null ? lock.getHeat() - 1 : 0;
            if (hIdx < 0 || hIdx >= heats) {
                warnings.add("人工锁定项组号越界（第" + lock.getHeat() + "组），已跳过占位");
                continue;
            }
            int lane = lock.getLane() != null ? lock.getLane() : 0;
            if (lane >= 1 && lane <= lanes && laneTaken[hIdx][lane - 1]) {
                warnings.add(String.format("人工锁定项道次冲突：第%d组第%d道被两条锁定项同时占用",
                        hIdx + 1, lane));
            }
            matrix[hIdx].add(lock);
            occupancy[hIdx]++;
            Long cid = lock.getAthlete() != null ? AthleteKeys.classIdOf(lock.getAthlete()) : 0L;
            classHeatFlags.computeIfAbsent(cid, k -> new boolean[nHeats])[hIdx] = true;
            if (lane >= 1 && lane <= lanes) {
                laneTaken[hIdx][lane - 1] = true;
                classLaneUse.computeIfAbsent(cid, k -> new int[lanes])[lane - 1]++;
            }
        }

        // 阶段一：贪心打底（与「班级均衡」同口径：大类先排 → 放进人最少且未出现该班的组）
        List<Map.Entry<Long, List<Athlete>>> sortedClasses = byClass.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toList());
        for (Map.Entry<Long, List<Athlete>> entry : sortedClasses) {
            Long classId = entry.getKey();
            boolean[] usedHeat = classHeatFlags.get(classId);
            for (Athlete athlete : entry.getValue()) {
                int bestHeat = -1;
                int minOcc = Integer.MAX_VALUE;
                for (int h = 0; h < heats; h++) {
                    if (usedHeat[h] || occupancy[h] >= lanes) continue;
                    if (occupancy[h] < minOcc) {
                        minOcc = occupancy[h];
                        bestHeat = h;
                    }
                }
                if (bestHeat < 0) {
                    for (int h = 0; h < heats; h++) {
                        if (occupancy[h] < lanes && (bestHeat < 0 || occupancy[h] < occupancy[bestHeat])) {
                            bestHeat = h;
                        }
                    }
                }
                if (bestHeat < 0) {
                    warnings.add("无法为运动员 " + athlete.getName() + " 分配合适的组");
                    continue;
                }
                Arrangement arr = new Arrangement();
                arr.setAthlete(athlete);
                matrix[bestHeat].add(arr);
                occupancy[bestHeat]++;
                usedHeat[bestHeat] = true;
            }
        }

        // 阶段二：**显式代价的局部搜索**（搬迁 / 交换，只接受严格变好且合法的移动）
        localSearchHeats(matrix, heats, lanes, occupancy, classHeatFlags);

        // 阶段三：分道（与「班级均衡」同一套公共下游 —— 匈牙利精确分道）
        assignLanes(matrix, heats, lanes, laneTaken, classLaneUse, false, null);

        return result(heats, matrix, warnings);
    }


    /**
     * 规划层的**局部搜索**：搬迁（一人换组）+ 交换（两人换组），只接受代价严格下降的移动。
     *
     * <h2>代价（为什么是这两项）</h2>
     *
     * <pre>
     * 代价 = 3 × 组人数不均衡  +  2 × 同班相邻组配对数
     * </pre>
     *
     * <ul>
     *   <li><b>不均衡</b>：贪心只认「当前人最少的组」，是个**近视**规则 ——
     *       真实报名结构（少数大班 + 多数小班）下前几步走歪就再也纠不回来；</li>
     *   <li><b>同班相邻组</b>：同班两人落在第 1、2 组 = 连着两趟上场，学生要连轴转。
     *       这一项**交换才动得了**（人数守恒、不均衡不变），
     *       所以它是「为什么必须有交换」的理由 —— 只做搬迁的话这一项永远优化不到。</li>
     * </ul>
     *
     * <p>⚠️ 刻意没有「空道次浪费」这一项：本款的组数是**推导出来的常量**
     * （{@code max(⌈总人数/道次⌉, 最大班人数)}），所有能排下的运动员都会被排下，
     * 于是「占用道次总数」在任何布局下都相等 —— 把它写进代价是个**恒为常数的项**，
     * 看起来在优化，实际什么都没优化。</p>
     *
     * <p>确定性：不做任何随机化。同一份报名重排两次必须得到同一分组
     * （AI 派遣款型之所以要传种子，正是因为它随机；规划层不需要）。</p>
     *
     * <p>⚠️ 每次试移动都**重查两条硬约束**（同班不得同组 / 组不超道次），
     * 而不是「先移动、再交给外部校验」—— 后者会把非法解留在中间状态里，
     * 一旦循环因为别的原因提前退出，就交付了一个非法分组。</p>
     */
    private void localSearchHeats(List<Arrangement>[] matrix, int heats, int lanes,
                                  int[] occupancy, Map<Long, boolean[]> classHeatFlags) {
        int adjTotal = 0;
        for (boolean[] f : classHeatFlags.values()) {
            adjTotal += adjacentPairs(f);
        }
        final int maxRounds = 100;
        for (int round = 0; round < maxRounds; round++) {
            final int base = 3 * spread(occupancy) + 2 * adjTotal;
            boolean moved = false;

            // ---- ① 搬迁：把一人从组 a 挪到组 b（只改一个人，代价增量可精确算）----
            outer:
            for (int a = 0; a < heats; a++) {
                if (occupancy[a] <= 0) continue;
                for (Arrangement arr : new ArrayList<>(matrix[a])) {
                    Long cid = AthleteKeys.classIdOf(arr.getAthlete());
                    boolean[] f = classHeatFlags.get(cid);
                    if (f == null) continue;                       // 没有班级标记 → 不冒风险动它
                    for (int b = 0; b < heats; b++) {
                        if (b == a || occupancy[b] >= lanes) continue;
                        if (f[b]) continue;                        // 硬约束：同班不得同组
                        int oldAdj = adjacentPairs(f);
                        f[a] = false;
                        f[b] = true;
                        occupancy[a]--;
                        occupancy[b]++;
                        int cost = 3 * spread(occupancy) + 2 * (adjTotal - oldAdj + adjacentPairs(f));
                        if (cost < base) {
                            matrix[a].remove(arr);
                            matrix[b].add(arr);
                            adjTotal += adjacentPairs(f) - oldAdj;
                            moved = true;
                            break outer;
                        }
                        // 回滚（试移动失败必须还原所有共享状态，否则搜索会「漂移」）
                        f[a] = true;
                        f[b] = false;
                        occupancy[a]++;
                        occupancy[b]--;
                    }
                }
            }
            if (moved) continue;

            // ---- ② 交换：两人互换组（人数守恒 → 不均衡不变；只能靠「相邻组」这一项获益）----
            outer2:
            for (int a = 0; a < heats; a++) {
                for (Arrangement xa : new ArrayList<>(matrix[a])) {
                    Long ca = AthleteKeys.classIdOf(xa.getAthlete());
                    boolean[] fa = classHeatFlags.get(ca);
                    if (fa == null) continue;
                    for (int b = a + 1; b < heats; b++) {
                        for (Arrangement xb : new ArrayList<>(matrix[b])) {
                            Long cb = AthleteKeys.classIdOf(xb.getAthlete());
                            if (ca.equals(cb)) continue;           // 同班互换：分布不变，无意义
                            boolean[] fb = classHeatFlags.get(cb);
                            if (fb == null) continue;
                            if (fa[b] || fb[a]) continue;          // 换过去会「同班同组」，硬约束
                            int oa = adjacentPairs(fa);
                            int ob = adjacentPairs(fb);
                            fa[a] = false;
                            fa[b] = true;
                            fb[b] = false;
                            fb[a] = true;
                            int na = adjacentPairs(fa);
                            int nb = adjacentPairs(fb);
                            int cost = 3 * spread(occupancy)
                                    + 2 * (adjTotal - oa - ob + na + nb);   // 人数守恒 → 不均衡项不变
                            if (cost < base) {
                                matrix[a].remove(xa);
                                matrix[b].remove(xb);
                                matrix[a].add(xb);
                                matrix[b].add(xa);
                                adjTotal += (na - oa) + (nb - ob);
                                moved = true;
                                break outer2;
                            }
                            // 回滚
                            fa[a] = true;
                            fa[b] = false;
                            fb[b] = true;
                            fb[a] = false;
                        }
                    }
                }
            }
            if (!moved) break;                                     // 收敛：搬迁与交换都动不了
        }
    }


    /** 组人数极差（越小越均衡）。 */
    private static int spread(int[] occupancy) {
        int maxOcc = 0;
        int minOcc = Integer.MAX_VALUE;
        for (int occ : occupancy) {
            maxOcc = Math.max(maxOcc, occ);
            minOcc = Math.min(minOcc, occ);
        }
        return maxOcc - (minOcc == Integer.MAX_VALUE ? 0 : minOcc);
    }


    /** 某班落在**相邻两组**的配对数（越小越不会「连轴转」）。 */
    private static int adjacentPairs(boolean[] presentInHeat) {
        int n = 0;
        for (int h = 0; h + 1 < presentInHeat.length; h++) {
            if (presentInHeat[h] && presentInHeat[h + 1]) n++;
        }
        return n;
    }


    /**
     * 「蛇形」款型（蛇形排布 / 种子蛇形）：确定性排序后用 {@link SnakeGrouping} 把运动员
     * S 形分散到各组，组内道次按蛇形次序落位。
     *
     * <ul>
     *   <li><b>snake（蛇形排布）</b>：按「年级 → 班级 → id」排序，使各组年级/班级分布均衡；</li>
     *   <li><b>snakeSeed（种子蛇形）</b>：按种子名次（{@code seedRank}，1=最快；含预赛成绩/已有成绩）
     *       排序，使各组种子强度均衡；无成绩者排最后（退回报名序）。</li>
     * </ul>
     *
     * <p>与默认「班级均衡」（同组不同班 + 匈牙利分道）互斥，同属 。人工锁定项作为已占位参与：
     * 蛇形目标组满员时环形顺延。</p>
     */
    private AllocationResult allocateSnake(Event event, List<Athlete> athletes, int lanes,
                                    List<Arrangement> locks, List<String> warnings,
                                    String styleRule, Map<Long, Integer> seedRank) {
        int total = athletes.size() + locks.size();
        int minHeats = (int) Math.ceil((double) total / Math.max(1, lanes));
        int maxLockedHeat = 0;
        for (Arrangement lock : locks) {
            maxLockedHeat = Math.max(maxLockedHeat, lock.getHeat() == null ? 0 : lock.getHeat());
        }
        int heats = Math.max(Math.max(minHeats, 1), maxLockedHeat);

        int[] occupancy = new int[heats];
        boolean[][] laneTaken = new boolean[heats][lanes];
        @SuppressWarnings("unchecked")
        List<Arrangement>[] matrix = new List[heats];
        for (int h = 0; h < heats; h++) matrix[h] = new ArrayList<>();

        // 预置人工锁定项（占住其 组/道）
        for (Arrangement lock : locks) {
            int hIdx = lock.getHeat() != null ? lock.getHeat() - 1 : 0;
            if (hIdx < 0 || hIdx >= heats) {
                warnings.add("人工锁定项组号越界（第" + lock.getHeat() + "组），已跳过占位");
                continue;
            }
            int lane = lock.getLane() != null ? lock.getLane() : 0;
            if (lane >= 1 && lane <= lanes && laneTaken[hIdx][lane - 1]) {
                warnings.add(String.format("人工锁定项道次冲突：第%d组第%d道被两条锁定项同时占用", hIdx + 1, lane));
            }
            matrix[hIdx].add(lock);
            occupancy[hIdx]++;
            if (lane >= 1 && lane <= lanes) laneTaken[hIdx][lane - 1] = true;
        }

        // 确定性排序（蛇形前先排好序）
        List<Athlete> ordered = new ArrayList<>(athletes);
        if (ArrangeStyle.SNAKE_SEEDED.id.equals(styleRule)) {
            // 种子蛇形：按种子名次升序（1=最快）；无成绩者名次为 MAX → 排最后，再按 id 稳定
            ordered.sort(Comparator
                    .comparingInt((Athlete a) -> AthleteKeys.seedRankOf(a, seedRank))
                    .thenComparing(a -> a.getId() == null ? Long.MAX_VALUE : a.getId()));
        } else {
            // 蛇形排布：年级 → 班级 → id
            ordered.sort(Comparator.comparing((Athlete a) -> AthleteKeys.nullSafeStr(a.getGrade()))
                    .thenComparing(AthleteKeys::classKeyOf)
                    .thenComparing(a -> a.getId() == null ? Long.MAX_VALUE : a.getId()));
        }

        // 蛇形目标组 → 入组（目标组满员则环形顺延；规则注入可改写选择：见 pickHeatByInjection）
        int[] heatOfPos = SnakeGrouping.heatOf(ordered.size(), heats);
        for (int pos = 0; pos < ordered.size(); pos++) {
            Athlete athlete = ordered.get(pos);
            int target = heatOfPos[pos];
            int chosen = ruleScorer.pickHeatByInjection(event, athlete, target, heats, lanes, occupancy);
            if (chosen < 0) {
                warnings.add("无法为运动员 " + athlete.getName() + " 分配合适的组");
                continue;
            }
            Arrangement arr = new Arrangement();
            arr.setAthlete(athlete);
            matrix[chosen].add(arr);
            occupancy[chosen]++;
        }

        // 组内道次：按蛇形偏好落位，跳过被锁定占用的道次
        for (int h = 0; h < heats; h++) {
            List<Arrangement> pending = new ArrayList<>();
            for (Arrangement a : matrix[h]) if (a.getLane() == null) pending.add(a);
            if (pending.isEmpty()) continue;
            int[] pref = SnakeGrouping.assignLanes(pending.size(), lanes);
            for (int i = 0; i < pending.size(); i++) {
                int lane = pref[i];
                if (lane >= 1 && lane <= lanes && laneTaken[h][lane - 1]) {
                    lane = -1;
                    for (int l = 1; l <= lanes; l++) if (!laneTaken[h][l - 1]) { lane = l; break; }
                }
                if (lane < 1) continue;
                laneTaken[h][lane - 1] = true;
                pending.get(i).setLane(lane);
            }
        }

        return result(heats, matrix, warnings);
    }

    // 「自定义规则」款型目录（CLASS 班级均衡 / SNAKE 蛇形排布 / SNAKE_SEEDED 种子蛇形）
    // 已拆出为独立文件：com.sports.schedule.rule.style.ArrangeStyle（后续 DSL/伪代码款型也归该层，多文件演进）。


    /**
     * 解析本次编排选用的 分组款型 id：优先 {@code ruleConfig.styleRule}（款型 id 字符串），
     * 兼容旧布尔 {@code ruleConfig.snakeGrouping=true} → snake；缺省 → class。
     */
    public static String resolveArrangeStyle(Map<String, Object> ruleConfig) {
        if (ruleConfig == null) return ArrangeStyle.CLASS.id;
        // 新键 styleRule / style_rule 优先；兼容升级前持久化的旧键 l1Rule / l1_rule（老数据无缝过渡）
        Object v = ruleConfig.get("styleRule");
        if (v == null) v = ruleConfig.get("style_rule");
        if (v == null) v = ruleConfig.get("l1Rule");
        if (v == null) v = ruleConfig.get("l1_rule");
        if (v != null && !String.valueOf(v).isBlank()) return ArrangeStyle.of(String.valueOf(v)).id;
        if (Boolean.TRUE.equals(ruleConfig.get("snakeGrouping"))) return ArrangeStyle.SNAKE.id;
        return ArrangeStyle.CLASS.id;
    }


    /** 是否属于「蛇形」款型（蛇形排布 / 种子蛇形）——二者共用同一套入组/分道实现，仅排序口径不同。 */
    public static boolean isSnakeRule(String styleRule) {
        return ArrangeStyle.of(styleRule).isSnake();
    }


    /**
     * 对抗式自检（内存态）：校验分配结果的硬约束，返回违反清单（空 = 全部满足）。
     * <ul>
     *   <li>同一组不能同班（人工锁定项豁免同班校验，但道次唯一仍校验）；蛇形模式下此项有意放开；</li>
     *   <li>道次在 [1, lanes] 且不重复占用。</li>
     * </ul>
     * 软约束（如「同班道次错开」）不在此列，仅作为 warnings 由 allocate 产出。
     */
    public List<String> validatePlacement(AllocationResult placement, int lanes, boolean snakeMode) {
        List<String> violations = new ArrayList<>();
        for (int h = 0; h < placement.heats(); h++) {
            List<Arrangement> in = placement.heatsMatrix().get(h);
            Set<Long> classes = new HashSet<>();
            Set<Integer> usedLanes = new HashSet<>();
            for (Arrangement a : in) {
                Integer lane = a.getLane();
                if (lane != null && (lane < 1 || lane > lanes)) {
                    violations.add(String.format("第%d组道次越界（lane=%d，合法区间 1~%d）", h + 1, lane, lanes));
                } else if (lane != null && !usedLanes.add(lane)) {
                    violations.add(String.format("第%d组道次%d被重复占用", h + 1, lane));
                }
                // 蛇形模式不强制「同组不同班」，跳过同班校验
                if (snakeMode) continue;
                // 人工锁定项：仅校验道次唯一（同班同组是人工选择，不视为编排错误）
                if (a.getId() != null && Boolean.TRUE.equals(a.getIsManual())) continue;
                Long cid = AthleteKeys.classIdOf(a.getAthlete());
                if (!classes.add(cid)) {
                    violations.add(String.format("第%d组出现同班重复（班级ID=%d），违反「同一组不能同班」", h + 1, cid));
                }
            }
        }
        return violations;
    }

    // ==================== 辅助方法 ====================


    public static int resolveLanes(Event e) {
        // 项目内并发人数优先：田赛 = 同时进行的工位数（X 人一批）；径赛 = 每组道次数
        Integer c = e.getConcurrency();
        if (c != null && c > 0) return c;
        if (Boolean.FALSE.equals(e.getTrack())) {
            // 田赛回退链：groupSize（每组工位数）> 默认工位数。
            // 注意：导入时「道次列填 0」令 defaultLanes=0，绝不能返回 0/1，否则批量编排落入
            // 「每人独占一组」（形同未编排）或除零；故缺失时给合理默认工位数 8。
            Integer gs = e.getGroupSize();
            if (gs != null && gs > 0) return gs;
            return DEFAULT_FIELD_GROUP;
        }
        Integer lc = e.getLaneCount();
        if (lc != null && lc > 0) return lc;
        Integer dl = e.getDefaultLanes();
        if (dl != null && dl > 0) return dl;
        return 8;
    }

}
