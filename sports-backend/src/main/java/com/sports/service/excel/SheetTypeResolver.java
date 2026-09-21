package com.sports.service.excel;

import com.sports.service.ExcelColumnMapping;
import com.sports.service.ExcelService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多表导入的「这张表是什么」判定 + 处理顺序。
 *
 * <p><b>为什么不能只看 Sheet 名</b>：真实工作簿里 Sheet 名千奇百怪——
 * 「高一年级」既可能是「按年级拆分的全名单」，也可能是「年级主数据」。
 * 只看名字必然误判，而误判的后果是<b>数据被写进错误的表且不报错</b>。</p>
 *
 * <p>因此判定顺序是：<b>人工指定 → 表头指纹 → Sheet 名关键词 → 认不出就跳过</b>。
 * 表头指纹比名字可靠得多：一个 Sheet 有哪些列，就决定了它是什么表。</p>
 */
public final class SheetTypeResolver {

    /** 表头指纹的最少命中列数：低于它不足以判定（避免把无关表硬塞给某个类型）。 */
    public static final int MIN_HEADER_HITS = 2;

    /**
     * 处理顺序（越小越先）。
     *
     * <p>按<b>数据依赖</b>排：年级 → 班级 → 运动员 → 项目 → 依赖「运动员 + 项目」的报名 → 成绩。
     * 同一本工作簿里 Sheet 的物理顺序由制表人随手决定，若按物理顺序处理，
     * 「报名表排在名单表之前」会让整张报名表全行失败——这不是数据错，而是顺序错。</p>
     */
    private static final Map<String, Integer> PRIORITY = new LinkedHashMap<>();

    static {
        PRIORITY.put("grade", 0);
        PRIORITY.put("class", 1);
        PRIORITY.put("roster", 2);
        PRIORITY.put("athlete", 3);
        PRIORITY.put("event", 4);
        PRIORITY.put("eventsimple", 5);
        PRIORITY.put("user", 6);
        PRIORITY.put("registration", 7);
        PRIORITY.put("signup", 8);
        PRIORITY.put("score", 9);
    }

    /** 参与表头指纹判定的候选类型（顺序即平局时的优先级）。 */
    private static final List<String> CANDIDATES = List.of(
            "grade", "class", "roster", "athlete", "event", "eventsimple", "signup", "registration", "score", "user");

    private SheetTypeResolver() {
    }

    /** 依赖顺序权重（未登记的类型排最后，保持稳定）。 */
    public static int priority(String type) {
        Integer p = type == null ? null : PRIORITY.get(type);
        return p != null ? p : 50;
    }

    /** 全部可导入类型（供前端下拉；顺序即建议的处理顺序）。 */
    public static List<String> allTypes() {
        return List.copyOf(PRIORITY.keySet());
    }

    /**
     * 类型的中文名（供前端下拉展示）。
     *
     * <p>放在后端是为了让「类型目录」只有一个来源：前端不需要维护一份可能过期的中文名映射。</p>
     */
    public static Map<String, String> typeLabels() {
        return LABELS;
    }

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("grade", "年级表");
        LABELS.put("class", "班级表");
        LABELS.put("roster", "全名单表（5列）");
        LABELS.put("athlete", "运动员表（12列）");
        LABELS.put("event", "项目表（表格2）");
        LABELS.put("eventsimple", "运动项目表（7列）");
        LABELS.put("user", "用户表");
        LABELS.put("registration", "报名表（旧：含号码）");
        LABELS.put("signup", "报名表（7列：含组号）");
        LABELS.put("score", "成绩表");
    }

    /**
     * 判定一个 Sheet 的导入类型。
     *
     * @param sheetName Sheet 名（可为空）
     * @param headers   表头行（可为空）
     * @param override  人工指定（非空则直接采用）
     * @return 类型 id；<b>认不出返回 null</b>（调用方应如实报告跳过，绝不猜成 athlete）
     */
    public static String resolve(String sheetName, List<String> headers, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String byHeader = inferByHeader(headers);
        if (byHeader != null) {
            return byHeader;
        }
        return ExcelService.detectTypeOrNull(sheetName);
    }

    /**
     * 表头指纹：哪个类型的字段被这张表的列名命中得最多。
     *
     * <p>命中列数需 ≥ {@link #MIN_HEADER_HITS}，否则返回 null（交给表名判定或跳过，绝不猜）。
     * 命中数相同时<b>按 {@link #CANDIDATES} 的顺序决胜（更具体的类型在前）</b>——
     * 例如「年级/班级/姓名/学号/性别」这 5 列同时命中 roster（5）与 athlete（5），
     * 取更具体的 roster；而 12 列的运动员表因为多出号码布/身份证号等列会把 athlete 顶到更高命中数，
     * 依然正确判为 athlete。之所以不做「并列即放弃」，是因为那样会把最常见的 5 列名单表直接判成「认不出」。</p>
     */
    public static String inferByHeader(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String best = null;
        int bestHits = 0;
        for (String type : CANDIDATES) {
            Set<String> fields = ExcelColumnMapping.fieldsOf(type).keySet();
            int hits = 0;
            for (String h : headers) {
                if (h == null || h.isBlank()) {
                    continue;
                }
                String field = ExcelColumnMapping.matchHeaderForType(type, h);
                if (field != null && fields.contains(field)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = type;
            }
        }
        if (bestHits < MIN_HEADER_HITS) {
            return null;
        }
        return best;
    }

    /**
     * 自动列映射：表头 → 该类型处理器读取的字段。
     *
     * @return 列号(字符串) → 字段名；识别不出的列直接不出现（不猜）
     */
    public static Map<String, String> autoColumnMap(String type, List<String> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        if (type == null || headers == null) {
            return map;
        }
        Set<String> fields = ExcelColumnMapping.fieldsOf(type).keySet();
        for (int c = 0; c < headers.size(); c++) {
            String field = ExcelColumnMapping.matchHeaderForType(type, headers.get(c));
            if (field == null || !fields.contains(field)) {
                continue;
            }
            // 同一字段被多列命中时只保留最靠左的一列（避免后者覆盖前者造成串列）
            if (map.containsValue(field)) {
                continue;
            }
            map.put(String.valueOf(c), field);
        }
        return map;
    }
}
