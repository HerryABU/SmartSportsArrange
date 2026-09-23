package com.sports.common.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 年级称谓归一化（模糊年级）。
 *
 * <p>现实里同一年级有大量写法：{@code 高一 / 高一年级 / 高中一年级 / 10年级 / 十年级 / Grade 10 / G10}
 * 都指同一年级；{@code 初一 / 初一年级 / 初中一年级 / 七年级 / 7年级} 同理；小学
 * {@code 一年级 / 1年级 / 小学一年级 / 小一} 亦然。历史库里
 * {@code ClassInfo.grade}/{@code Athlete.grade} 存短称（"高一"、"初一"），而系统设置/年级管理/
 * 日程/项目 {@code grade_group} 常用带后缀（"高一年级"），导入的 Excel 又可能是"10年级"。
 * 任何按年级精确 equals 比较的地方都必须先经本工具归一，否则永远匹配不上。</p>
 *
 * <p>核心概念：</p>
 * <ul>
 *   <li>{@link #key(String)} —— 等价键（如 {@code G10}）。只要 key 相同即同一年级。</li>
 *   <li>{@link #norm(String)} —— 规范显示名（{@code 高一} / {@code 初一} / {@code 一年级}），写库用它。</li>
 *   <li>{@link #order(String)} —— 年级序号 1..12（小学 1-6、初中 7-9、高中 10-12），用于排序。</li>
 *   <li>{@link #equivalents(String)} —— 全部常见写法，供 SQL {@code IN} 查询兼容存量数据。</li>
 *   <li>{@link #classKey(String)} —— 班级名归一化键（{@code 高三年级1班} → {@code G12#1}），班级查重用它。</li>
 * </ul>
 */
public final class Grades {

    private Grades() {
    }

    /** 无法识别为年级时的前缀（保证"原样字符串"仍能自比相等） */
    private static final String RAW = "RAW:";

    /** 学段基准（12 年制）：小学 0、初中 6、高中 9 */
    private static final int BASE_PRIMARY = 0;
    private static final int BASE_JUNIOR = 6;
    private static final int BASE_SENIOR = 9;

    /** Grade 10 / G10 / 10th */
    private static final Pattern GRADE_EN =
            Pattern.compile("^(?:grade|gr|g)[\\s._-]*(\\d{1,2})(?:st|nd|rd|th)?$", Pattern.CASE_INSENSITIVE);
    /** 学段前缀 + 数字：高一年级 / 高中一年级 / 小一 / 初一 / 10年级（不带前缀走后续规则） */
    private static final Pattern PREFIXED =
            Pattern.compile("^(小学|初中|高中|小|初|高)([一二三四五六七八九十]{1,3}|\\d{1,2})(?:年级|级)?$");
    /** 纯阿拉伯数字：10年级 / 10 */
    private static final Pattern PURE_NUM = Pattern.compile("^(\\d{1,2})(?:年级|级)?$");
    /** 纯中文数字：一年级 / 十年级 / 十二年级 */
    private static final Pattern CN_NUM = Pattern.compile("^([一二三四五六七八九十]{1,3})(?:年级|级)?$");

    // ==================== 规范化字面量 ====================

    /** 全角转半角、去空白与括号装饰（"高三（1）班" → "高三1班"） */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.trim().toCharArray()) {
            char x = ch;
            if (x >= '０' && x <= '９') x = (char) (x - '０' + '0');
            if (x >= 'Ａ' && x <= 'Ｚ') x = (char) (x - 'Ａ' + 'A');
            if (x >= 'ａ' && x <= 'ｚ') x = (char) (x - 'ａ' + 'a');
            if (x == '（' || x == '）' || x == '(' || x == ')') continue;   // 括号是装饰，去掉
            if (x == '　' || Character.isWhitespace(x)) continue;
            sb.append(x);
        }
        return sb.toString();
    }

    /** 中文数字词 → 1..12；无法识别返回 null */
    private static Integer cnToInt(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.startsWith("十")) {
            String rest = s.substring(1);
            if (rest.isEmpty()) return 10;
            Integer r = cnToInt(rest);
            return r == null ? null : 10 + r;
        }
        return switch (s) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            default -> null;
        };
    }

    /** 1..12 → 中文数字词 */
    public static String cn(int n) {
        String[] d = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n <= 0 || n > 12) return String.valueOf(n);
        if (n < 10) return d[n];
        if (n == 10) return "十";
        return n == 11 ? "十一" : "十二";
    }

    private static Integer num(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return cnToInt(token);
    }

    // ==================== 年级解析 ====================

    /**
     * 解析为年级序号 1..12（小学 1-6、初中 7-9、高中 10-12）；无法识别返回 {@code null}。
     *
     * <p>规则优先级：{@code Grade 10} → 学段前缀（高一年级=10）→ 纯数字（10年级=10）→ 纯中文数字（十年级=10）。
     * 「高一年级」必须解析为 10 而非 1，所以学段前缀优先于纯中文数字。</p>
     */
    public static Integer parse(String grade) {
        String c = clean(grade);
        if (c.isEmpty()) return null;

        Matcher en = GRADE_EN.matcher(c);
        if (en.matches()) return range(num(en.group(1)));

        Matcher p = PREFIXED.matcher(c);
        if (p.matches()) {
            Integer k = num(p.group(2));
            if (k == null) return null;
            return range(baseOf(p.group(1)) + k);
        }

        Matcher ar = PURE_NUM.matcher(c);
        if (ar.matches()) return range(num(ar.group(1)));

        Matcher cn = CN_NUM.matcher(c);
        if (cn.matches()) return range(num(cn.group(1)));

        return null;
    }

    private static int baseOf(String prefix) {
        return switch (prefix) {
            case "小学", "小" -> BASE_PRIMARY;
            case "初中", "初" -> BASE_JUNIOR;
            default -> BASE_SENIOR;   // 高中 / 高
        };
    }

    private static Integer range(Integer n) {
        if (n == null || n < 1 || n > 12) return null;
        return n;
    }

    // ==================== 对外 API ====================

    /** 等价键：同一年级的所有写法 → 同一 key（如 {@code G10}）；无法识别 → {@code RAW:原文} */
    public static String key(String grade) {
        Integer n = parse(grade);
        return n == null ? RAW + clean(grade) : "G" + n;
    }

    /**
     * 规范显示名（写库用）：
     * 小学 {@code 一年级..六年级}、初中 {@code 初一..初三}、高中 {@code 高一..高三}。
     * 无法识别时返回 trim 后的原文（不至于丢失信息）。
     */
    public static String norm(String grade) {
        if (grade == null) return null;
        Integer n = parse(grade);
        if (n == null) {
            String t = grade.trim();
            return t.isEmpty() ? null : t;
        }
        return display(n);
    }

    private static String display(int n) {
        if (n <= 6) return cn(n) + "年级";
        if (n <= 9) return "初" + cn(n - 6);
        return "高" + cn(n - 9);
    }

    /** 年级序号 1..12；无法识别 → 0（便于排序时排到最后） */
    public static int order(String grade) {
        Integer n = parse(grade);
        return n == null ? 0 : n;
    }

    /** 去掉「年级」后缀（历史行为，等价于 {@link #norm}）："高一年级"→"高一"；null → null */
    public static String shortName(String g) {
        return norm(g);
    }

    /** 等价判断：{@code same("高一","10年级") == true}。任一方为 null → false。 */
    public static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return key(a).equals(key(b));
    }

    /**
     * 该年级的全部常见写法（含规范名），供 SQL {@code IN} 查询，以便命中存量数据里的各种旧写法。
     * 无法识别时只返回原文本身。
     */
    public static List<String> equivalents(String grade) {
        Set<String> out = new LinkedHashSet<>();
        String raw = grade == null ? null : grade.trim();
        if (raw != null && !raw.isEmpty()) out.add(raw);

        Integer n = parse(grade);
        if (n == null) return new ArrayList<>(out);

        out.add(display(n));                        // 高一
        out.add(display(n) + "年级");                // 高一年级
        out.add(n + "年级");                         // 10年级
        out.add(cn(n) + "年级");                     // 十年级 / 一年级
        out.add(stageFull(n));                      // 高中一年级
        out.add(stageShort(n));                     // 高一
        out.add("Grade " + n);                      // Grade 10
        out.add("G" + n);                           // G10
        return new ArrayList<>(out);
    }

    private static String stageFull(int n) {
        if (n <= 6) return "小学" + cn(n) + "年级";
        if (n <= 9) return "初中" + cn(n - 6) + "年级";
        return "高中" + cn(n - 9) + "年级";
    }

    private static String stageShort(int n) {
        if (n <= 6) return "小" + cn(n);
        if (n <= 9) return "初" + cn(n - 6);
        return "高" + cn(n - 9);
    }

    // ==================== 班级名归一化 ====================

    /**
     * 解析「年级 + 班号」，返回 {@code {年级序号, 班号}}；无法解析返回 {@code null}。
     *
     * <p>中文数字与年级数字同字符集（"高三一班" 的 "三一" 既可读作年级三+班号一，
     * 也可读作 …），故不做正则贪婪匹配，而是**穷举班号长度**，取第一个「年级可解析」的切分：
     * {@code 高三一班} → 班号"一"+年级"高三"；{@code 高三12班} → 班号"12"+年级"高三"。</p>
     */
    private static int[] parseClassParts(String className) {
        String c = clean(className);
        if (c.isEmpty() || !c.endsWith("班")) return null;
        String body = c.substring(0, c.length() - 1);
        if (body.isEmpty()) return null;
        for (int cut = 1; cut <= 3 && cut < body.length(); cut++) {
            String noToken = body.substring(body.length() - cut);
            String gradeToken = body.substring(0, body.length() - cut);
            Integer g = parse(gradeToken);
            Integer no = num(noToken);
            if (g != null && no != null && no >= 1 && no <= 99) return new int[]{g, no};
        }
        return null;
    }

    /**
     * 班级名归一化键：{@code 高三1班} / {@code 高三年级1班} / {@code 高三（1）班} / {@code 10年级1班}
     * 全部得到 {@code G12#1}。年级部分解析不出时返回 {@code RAW:原文}（保证不同年级的"1班"不会被误判同班）。
     */
    public static String classKey(String className) {
        String c = clean(className);
        if (c.isEmpty()) return null;
        int[] p = parseClassParts(c);
        return p == null ? RAW + c : "G" + p[0] + "#" + p[1];
    }

    /**
     * 班级名规范显示（写库用）：{@code 高三年级（1）班} → {@code 高三1班}。
     * 无法解析出「年级+班号」时返回 clean 后的原文（保留"实验班"这类非标准命名）。
     */
    public static String normClassName(String className) {
        String c = clean(className);
        if (c.isEmpty()) return className == null ? null : className.trim();
        int[] p = parseClassParts(c);
        return p == null ? c : display(p[0]) + p[1] + "班";
    }
}
