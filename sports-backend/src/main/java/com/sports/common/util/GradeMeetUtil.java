package com.sports.common.util;

/**
 * 届 × 年级 × 入毕年份 的递归计算与「校验号」拼装。
 *
 * <p>设计要点（与 {@link Grades} 配合使用）：</p>
 * <ul>
 *   <li>运动员库内 {@code grade} 视为<b>入学时年级（基准年级）</b>；当前届下的「当前年级」
 *       由基准年级 +（当前届年份 − 入学年份）递归推导，即<b>自然升级</b>每年 +1。</li>
 *   <li>入毕年份码：{@code 2025 入学 / 2028 毕业 → "20252028"}（两位年份直接拼接，便于印刷 / 校验）。</li>
 *   <li>毕业判定：当前届年份 ≥ 毕业年份即视为毕业生（{@code graduated}）。</li>
 *   <li>校验号：{@code 第X届Y季节运动会-年份码-年级[-学号]}，作为跨届唯一可校验标识，
 *       用于号码簿 / 成绩册 / 进步榜的同一学生身份对齐。</li>
 * </ul>
 *
 * <p>本工具只依赖基础类型与 {@link Grades}，不耦合实体，方便单测与复用。</p>
 */
public final class GradeMeetUtil {

    private GradeMeetUtil() {
    }

    /**
     * 入毕年份码：{@code enrollYear=2025, graduateYear=2028 → "20252028"}。
     * 任一年份为空返回 {@code null}（无法构成校验号）。
     */
    public static String composeYearCode(Integer enrollYear, Integer graduateYear) {
        if (enrollYear == null || graduateYear == null) return null;
        return String.valueOf(enrollYear) + String.valueOf(graduateYear);
    }

    /**
     * 当前届下的「当前年级序号」：基准年级 +（当前届年份 − 入学年份）。
     * 自然升级：每多过一年自动 +1。结果收敛到 1..12（低于 1 取基准、高于 12 取 12，
     * 具体是否毕业由 {@link #isGraduated} 判定）。
     *
     * @param baseGradeOrder 基准年级序号 1..12（见 {@link Grades#order}）
     * @param meetYear       当前届举办年份
     * @param enrollYear     入学年份（null → 直接返回基准）
     */
    public static int currentGradeOrder(int baseGradeOrder, Integer meetYear, Integer enrollYear) {
        if (meetYear == null || enrollYear == null) return baseGradeOrder;
        int order = baseGradeOrder + (meetYear - enrollYear);
        if (order < baseGradeOrder) return baseGradeOrder;   // 未到入学年：维持基准
        if (order > 12) return 12;
        return order;
    }

    /**
     * 当前届下的「当前年级」展示名（如「高一」）。无法计算时回退基准年级展示名。
     */
    public static String currentGradeDisplay(String baseGrade, Integer meetYear, Integer enrollYear) {
        Integer base = Grades.order(baseGrade);
        if (base == null || base == 0) return baseGrade;
        int order = currentGradeOrder(base, meetYear, enrollYear);
        return Grades.display(order);
    }

    /** 是否毕业：当前届年份 ≥ 毕业年份 */
    public static boolean isGraduated(Integer graduateYear, Integer meetYear) {
        if (graduateYear == null || meetYear == null) return false;
        return meetYear >= graduateYear;
    }

    /**
     * 拼装校验号：{@code 第X届Y季节运动会-年份码-年级[-学号]}。
     * 示例：{@code 第3届秋季运动会-20252028-高一-1001}。任一核心项缺失则该段省略，但至少返回届名。
     */
    public static String composeCheckNo(String meetName, String yearCode, String gradeDisplay, String studentNo) {
        StringBuilder sb = new StringBuilder();
        sb.append(meetName == null ? "" : meetName);
        if (yearCode != null) sb.append("-").append(yearCode);
        if (gradeDisplay != null && !gradeDisplay.isBlank()) sb.append("-").append(gradeDisplay);
        if (studentNo != null && !studentNo.isBlank()) sb.append("-").append(studentNo);
        return sb.toString();
    }
}
