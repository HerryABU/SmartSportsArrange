package com.sports.service.excel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量（多表）导入的「批次守卫」：在一次导入内做三件事——
 * <ol>
 *   <li><b>文件内去重</b>：同一实体（同一个人 / 同一个项目 / 同一条报名）只导一次，重复行给出定位；</li>
 *   <li><b>同键信息冲突</b>：同一实体的姓名/性别/班级/项目名称等<b>不一致</b>时列出双方取值，供人工核对；</li>
 *   <li><b>名单 vs 报名一致性</b>：报名里的人不在名单、或姓名/性别/班级与名单不符 → 单独报告。</li>
 * </ol>
 *
 * <p>纯内存、无 Spring 依赖、无 DB 访问，便于单测。使用分两阶段：</p>
 * <pre>
 *   // 阶段 A：先扫「名单类」表建立索引（不占用去重名额）
 *   guard.indexAthlete("roster", row);
 *   // 阶段 B：按导入依赖顺序逐表逐行校验
 *   List&lt;Issue&gt; issues = guard.check("signup", row);
 *   boolean duplicate = issues.stream().anyMatch(i -&gt; i.kind() != Kind.ROSTER_MISMATCH);
 * </pre>
 *
 * <p><b>键口径</b>：优先用最稳定的业务键（学号 &gt; 号码 &gt; 姓名@班级；项目编码 &gt; 项目名称），
 * 避免用易变的显示名当唯一键。</p>
 */
public class ImportBatchGuard {

    public enum Kind {
        /** 同一实体重复出现（字段一致）。 */
        DUPLICATE,
        /** 同一实体重复出现但字段不一致。 */
        CONFLICT,
        /** 报名信息与名单不一致（或名单中查无此人）。 */
        ROSTER_MISMATCH
    }

    /** 一条问题记录；{@code detail} 面向人工核对（不含行号，行号由调用方补）。 */
    public record Issue(Kind kind, String detail) {
    }

    private record Seen(String where, Map<String, String> fields) {
    }

    /** 键 → 首次出现（含位置与字段快照）。 */
    private final Map<String, Seen> seen = new LinkedHashMap<>();

    /** 名单索引：运动员键 → 字段快照（供报名一致性校验）。 */
    private final Map<String, Map<String, String>> rosterIndex = new LinkedHashMap<>();

    /** 各命名空间参与「冲突」比对的字段（顺序即提示顺序）。 */
    private static final Map<String, List<String>> COMPARE_FIELDS = new LinkedHashMap<>();

    /** 字段 → 中文名（提示用）。 */
    private static final Map<String, String> FIELD_LABELS = new LinkedHashMap<>();

    static {
        COMPARE_FIELDS.put("athlete", List.of("name", "gender", "className", "grade"));
        COMPARE_FIELDS.put("project", List.of("name", "eventName", "category"));
        COMPARE_FIELDS.put("clazz", List.of("name", "code", "grade", "teacherName"));
        COMPARE_FIELDS.put("grade", List.of("name"));
        COMPARE_FIELDS.put("user", List.of("realName", "role"));
        COMPARE_FIELDS.put("signup", List.of("name", "gender", "className", "grade"));
        COMPARE_FIELDS.put("score", List.of("rawTime", "lane", "heat"));

        FIELD_LABELS.put("name", "姓名");
        FIELD_LABELS.put("eventName", "项目名称");
        FIELD_LABELS.put("gender", "性别");
        FIELD_LABELS.put("className", "班级");
        FIELD_LABELS.put("grade", "年级");
        FIELD_LABELS.put("code", "编码");
        FIELD_LABELS.put("teacherName", "班主任");
        FIELD_LABELS.put("realName", "姓名");
        FIELD_LABELS.put("role", "角色");
        FIELD_LABELS.put("category", "项目类型");
        FIELD_LABELS.put("rawTime", "成绩");
        FIELD_LABELS.put("lane", "道次");
        FIELD_LABELS.put("heat", "组别");
    }

    // ==================== 阶段 A：建名单索引 ====================

    /**
     * 把一行「名单类」数据计入名单索引（供报名一致性校验）。
     * 只处理 athlete / roster / athlete_signup；不占用去重名额。
     */
    public void indexAthlete(String type, Map<String, String> v) {
        String ns = namespace(type);
        if (!"athlete".equals(ns)) {
            return;
        }
        String k = athleteKey(v);
        if (k != null) {
            rosterIndex.putIfAbsent(k, snapshot(v, COMPARE_FIELDS.get("athlete")));
        }
    }

    /** 该运动员键是否已在名单索引中。 */
    public boolean hasAthlete(String key) {
        return key != null && rosterIndex.containsKey(key);
    }

    // ==================== 阶段 B：逐行校验 ====================

    /**
     * 校验一行；返回问题列表。
     *
     * <p>返回的列表中若含 {@link Kind#DUPLICATE} 或 {@link Kind#CONFLICT}，说明该实体已出现过，
     * 调用方应<b>跳过该行不再导入</b>；{@link Kind#ROSTER_MISMATCH} 只提示、不阻止导入。</p>
     *
     * @param type  表类型（SheetTypeResolver 的类型 id）
     * @param v     该行按列映射后的字段值
     * @param where 位置描述（如「名单.xlsx[报名] 第 12 行」）
     */
    public List<Issue> check(String type, Map<String, String> v, String where) {
        List<Issue> issues = new ArrayList<>();
        String ns = namespace(type);
        String k = keyOf(ns, type, v);
        if (k == null) {
            return issues; // 无稳定业务键 → 不做去重（交由后端逐行校验）
        }
        String full = ns + "|" + k;
        Seen prev = seen.get(full);
        if (prev != null) {
            List<String> diffs = diff(ns, prev.fields(), v);
            if (diffs.isEmpty()) {
                issues.add(new Issue(Kind.DUPLICATE,
                        "重复行（与 " + prev.where() + " 的 " + describeKey(ns, v) + " 相同），已跳过"));
            } else {
                issues.add(new Issue(Kind.CONFLICT,
                        describeKey(ns, v) + " 与 " + prev.where() + " 冲突：" + String.join("、", diffs)
                                + "；已按首次出现的记录为准跳过本行"));
            }
            return issues; // 重复/冲突行不再参与后续校验
        }
        seen.put(full, new Seen(where, snapshot(v, COMPARE_FIELDS.get(ns))));

        // 名单 vs 报名：报名行与名单不符 → 提示（不阻止）
        if ("signup".equals(ns)) {
            Map<String, String> roster = rosterIndex.get(athleteKey(v));
            if (roster != null) {
                List<String> diffs = new ArrayList<>();
                for (String f : List.of("name", "gender", "className", "grade")) {
                    String a = trim(v.get(f));
                    String b = trim(roster.get(f));
                    if (a != null && b != null && !a.equals(b)) {
                        diffs.add(label(f) + "「" + a + "」≠ 名单「" + b + "」");
                    }
                }
                if (!diffs.isEmpty()) {
                    issues.add(new Issue(Kind.ROSTER_MISMATCH,
                            "报名与名单不一致：" + String.join("、", diffs)));
                }
            }
        }
        return issues;
    }

    // ==================== 内部 ====================

    /** 命名空间：把「同一实体」的不同表类型归并（event/eventsimple 都算「项目」）。 */
    private static String namespace(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "event", "eventsimple" -> "project";
            case "athlete", "roster", "athlete_signup" -> "athlete";
            case "class" -> "clazz";
            default -> type;
        };
    }

    private static String keyOf(String ns, String type, Map<String, String> v) {
        return switch (ns) {
            case "athlete" -> athleteKey(v);
            case "project" -> firstNonBlank(v.get("eventCode"), v.get("code"), v.get("name"), v.get("eventName"));
            case "signup" -> {
                String who = athleteKey(v);
                if (who == null) {
                    yield null;
                }
                yield who + "|" + String.valueOf(firstNonBlank(v.get("eventCode"), "")) + "|"
                        + String.valueOf(firstNonBlank(v.get("teamTag"), ""));
            }
            case "clazz" -> firstNonBlank(v.get("code"), v.get("name"));
            case "grade" -> firstNonBlank(v.get("name"));
            case "user" -> firstNonBlank(v.get("username"));
            case "score" -> {
                String who = firstNonBlank(v.get("athleteNumber"), v.get("athleteName"));
                if (who == null) {
                    yield null;
                }
                yield who + "|" + String.valueOf(firstNonBlank(v.get("eventCode"), ""));
            }
            default -> null;
        };
    }

    /** 运动员键：学号 &gt; 号码 &gt; 姓名@班级。 */
    private static String athleteKey(Map<String, String> v) {
        String sid = trim(v.get("studentId"));
        if (sid != null) {
            return "sid:" + sid;
        }
        String num = trim(v.get("number"));
        if (num != null) {
            return "num:" + num;
        }
        String name = trim(v.get("name"));
        if (name != null) {
            return "nm:" + name + "@" + String.valueOf(trim(v.get("className")));
        }
        return null;
    }

    private static Map<String, String> snapshot(Map<String, String> v, List<String> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fields != null) {
            for (String f : fields) {
                out.put(f, trim(v.get(f)));
            }
        }
        return out;
    }

    /** 逐字段比对，返回「字段 旧值 ≠ 新值」提示。 */
    private static List<String> diff(String ns, Map<String, String> prev, Map<String, String> now) {
        List<String> diffs = new ArrayList<>();
        List<String> fields = COMPARE_FIELDS.getOrDefault(ns, List.of());
        for (String f : fields) {
            String a = trim(prev.get(f));
            String b = trim(now.get(f));
            if (a != null && b != null && !a.equals(b)) {
                diffs.add(label(f) + "「" + a + "」≠「" + b + "」");
            }
        }
        return diffs;
    }

    private static String describeKey(String ns, Map<String, String> v) {
        return switch (ns) {
            case "athlete" -> "运动员(" + keyOf("athlete", "athlete", v) + ")";
            case "project" -> "项目(" + keyOf("project", "event", v) + ")";
            case "signup" -> "报名(" + keyOf("signup", "signup", v) + ")";
            case "clazz" -> "班级(" + keyOf("clazz", "class", v) + ")";
            case "grade" -> "年级(" + keyOf("grade", "grade", v) + ")";
            case "user" -> "用户(" + keyOf("user", "user", v) + ")";
            case "score" -> "成绩(" + keyOf("score", "score", v) + ")";
            default -> ns;
        };
    }

    private static String label(String field) {
        return FIELD_LABELS.getOrDefault(field, field);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            String t = trim(v);
            if (t != null) {
                return t;
            }
        }
        return null;
    }
}
