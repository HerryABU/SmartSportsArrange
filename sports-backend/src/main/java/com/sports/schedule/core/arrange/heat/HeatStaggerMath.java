package com.sports.schedule.core.arrange.heat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组次错开求解（纯函数核心：无 DB / 无 Spring / 无状态）。
 *
 * <h2>为什么需要它：项目级判定太粗，会「冤枉」也「够不着」</h2>
 * 编排落库后，冲突检测用的是<b>整条赛程行</b>的时间窗：
 * 项目 A 排 10:00–11:00（6 组，每组 10 分钟），项目 B 排 10:00–10:30（3 组）。
 * 两条赛程行重叠 → 报冲突。但运动员甲若在 A 的第 <b>5</b> 组（10:40–10:50）、
 * 在 B 的第 1 组（10:00–10:10），实际间隔 30 分钟，<b>根本不冲突</b>——
 * 项目级口径把「可能冲突」当成了「真冲突」，这类误报会逼编排者去调项目时间，
 * 而真正该调的只是<b>组次顺序</b>。
 *
 * <p>本类把判定下沉到<b>组次级</b>：先按「运动员 × 组次」算出他真实的出场时间窗，
 * 再对真正撞车的那些人，<b>只改换他在该项目内的组次</b>，让两场错开
 * ≥ 缓冲分钟——项目的时间窗一分不动。
 *
 * <h2>为什么不直接改项目时间</h2>
 * 改时间要重新走一遍占用/容量协商，且往往牵连别的项目；而改组次是
 * <b>组内重排</b>，不触碰任何时段容量，是代价最低的一招。
 * 现实中这也是体育老师的标准做法：「这两个项目同时段了，但把他从第 1 组调到第 4 组就行」。
 *
 * <h2>三道不可让步的红线</h2>
 * <ol>
 *   <li><b>同组不同班</b>（与 {@code ArrangementService.validatePlacement} 同一条硬约束）：
 *       目标组次里若已有同班同学，则该候选一律不采纳——错开冲突不能靠制造新违规换取。</li>
 *   <li><b>组容量</b>：目标组次人数不得超过并道数，否则道次排不下。</li>
 *   <li><b>只动组次、不动人</b>：人工锁定的编排项（{@code isManual}）不参与换组，
 *       它是编排者明确指定的位置。</li>
 * </ol>
 *
 * <p>纯函数：输入输出都是不可变视图，可脱离数据库单测。</p>
 */
public final class HeatStaggerMath {

    private HeatStaggerMath() {
    }

    /**
     * 一个组次槽位：某项目某赛次下的「第 h 组」及其真实时间窗。
     *
     * <p>时间窗由赛程行起点 + 组序推导：第 h 组 = {@code [start + (h-1)×perRound, +perRound)}。
     * 这与编排期「项目时长 = 组数 × 组次用时、组次依次开赛」的口径一致。</p>
     */
    public record HeatSlot(long eventId, String eventName, String grade, String gender, String round,
                           int heat, int heatCount, int day, int startMinute, int perRoundMinutes) {

        public int start() {
            return startMinute + (heat - 1) * perRoundMinutes;
        }

        public int end() {
            return start() + perRoundMinutes;
        }

        /** 展示用键：项目 × 年级 × 性别 × 赛次（同一赛次下的所有组次属于同一编排批次） */
        public String batchKey() {
            return eventId + "|" + nz(grade) + "|" + nz(gender) + "|" + nz(round);
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }

    /** 一名运动员在某个编排批次里的一个占位（= 一条编排记录） */
    public record SlotRef(long athleteId, String athleteName, String classId, HeatSlot slot,
                          boolean manual) {

        public String id() {
            return athleteId + "@" + slot.batchKey();
        }
    }

    /** 一次换组建议：把该占位从当前组次移到目标组次 */
    public record Move(long athleteId, String athleteName, String classId,
                       HeatSlot from, HeatSlot to, int gapBefore, int gapAfter,
                       boolean aiAdvised) {

        /** 兼容旧构造（规则择优） */
        public Move(long athleteId, String athleteName, String classId,
                    HeatSlot from, HeatSlot to, int gapBefore, int gapAfter) {
            this(athleteId, athleteName, classId, from, to, gapBefore, gapAfter, false);
        }

        public String describe() {
            return String.format("运动员「%s」在「%s」（%s %s）的第%d组 → 第%d组（间隔 %d → %d 分钟）",
                    athleteName, from.eventName(), nzLabel(from.grade()), nzLabel(from.gender()),
                    from.heat(), to.heat(), gapBefore, gapAfter);
        }

        private static String nzLabel(String s) {
            return s == null || s.isBlank() ? "不分年级" : s;
        }
    }

    /**
     * 求解：把「组次级真冲突」逐个消解掉。
     *
     * <p>算法（贪心 + 择优，刻意保持 O(运动员数 × 候选组次数) 的轻量级）：
     * <ol>
     *   <li>按运动员分组其全部占位；</li>
     *   <li>两两判组次级冲突（同一天、间隔 &lt; 缓冲）；</li>
     *   <li>对每个冲突运动员，枚举「可换的目标组次」（容量够 + 不违反同班不同班 + 非人工锁定），
     *       逐一试算换后与他其余项目能否全部错开，取<b>错开后最小间隔最大</b>者
     *       （间隔越大运动员休息越足；能全部消解冲突的候选自然排在前面）；</li>
     *   <li>采纳后<b>就地更新</b>该运动员的占位，使其后的冲突判定看到最新状态。</li>
     * </ol>
     *
     * <p>「就地更新」是本算法与「先收集再统一改」的本质区别：一次编排里同一人可能有
     * 3 处冲突，改完第一处若不更新状态，第二处会基于已过期的组次做决策，
     * 结果是改了又撞——这是本类必须逐个收敛、而不是批量出方案的原因。</p>
     *
     * @param slots   全量占位（编排表 + 赛程行推导出的组次时间窗）
     * @param lanesOf 各项目并道数（组容量上限）；缺配置视为不限
     * @param bufferMin 赶场缓冲（分钟）
     * @return 换组建议（按运动员顺序）；无可消解的冲突时返回空列表
     */
    public static List<Move> resolve(List<SlotRef> slots, Map<Long, Integer> lanesOf, int bufferMin) {
        return resolve(slots, lanesOf, bufferMin, null);
    }

    /**
     * 建议提供者（可选）：给定合法候选组次，返回「换到第几组」的建议。
     *
     * <p>刻意做成接口而不是直接依赖 Spring Bean：本类是<b>纯函数核心</b>
     * （不读库、不依赖容器），单测里用 lambda 就能喂建议。
     * 模型不可用时传 null 或返回 empty，规则择优（间隔最大）自动兜底。</p>
     */
    @FunctionalInterface
    public interface HeatSuggester {
        /**
         * @param heatCount 组次数
         * @param perRound  每组用时
         * @param legal     合法候选组次（1-based）
         * @param curHeat   当前组次（1-based）
         * @param gapOf     逐组间隔（分钟），长度 ≥ heatCount
         * @param fillOf    逐组填充率，长度 ≥ heatCount
         * @return 建议目标组次（1-based）；无建议返回 empty
         */
        java.util.Optional<Integer> suggest(int heatCount, int perRound, List<Integer> legal,
                                            int curHeat, int[] gapOf, double[] fillOf);
    }

    /**
     * 求解（可挂模型建议）。
     *
     * <p><b>模型只在「规则已判定合法」的候选里重排</b>，绝不参与合法性判断：
     * 三条红线（同班/容量/锁定）永远由本类裁定。理由见训练侧的消融记录 ——
     * 把判据喂进特征会让模型只是把判据读一遍（命中率 1.000 却什么都没学），
     * 所以「判合法」与「挑更好」必须分给两方。</p>
     */
    public static List<Move> resolve(List<SlotRef> slots, Map<Long, Integer> lanesOf, int bufferMin,
                                     HeatSuggester suggester) {
        // ① 运动员 → 其全部占位（保持录入顺序，便于结果可复现）
        Map<Long, List<SlotRef>> byAthlete = new LinkedHashMap<>();
        for (SlotRef s : slots) {
            byAthlete.computeIfAbsent(s.athleteId(), k -> new ArrayList<>()).add(s);
        }
        // ② 当前每组人数（换组后需重算，故每次采纳都更新）
        Map<String, Integer> heatSize = new HashMap<>();
        for (SlotRef s : slots) {
            heatSize.merge(heatSizeKey(s.slot()), 1, Integer::sum);
        }
        // ③ 每组内已有的班级（用于「同组不同班」判定）
        Map<String, Set<String>> heatClasses = new HashMap<>();
        for (SlotRef s : slots) {
            heatClasses.computeIfAbsent(heatSizeKey(s.slot()), k -> new LinkedHashSet<>())
                    .add(s.classId() == null ? "" : s.classId());
        }

        List<Move> moves = new ArrayList<>();
        for (Map.Entry<Long, List<SlotRef>> entry : byAthlete.entrySet()) {
            if (entry.getValue().size() < 2) continue;   // 只报一个项目的项目不可能有兼项冲突
            // 反复求解直到该运动员无冲突（一次可能只解开一处）
            for (int guard = 0; guard < entry.getValue().size(); guard++) {
                List<SlotRef> mine = entry.getValue();
                List<SlotRef> pair = firstClashingPair(mine, bufferMin);
                if (pair == null) break;
                SlotRef a = pair.get(0), b = pair.get(1);
                Move best = bestMove(a, b, mine, heatSize, heatClasses, lanesOf, bufferMin, suggester);
                if (best == null) break;   // 无合法换法 → 留给告警，不硬改
                applyMove(best, a, b, heatSize, heatClasses);
                moves.add(best);
                // 被移动的那一条要真正换掉（entry 里持的是旧对象）
                replaceRef(mine, best);
            }
        }
        return moves;
    }

    /** 同一天且间隔 < 缓冲即冲突（对称间隔口径，与 ConflictMath.gapOf 一致） */
    private static List<SlotRef> firstClashingPair(List<SlotRef> mine, int bufferMin) {
        for (int i = 0; i < mine.size(); i++) {
            for (int j = i + 1; j < mine.size(); j++) {
                SlotRef a = mine.get(i), b = mine.get(j);
                if (a.slot().eventId() == b.slot().eventId() && nz(a.slot().round()).equals(nz(b.slot().round()))) {
                    continue;   // 同一项目同一赛次的不同组次本身就在同一条赛程行内，不算兼项冲突
                }
                if (clash(a.slot(), b.slot(), bufferMin)) return List.of(a, b);
            }
        }
        return null;
    }

    /** 两个组次槽位是否赶不上（不同天永不冲突） */
    public static boolean clash(HeatSlot x, HeatSlot y, int bufferMin) {
        if (x.day() != y.day()) return false;
        int gap = Math.max(y.start() - x.end(), x.start() - y.end());
        return gap < bufferMin;
    }

    /**
     * 择优换组：在 a、b 两个冲突占位里，各挑一个能解开冲突的目标组次，取间隔最大者。
     *
     * <p>两侧都试是必要的：换 a 还是换 b，现场体感差别很大——
     * 把靠后的项目提前通常比把靠前的项目推后更容易执行，
     * 而「最小间隔最大」这个判据自然偏向让两场都离得更远。</p>
     */
    private static Move bestMove(SlotRef a, SlotRef b, List<SlotRef> mine,
                                 Map<String, Integer> heatSize, Map<String, Set<String>> heatClasses,
                                 Map<Long, Integer> lanesOf, int bufferMin) {
        return bestMove(a, b, mine, heatSize, heatClasses, lanesOf, bufferMin, null);
    }

    private static Move bestMove(SlotRef a, SlotRef b, List<SlotRef> mine,
                                 Map<String, Integer> heatSize, Map<String, Set<String>> heatClasses,
                                 Map<Long, Integer> lanesOf, int bufferMin,
                                 HeatSuggester suggester) {
        Move best = null;
        Move viaA = bestFor(a, b, mine, heatSize, heatClasses, lanesOf, bufferMin, suggester);
        if (viaA != null) best = viaA;
        Move viaB = bestFor(b, a, mine, heatSize, heatClasses, lanesOf, bufferMin, suggester);
        if (viaB != null && (best == null || betterMove(viaB, best))) best = viaB;
        return best;
    }

    /**
     * 比较两个都合法的换法：模型建议优先，其次间隔最大。
     *
     * <p>「模型优先」是刻意的：它编码了规则看不到的全局权衡
     * （给后续留余量、班级分散的长期代价）。但只有当模型建议也<b>确实解开了冲突</b>时才采纳 ——
     * 见 {@code bestFor} 里的 {@code gapAfter >= bufferMin} 闸门。</p>
     */
    private static boolean betterMove(Move candidate, Move current) {
        if (candidate.aiAdvised() != current.aiAdvised()) {
            return candidate.aiAdvised();
        }
        return candidate.gapAfter() > current.gapAfter();
    }

    private static Move bestFor(SlotRef moving, SlotRef fixed, List<SlotRef> mine,
                                Map<String, Integer> heatSize, Map<String, Set<String>> heatClasses,
                                Map<Long, Integer> lanesOf, int bufferMin) {
        return bestFor(moving, fixed, mine, heatSize, heatClasses, lanesOf, bufferMin, null);
    }

    private static Move bestFor(SlotRef moving, SlotRef fixed, List<SlotRef> mine,
                                Map<String, Integer> heatSize, Map<String, Set<String>> heatClasses,
                                Map<Long, Integer> lanesOf, int bufferMin,
                                HeatSuggester suggester) {
        // 红线③：人工锁定的项不动
        if (moving.manual()) return null;
        HeatSlot from = moving.slot();
        Integer lanes = lanesOf.get(from.eventId());
        String cid = moving.classId() == null ? "" : moving.classId();
        int gapBefore = Math.max(fixed.slot().start() - from.end(), from.start() - fixed.slot().end());

        int heatCount = from.heatCount();
        int[] gapOf = new int[heatCount];
        double[] fillOf = new double[heatCount];
        List<Integer> legal = new ArrayList<>();
        java.util.Map<Integer, Integer> legalGap = new LinkedHashMap<>();

        for (int h = 1; h <= heatCount; h++) {
            HeatSlot to = new HeatSlot(from.eventId(), from.eventName(), from.grade(), from.gender(),
                    from.round(), h, heatCount, from.day(), from.startMinute(), from.perRoundMinutes());
            String key = heatSizeKey(to);
            Integer lanesCap = lanesOf.get(from.eventId());
            Integer size = heatSize.get(key);
            int fill = size == null ? 0 : size;
            fillOf[h - 1] = (lanesCap == null || lanesCap <= 0) ? 0d : (double) fill / lanesCap;
            int g = gapToFixed(to, fixed.slot());
            gapOf[h - 1] = g;
            if (h == from.heat()) continue;
            // 红线②：组容量（并道数上限；缺配置视为不限）
            if (lanes != null && fill >= lanes) continue;
            // 红线①：同组不同班 —— 必须查<b>目标组次</b>的班级构成。
            // ⚠️ 早前误取「来源组」的班级集合，等于这条红线从未真正生效：
            //    换组恰恰是为了离开来源组，查它的同班构成毫无意义。
            if (heatClasses.getOrDefault(key, Set.of()).contains(cid)) continue;
            // 换后必须与「该运动员其余全部项目」都错开（不只是刚冲突的那一个）
            if (stillClashesWithOthers(to, moving, fixed, mine, bufferMin)) continue;
            // ⚠️ 必须<b>真的消解</b>冲突：只检查「与 others 不撞」是不够的——
            // 若目标组次与 fixed 仍差着不到 buffer，本质上冲突还在，
            // 而这次换组已经改动了组次顺序，等于白白消耗一次机会、
            // 还可能把下游依赖「组次已定」的环节搅乱。
            // （实测：错开一格 10 分钟 < 缓冲 15 分钟时，不做这道闸门就会采纳无效换组。）
            if (g < bufferMin) continue;
            legal.add(h);
            legalGap.put(h, g);
        }
        if (legal.isEmpty()) return null;

        // ① 模型建议优先（只在已判定合法的候选里挑 —— 判合法永远是规则的事）
        if (suggester != null && legal.size() > 1) {
            try {
                java.util.Optional<Integer> hinted = suggester.suggest(
                        heatCount, from.perRoundMinutes(), legal, from.heat(), gapOf, fillOf);
                if (hinted.isPresent()) {
                    int h = hinted.get();
                    Integer g = legalGap.get(h);
                    // 二次校验：模型给的组次必须仍在合法集合内（模型不可信，红线不可越）
                    if (g != null && g >= bufferMin) {
                        HeatSlot to = new HeatSlot(from.eventId(), from.eventName(), from.grade(),
                                from.gender(), from.round(), h, heatCount, from.day(),
                                from.startMinute(), from.perRoundMinutes());
                        return new Move(moving.athleteId(), moving.athleteName(), moving.classId(),
                                from, to, gapBefore, g, true);
                    }
                }
            } catch (Exception ignored) {
                // 模型异常一律回退规则，不影响主流程
            }
        }

        // ② 规则兜底：间隔最大者
        Move best = null;
        for (int h : legal) {
            int g = legalGap.get(h);
            if (best == null || g > best.gapAfter()) {
                HeatSlot to = new HeatSlot(from.eventId(), from.eventName(), from.grade(), from.gender(),
                        from.round(), h, heatCount, from.day(), from.startMinute(), from.perRoundMinutes());
                best = new Move(moving.athleteId(), moving.athleteName(), moving.classId(),
                        from, to, gapBefore, g, false);
            }
        }
        return best;
    }

    /** 目标组次与固定占位之间的对称间隔（分钟） */
    private static int gapToFixed(HeatSlot to, HeatSlot fixed) {
        return Math.max(fixed.start() - to.end(), to.start() - fixed.end());
    }

    /** 换到目标组次后，是否仍与其余项目（除 fixed 外）撞车 */
    private static boolean stillClashesWithOthers(HeatSlot to, SlotRef moving, SlotRef fixed,
                                                  List<SlotRef> mine, int bufferMin) {
        for (SlotRef other : mine) {
            if (other == moving || other == fixed) continue;
            if (other.slot().eventId() == to.eventId()
                    && nz(other.slot().round()).equals(nz(to.round()))) {
                continue;   // 同项目同赛次：本就是同一批编排，不互撞
            }
            if (clash(to, other.slot(), bufferMin)) return true;
        }
        return false;
    }

    /** 采纳换组：更新两组的规模与班级构成 */
    private static void applyMove(Move move, SlotRef moving, SlotRef fixed,
                                  Map<String, Integer> heatSize, Map<String, Set<String>> heatClasses) {
        String fromKey = heatSizeKey(moving.slot());
        String toKey = heatSizeKey(move.to());
        heatSize.merge(fromKey, -1, Integer::sum);
        heatSize.merge(toKey, 1, Integer::sum);
        Set<String> toClasses = heatClasses.computeIfAbsent(toKey, k -> new LinkedHashSet<>());
        if (move.classId() != null) toClasses.add(move.classId());
    }

    /** 把列表里那条旧占位替换成换组后的新占位（保持后续判定的状态是最新的） */
    private static void replaceRef(List<SlotRef> mine, Move move) {
        for (int i = 0; i < mine.size(); i++) {
            SlotRef s = mine.get(i);
            if (s.athleteId() == move.athleteId() && s.slot().eventId() == move.from().eventId()
                    && nz(s.slot().round()).equals(nz(move.from().round()))) {
                mine.set(i, new SlotRef(s.athleteId(), s.athleteName(), s.classId(),
                        move.to(), s.manual()));
                return;
            }
        }
    }

    private static String heatSizeKey(HeatSlot s) {
        return s.batchKey() + "#" + s.heat();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
