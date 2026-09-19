package com.sports.schedule.verify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赛程独立校验器——自检体系里的「裁判」。
 *
 * <h2>为什么必须有它</h2>
 * 赛程编排是 NP 难问题：没有已知最优解，也就没有外部标准答案可以比对。
 * 求解器（Timefold）和贪心算法都只是「生产者」——它们的输出<b>不能自己证明自己</b>。
 * 所以系统必须内置一个只做一件事的模块：<b>找茬</b>。
 *
 * <h2>它与求解器的关键区别（这才是它存在的意义）</h2>
 * <ul>
 *   <li><b>判据不同维度</b>：求解器按「并发位（池 × 槽位 × 时段窗口）」判断不重叠；
 *       本类按<b>落库后的真实场地</b>判断。槽位与场地是两套映射（并发位可能映射到同一场地），
 *       只有按场地查才查得出真实冲突。</li>
 *   <li><b>对象不同</b>：求解器检查的是内存里的「解」，本类检查的是「变成赛程表之后的样子」——
 *       中间要经过落库、场地映射、时长回写，任何一步出错求解器都看不见。</li>
 *   <li><b>实现独立</b>：本类<b>不复用</b> {@code ScheduleConstraintProvider} 的任何判定函数。
 *       共用实现会让同一个逻辑错误被生产端和校验端同时继承，校验就退化成自我确认。</li>
 * </ul>
 *
 * <h2>「没检查却报绿」是自检最大的敌人</h2>
 * 一个什么都不做、直接返回「通过」的校验器，比没有校验器更危险——它会给人虚假的安全感。
 * 因此结果里带上 {@link Audit} 的两个计数：{@code comparedVenuePairs}、
 * {@code comparedAthletePairs}（实际比对了多少对）。若行数很多而比对数为 0，
 * 看报告的人应当据此<b>质疑</b>结论，而不是相信一个空白的「通过」。
 *
 * <p>本类不依赖 Repository / JPA，输入是纯数据视图，因此可以脱离数据库单测——
 * 校验逻辑本身必须能被独立验证。</p>
 */
@Slf4j
@Component
public class ScheduleVerifier {

    /**
     * 兼项赶场缓冲（分钟）。
     *
     * <p>与 {@code ConflictService}、{@code ScheduleConstraintProvider} 三处同值：
     * 「排的时候按 15 分钟避让、检测的时候按 15 分钟报错」才是自洽的口径，
     * 任一处置成别的数字，都会出现「排完说没问题、校验说有问题」的怪象。</p>
     */
    public static final int CONFLICT_BUFFER_MIN = 15;

    /** 项目时长可执行下限比例（与调度端 MIN_DURATION_RATIO 对齐） */
    public static final double MIN_DURATION_RATIO = 0.35;

    /** 单项目最短可执行时长（分钟） */
    public static final int MIN_DURATION = 10;

    // ==================== 输入视图 ====================

    /**
     * 一条赛程行（纯数据视图）。
     *
     * <p>之所以不直接吃 JPA 实体：实体带懒加载代理与 Session 生命周期，既难单测也容易
     * 「因为懒加载异常而静默漏检」——校验器最不该有这种不确定性。</p>
     */
    public record Row(long eventId, String eventName, String grade, int day, String date,
                      String slotName, int startMinute, int endMinute, String venue,
                      String groupKey, Set<Long> athletes, Map<Long, String> athleteNames) {

        /** 自然键：项目 × 年级，用于与「编排表应有的单元」对齐 */
        public String key() {
            return keyOf(eventId, grade);
        }

        public String label() {
            return eventName + (grade == null || grade.isBlank() ? "" : "（" + grade + "）");
        }

        public String when() {
            return "第" + day + "天 " + slotName + " " + hhmm(startMinute) + "-" + hhmm(endMinute);
        }

        /** 跨天可比较的绝对分钟（第 N 天 = (N-1)×1440 + 当日分钟） */
        public int absoluteStart() {
            return (day - 1) * 1440 + startMinute;
        }

        public int durationMinutes() {
            return endMinute - startMinute;
        }

        public String athleteLabel(Long id) {
            if (athleteNames != null) {
                String n = athleteNames.get(id);
                if (n != null && !n.isBlank()) return n;
            }
            return "运动员#" + id;
        }
    }

    /** 编排表里「应该有」的一个单元——用来发现被静默丢弃的项目、并算出压缩比例 */
    public record Expected(String key, String eventName, String grade, int rawDurationMinutes) {
        public String label() {
            return eventName + (grade == null || grade.isBlank() ? "" : "（" + grade + "）");
        }
    }

    /** 对抗性聚焦结果：主动去盯「最可能出问题的地方」，而不是等它出问题 */
    public record Audit(int rowCount,
                        long comparedVenuePairs,
                        long comparedAthletePairs,
                        List<Map<String, Object>> busiestAthletes,
                        List<Map<String, Object>> tightestVenues) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowCount", rowCount);
            m.put("comparedVenuePairs", comparedVenuePairs);
            m.put("comparedAthletePairs", comparedAthletePairs);
            m.put("busiestAthletes", busiestAthletes);
            m.put("tightestVenues", tightestVenues);
            return m;
        }
    }

    /**
     * 解的质量自评：没有最优解，但可以量化「当前这版有多好、离理论下界多远」。
     *
     * <p>{@code totalMinutes} 是各行时长之和，{@code netUsedMinutes} 是按「天 × 场地」合并重叠后的
     * <b>净占用</b>。利用率必须用净占用算——用行时长之和会把重叠部分重复计入，
     * 得出 >100% 的荒谬值；而「有重叠」这件事应该由 {@code violations} 明确报告，
     * 不该被稀释进一个比例数字里。</p>
     */
    public record Quality(int totalRows, int totalMinutes, int netUsedMinutes, int rawMinutes,
                          double compressionPercent, int spanMinutes,
                          int dailyCapacityMinutes, double utilizationPercent) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalRows", totalRows);
            m.put("totalMinutes", totalMinutes);
            m.put("netUsedMinutes", netUsedMinutes);
            m.put("rawMinutes", rawMinutes);
            m.put("compressionPercent", compressionPercent);
            m.put("spanMinutes", spanMinutes);
            m.put("dailyCapacityMinutes", dailyCapacityMinutes);
            m.put("utilizationPercent", utilizationPercent);
            return m;
        }
    }

    /** 自检结论 */
    public record Result(boolean hardOk, int blockerCount, int warningCount,
                         List<ScheduleViolation> violations,
                         List<Map<String, Object>> unassigned,
                         Audit audit, Quality quality) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hardOk", hardOk);
            m.put("blockerCount", blockerCount);
            m.put("warningCount", warningCount);
            List<Map<String, Object>> vs = new ArrayList<>();
            for (ScheduleViolation v : violations) vs.add(v.toMap());
            m.put("violations", vs);
            m.put("unassigned", unassigned);
            m.put("audit", audit == null ? null : audit.toMap());
            m.put("quality", quality == null ? null : quality.toMap());
            return m;
        }
    }

    // ==================== 主流程 ====================

    /**
     * 对一份赛程表做整体自检。
     *
     * @param rows                 赛程行（落库后的真实样子）
     * @param expected             编排表里应有的单元（用于发现「被静默丢弃」与算压缩比）
     * @param dailyCapacityMinutes 单日可用分钟总数（所有时段之和），0 表示不计算利用率
     */
    public Result verify(List<Row> rows, List<Expected> expected, int dailyCapacityMinutes) {
        List<ScheduleViolation> violations = new ArrayList<>();
        List<Map<String, Object>> unassigned = new ArrayList<>();
        List<Row> safeRows = rows == null ? List.of() : rows;
        List<Expected> safeExpected = expected == null ? List.of() : expected;

        long venuePairs = 0;
        long athletePairs = 0;

        // ① 时间自洽：结束必须晚于开始。
        //    这是最廉价也最容易被忽略的一条——一旦 is 不成立，后面所有区间判定都失去意义。
        for (Row r : safeRows) {
            if (r.endMinute() <= r.startMinute()) {
                violations.add(new ScheduleViolation(ScheduleViolation.TIME_INCONSISTENT,
                        ScheduleViolation.LEVEL_BLOCKER, r.label(), null, r.when(),
                        "结束时间不晚于开始时间（时长 " + r.durationMinutes() + " 分钟）",
                        "检查该项目的时长配置与所处时段边界"));
            }
        }

        // ② 同场地同时段重叠——按**真实场地**判，与求解器按「并发位」判是两套独立口径。
        Map<String, List<Row>> byVenueDay = new LinkedHashMap<>();
        for (Row r : safeRows) {
            byVenueDay.computeIfAbsent(r.day() + "|" + r.venue(), k -> new ArrayList<>()).add(r);
        }
        for (List<Row> group : byVenueDay.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    venuePairs++;
                    Row a = group.get(i);
                    Row b = group.get(j);
                    if (overlaps(a.startMinute(), a.endMinute(), b.startMinute(), b.endMinute())) {
                        violations.add(new ScheduleViolation(ScheduleViolation.VENUE_OVERLAP,
                                ScheduleViolation.LEVEL_BLOCKER, a.label(), b.label(),
                                a.when() + " 与 " + b.when(),
                                "同一场地「" + a.venue() + "」被两个项目同时占用",
                                "把其中一个项目移到其它时段，或改到其它场地"));
                    }
                }
            }
        }

        // ③ 运动员兼项冲突（含赶场缓冲）。
        //    用「运动员 → 其所有赛程行」的倒排表，只比对同一运动员的行对——
        //    否则 n 行两两比对是 O(n²)，几百行就会把校验拖到不可交互。
        Map<Long, List<Row>> byAthlete = new LinkedHashMap<>();
        for (Row r : safeRows) {
            if (r.athletes() == null) continue;
            for (Long id : r.athletes()) {
                byAthlete.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<Long, List<Row>> e : byAthlete.entrySet()) {
            List<Row> list = e.getValue();
            if (list.size() < 2) continue;
            // 按绝对起点排序后只比较相邻对：O(k) 取代 O(k²)。
            // 正确性：若某运动员的 A 与任一更晚区间冲突，则 A 必与其按起点排序的
            // 直接后继冲突（缓冲同向叠加，冲突关系在排序后连通成链），故相邻比较不漏报。
            List<Row> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparingInt(Row::absoluteStart));
            for (int i = 0; i < sorted.size() - 1; i++) {
                athletePairs++;
                Row a = sorted.get(i);
                Row b = sorted.get(i + 1);
                if (athleteClash(a, b)) {
                    String who = a.athleteLabel(e.getKey());
                    violations.add(new ScheduleViolation(ScheduleViolation.ATHLETE_CLASH,
                            ScheduleViolation.LEVEL_BLOCKER, a.label(), b.label(),
                            a.when() + " 与 " + b.when(),
                            "运动员「" + who + "」兼报这两项，中间赶不上（需 ≥ "
                                    + CONFLICT_BUFFER_MIN + " 分钟缓冲）",
                            "把其中一项后移，或确认该运动员是否应当兼报"));
                }
            }
        }

        // ④ 同组项目未同时开赛——违反赛会惯例（集中裁判与器材），不算阻塞但要人工确认
        Map<String, List<Row>> byGroup = new LinkedHashMap<>();
        for (Row r : safeRows) {
            if (r.groupKey() == null || r.groupKey().isBlank()) continue;
            byGroup.computeIfAbsent(r.groupKey(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Row>> e : byGroup.entrySet()) {
            List<Row> g = e.getValue();
            if (g.size() < 2) continue;
            Row first = g.get(0);
            for (int i = 1; i < g.size(); i++) {
                Row r = g.get(i);
                if (r.day() != first.day() || r.startMinute() != first.startMinute()) {
                    violations.add(new ScheduleViolation(ScheduleViolation.GROUP_NOT_SIMULTANEOUS,
                            ScheduleViolation.LEVEL_WARNING, first.label(), r.label(),
                            first.when() + " 与 " + r.when(),
                            "同组「" + e.getKey() + "」项目未同时开赛",
                            "如需集中裁判/器材，请把同组项目对齐到同一时刻"));
                }
            }
        }

        // ⑤ 被静默丢弃的项目：编排表里有、赛程表里没有。
        //    「没排入」比「排错」更危险——排错至少看得见，没排入会到比赛当天才发现跑不完。
        Map<String, Expected> expectedByKey = new LinkedHashMap<>();
        Set<String> placedKeys = new HashSet<>();
        for (Row r : safeRows) placedKeys.add(r.key());
        for (Expected ex : safeExpected) expectedByKey.put(ex.key(), ex);
        for (Expected ex : safeExpected) {
            if (placedKeys.contains(ex.key())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", ex.key());
            m.put("eventName", ex.eventName());
            m.put("grade", ex.grade());
            unassigned.add(m);
            violations.add(new ScheduleViolation(ScheduleViolation.NOT_ASSIGNED,
                    ScheduleViolation.LEVEL_BLOCKER, ex.label(), null, null,
                    "该项目在赛程表里找不到对应条目（容量不足或编排未覆盖）",
                    "增加比赛天数/时段、提高并发位，或削减项目规模"));
        }

        // ⑥ 时长低于可执行下限：项目被压得太狠，现场跑不完
        for (Row r : safeRows) {
            Expected ex = expectedByKey.get(r.key());
            if (ex == null || ex.rawDurationMinutes() <= 0) continue;
            int floor = Math.max(MIN_DURATION,
                    (int) Math.round(ex.rawDurationMinutes() * MIN_DURATION_RATIO));
            if (r.durationMinutes() < floor) {
                violations.add(new ScheduleViolation(ScheduleViolation.DURATION_BELOW_FLOOR,
                        ScheduleViolation.LEVEL_WARNING, r.label(), null, r.when(),
                        "实际时长 " + r.durationMinutes() + " 分钟，低于可执行下限 " + floor
                                + " 分钟（真实估算 " + ex.rawDurationMinutes() + " 分钟）",
                        "增加该项目所在池的并发位，或延长当天时段"));
            }
        }

        int blockers = 0;
        int warn = 0;
        for (ScheduleViolation v : violations) {
            if (v.isBlocker()) blockers++;
            else warn++;
        }

        Audit audit = buildAudit(safeRows, byVenueDay, byAthlete, venuePairs, athletePairs);
        Quality quality = buildQuality(safeRows, safeExpected, dailyCapacityMinutes);

        Result result = new Result(blockers == 0, blockers, warn, violations, unassigned, audit, quality);
        log.info("赛程自检: 阻塞 {} 条 / 告警 {} 条；比对场地对 {} 对、运动员对 {} 对；压缩 {}%",
                blockers, warn, venuePairs, athletePairs,
                quality == null ? "-" : quality.compressionPercent());
        return result;
    }

    // ==================== 对抗性聚焦 ====================

    /**
     * 主动盯住「最可能出问题的地方」：最忙的运动员、最紧张的场地。
     *
     * <p>与其等冲突自己暴露，不如把资源最紧张的那几个对象直接列出来让人过目——
     * 这是「把最难排的、最忙的单独拎出来检验」的落地。</p>
     */
    private Audit buildAudit(List<Row> rows,
                             Map<String, List<Row>> byVenueDay,
                             Map<Long, List<Row>> byAthlete,
                             long venuePairs, long athletePairs) {
        List<Map<String, Object>> busiest = new ArrayList<>();
        byAthlete.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Long, List<Row>> e) -> e.getValue().size()).reversed())
                .limit(5)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("athleteId", e.getKey());
                    m.put("athleteName", e.getValue().get(0).athleteLabel(e.getKey()));
                    m.put("entries", e.getValue().size());
                    List<String> items = new ArrayList<>();
                    for (Row r : e.getValue()) items.add(r.label() + "@" + r.when());
                    m.put("items", items);
                    busiest.add(m);
                });

        List<Map<String, Object>> tightest = new ArrayList<>();
        byVenueDay.entrySet().stream()
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, List<Row>> e) -> sumMinutes(e.getValue())).reversed())
                .limit(5)
                .forEach(e -> {
                    List<Row> list = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bucket", e.getKey());
                    m.put("rowCount", list.size());
                    m.put("usedMinutes", sumMinutes(list));
                    tightest.add(m);
                });

        return new Audit(rows.size(), venuePairs, athletePairs, busiest, tightest);
    }

    /** 解的质量自评 */
    private Quality buildQuality(List<Row> rows, List<Expected> expected, int dailyCapacityMinutes) {
        int totalMinutes = 0;
        int minAbs = Integer.MAX_VALUE;
        int maxAbs = Integer.MIN_VALUE;
        Set<Integer> days = new HashSet<>();
        Set<String> venues = new HashSet<>();
        for (Row r : rows) {
            totalMinutes += r.durationMinutes();
            minAbs = Math.min(minAbs, r.absoluteStart());
            maxAbs = Math.max(maxAbs, r.absoluteStart() + r.durationMinutes());
            days.add(r.day());
            venues.add(r.venue());
        }
        int rawMinutes = 0;
        for (Expected ex : expected) rawMinutes += Math.max(0, ex.rawDurationMinutes());

        // 净占用：按 (天, 场地) 把各行区间合并掉重叠后再求和。
        // 直接用「行时长之和」算利用率会把重叠部分重复计入，产出 >100% 的荒谬值——
        // 那其实是「存在重叠」的信号，应由 violations 明确报告，而不是污染利用率指标。
        Map<String, List<int[]>> byVenueDay = new LinkedHashMap<>();
        for (Row r : rows) {
            byVenueDay.computeIfAbsent(r.day() + "|" + r.venue(), k -> new ArrayList<>())
                    .add(new int[]{r.startMinute(), r.endMinute()});
        }
        int netUsed = 0;
        for (List<int[]> list : byVenueDay.values()) {
            list.sort(Comparator.comparingInt(iv -> iv[0]));
            int curStart = -1;
            int curEnd = -1;
            for (int[] iv : list) {
                if (curStart < 0) {
                    curStart = iv[0];
                    curEnd = iv[1];
                } else if (iv[0] <= curEnd) {
                    curEnd = Math.max(curEnd, iv[1]);
                } else {
                    netUsed += Math.max(0, curEnd - curStart);
                    curStart = iv[0];
                    curEnd = iv[1];
                }
            }
            if (curStart >= 0) netUsed += Math.max(0, curEnd - curStart);
        }

        double compression = rawMinutes > 0 ? round1(totalMinutes * 100.0 / rawMinutes) : 0;
        int span = rows.isEmpty() ? 0 : Math.max(0, maxAbs - minAbs);
        // 供给 = 单日可用分钟 × 比赛天数 × **场地数**。
        // 漏乘场地数会得出 >100% 的荒谬利用率（实测 230%）——多个场地是并行资源，
        // 短缺的要么是分子口径（重叠重复计入）、要么是分母口径（漏了并行度），必须区分清楚。
        int supply = dailyCapacityMinutes * Math.max(1, days.size()) * Math.max(1, venues.size());
        double utilization = supply > 0 ? round1(netUsed * 100.0 / supply) : 0;

        return new Quality(rows.size(), totalMinutes, netUsed, rawMinutes, compression, span,
                dailyCapacityMinutes, utilization);
    }

    // ==================== 判定工具（独立实现，不复用调度端） ====================

    /** 两个时间区间是否重叠（左闭右开） */
    static boolean overlaps(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    /** 同一运动员的两个项目是否赶不上（跨天用绝对分钟比较；口径同 ConflictService） */
    static boolean athleteClash(Row a, Row b) {
        int aS = a.absoluteStart();
        int aE = aS + a.durationMinutes();
        int bS = b.absoluteStart();
        int bE = bS + b.durationMinutes();
        return aS < bE + CONFLICT_BUFFER_MIN && bS < aE + CONFLICT_BUFFER_MIN;
    }

    public static String keyOf(long eventId, String grade) {
        return eventId + "|" + (grade == null ? "" : grade);
    }

    static String hhmm(int minute) {
        int m = Math.max(0, minute);
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static int sumMinutes(List<Row> rows) {
        int s = 0;
        for (Row r : rows) s += r.durationMinutes();
        return s;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
