package com.sports.schedule.verify;

import com.sports.schedule.verify.checks.AssignmentCoverageCheck;
import com.sports.schedule.verify.checks.AthleteClashCheck;
import com.sports.schedule.verify.checks.DurationFloorCheck;
import com.sports.schedule.verify.checks.GroupSimultaneousCheck;
import com.sports.schedule.verify.checks.TimeConsistencyCheck;
import com.sports.schedule.verify.checks.VenueOverlapCheck;
import com.sports.schedule.verify.checks.VerifyCounters;
import com.sports.schedule.verify.focus.FocusScanner;
import com.sports.schedule.verify.quality.QualityEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.sports.entity.result.Result;
import com.sports.schedule.opt.solver.ScheduleConstraintProvider;
import com.sports.service.arrange.ConflictService;

/**
 * 赛程独立校验器——自检体系里的「裁判」与<b>编排入口</b>。
 *
 * <h2>为什么必须有它</h2>
 * 赛程编排是 NP 难问题：没有已知最优解，也就没有外部标准答案可以比对。
 * 求解器（Timefold）和贪心算法都只是「生产者」——它们的输出<b>不能自己证明自己</b>。
 * 所以系统必须内置一个只做一件事的模块：<b>找茬</b>。
 *
 * <h2>它与求解器的关键区别（这才是它存在的意义）</h2>
 * <ul>
 *   <li><b>判据不同维度</b>：求解器按「并发位（池 × 槽位 × 时段窗口）」判断不重叠；
 *       本类按<b>落库后的真实场地</b>判断。只有按场地查才查得出真实冲突。</li>
 *   <li><b>对象不同</b>：求解器检查的是内存里的「解」，本类检查的是「变成赛程表之后的样子」。</li>
 *   <li><b>实现独立</b>：本类<b>不复用</b> {@code ScheduleConstraintProvider} 的任何判定函数。</li>
 * </ul>
 *
 * <h2>职责拆分（二级目录，按算法系归类）</h2>
 * 本类只做<b>编排与日志</b>，6 项检查与对抗性聚焦/质量自评分别下沉到：
 * <ul>
 *   <li>{@code verify.checks.*} —— 检查①②③④⑤⑥（时间自洽 / 场地重叠 / 运动员兼项 / 同组同时 / 覆盖度 / 时长下限）</li>
 *   <li>{@code verify.focus.FocusScanner} —— 最忙运动员、最紧张场地的对抗性聚焦</li>
 *   <li>{@code verify.quality.QualityEstimator} —— 解的质量自评（净占用 / 利用率 / 压缩比）</li>
 * </ul>
 *
 * <p>本类不依赖 Repository / JPA，输入是纯数据视图，因此可以脱离数据库单测。</p>
 */
@Slf4j
@Component
public class ScheduleVerifier {

    /**
     * 兼项赶场缓冲（分钟）。与 {@code ConflictService}、{@code ScheduleConstraintProvider} 三处同值。
     */
    public static final int CONFLICT_BUFFER_MIN = 15;

    /** 项目时长可执行下限比例（与调度端 MIN_DURATION_RATIO 对齐） */
    public static final double MIN_DURATION_RATIO = 0.35;

    /** 单项目最短可执行时长（分钟） */
    public static final int MIN_DURATION = 10;

    // ==================== 输入视图 ====================

    /**
     * 一条赛程行（纯数据视图）。之所以不直接吃 JPA 实体：避免懒加载异常导致静默漏检。
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

    /** 对抗性聚焦结果 */
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

    /** 解的质量自评 */
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

    // ==================== 主流程（编排入口：仅调度各检查，逻辑下沉） ====================

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

        // ① 时间自洽（最廉价也最易被忽略）
        TimeConsistencyCheck.check(safeRows, violations);

        // ② 同场地同时段重叠 / ③ 运动员兼项冲突（携带审计计数）
        VerifyCounters counters = new VerifyCounters();
        VenueOverlapCheck.check(safeRows, violations, counters);
        AthleteClashCheck.check(safeRows, violations, counters);

        // ④ 同组项目未同时开赛
        GroupSimultaneousCheck.check(safeRows, violations);

        // ⑤ 覆盖度 / ⑥ 时长下限（依赖 placedKeys + expectedByKey）
        Set<String> placedKeys = new HashSet<>();
        for (Row r : safeRows) placedKeys.add(r.key());
        Map<String, Expected> expectedByKey = new LinkedHashMap<>();
        for (Expected ex : safeExpected) expectedByKey.put(ex.key(), ex);
        AssignmentCoverageCheck.check(placedKeys, expectedByKey, violations, unassigned);
        DurationFloorCheck.check(safeRows, expectedByKey, violations);

        int blockers = 0;
        int warn = 0;
        for (ScheduleViolation v : violations) {
            if (v.isBlocker()) blockers++;
            else warn++;
        }

        Audit audit = FocusScanner.scan(safeRows, counters.venuePairs, counters.athletePairs);
        Quality quality = QualityEstimator.estimate(safeRows, safeExpected, dailyCapacityMinutes);

        Result result = new Result(blockers == 0, blockers, warn, violations, unassigned, audit, quality);
        log.info("赛程自检: 阻塞 {} 条 / 告警 {} 条；比对场地对 {} 对、运动员对 {} 对；压缩 {}%",
                blockers, warn, counters.venuePairs, counters.athletePairs,
                quality == null ? "-" : quality.compressionPercent());
        return result;
    }

    public static String keyOf(long eventId, String grade) {
        return eventId + "|" + (grade == null ? "" : grade);
    }

    static String hhmm(int minute) {
        int m = Math.max(0, minute);
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
