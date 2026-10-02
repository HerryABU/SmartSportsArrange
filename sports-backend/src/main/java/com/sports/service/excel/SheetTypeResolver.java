package com.sports.service.excel;

import com.sports.service.excel.ExcelColumnMapping;
import com.sports.service.excel.ExcelService;

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
        PRIORITY.put("venue", 6);
        PRIORITY.put("user", 7);
        PRIORITY.put("registration", 8);
        PRIORITY.put("signup", 9);
        // 合一表：既建运动员又写报名，报名依赖「项目」，故必须排在 event/eventsimple 之后、score 之前
        PRIORITY.put("athlete_signup", 10);
        PRIORITY.put("score", 11);
        // 填写说明页：无业务数据、整表跳过；排在最后，不参与任何表头指纹竞争
        PRIORITY.put("notice", 99);
    }

    /** 参与表头指纹判定的候选类型（顺序即平局时的优先级）。 */
    private static final List<String> CANDIDATES = List.of(
            "grade", "class", "roster", "athlete", "event", "eventsimple", "venue", "signup", "registration", "athlete_signup", "score", "user");

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
        LABELS.put("venue", "场地表");
        LABELS.put("user", "用户表");
        LABELS.put("registration", "报名表（旧：含号码）");
        LABELS.put("signup", "报名表（7列：含组号）");
        LABELS.put("athlete_signup", "名单+报名合并表（合一）");
        LABELS.put("score", "成绩表");
        LABELS.put("notice", "填写说明页（自动跳过）");
    }

    /**
     * 说明页识别：Sheet 名叫「填写说明 / 使用说明 / 填写指南…」，或表头就是「字段 + 填写说明」。
     *
     * <p><b>为什么单独一类而不是判成「认不出」</b>：多表模板下载下来就带一张「填写说明」页，
     * 表头是「字段 / 填写说明」。旧逻辑两张列都不属于任何导入类型 → 整表判成「未识别」，
     * 用户看到的提示是「可在列表里手动指定类型」，可这张表<b>本来就没有要导的数据</b>，
     * 让人去指定类型是误导。判成 notice 后能直接给出「说明页，不参与导入」的准确结论。</p>
     *
     * <p>判定条件刻意收窄：Sheet 名必须含明确的说明页关键词，或表头「只有」两列且列名是
     * 字段/说明这类注释性列名 —— 不能因为一张表头里有「备注」就当成说明页。</p>
     */
    private static final List<String> NOTICE_SHEET_KEYWORDS = List.of(
            "填写说明", "使用说明", "说明页", "填写指南", "填写要求", "备注说明", "操作说明",
            "instruction", "guide", "notesheet");

    /** 说明页表头允许的列名（归一化后比较），且表头总列数不得超过 4 列。 */
    private static final List<String> NOTICE_HEADERS = List.of("字段", "说明内容", "填写说明", "说明", "备注", "提示", "用法");

    public static final String NOTICE_TYPE = "notice";

    public static boolean isNotice(String type) {
        return NOTICE_TYPE.equals(type);
    }

    /**
     * 这张表是不是「填写说明」页。
     *
     * @return 是说明页返回 {@link #NOTICE_TYPE}，否则 null
     */
    public static String inferNoticeOrNull(String sheetName, List<String> headers) {
        if (sheetName != null) {
            String l = sheetName.toLowerCase();
            for (String k : NOTICE_SHEET_KEYWORDS) {
                if (l.contains(k)) return NOTICE_TYPE;
            }
        }
        if (headers != null && headers.size() <= 4 && !headers.isEmpty()) {
            boolean allNoticey = true;
            for (String h : headers) {
                if (h == null || h.isBlank()) {
                    continue;
                }
                boolean hit = false;
                for (String n : NOTICE_HEADERS) {
                    if (n.equals(ExcelColumnMapping.normalize(h))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    allNoticey = false;
                    break;
                }
            }
            if (allNoticey) {
                return NOTICE_TYPE;
            }
        }
        return null;
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
        // 顺序：表头指纹 → 说明页 → Sheet 名关键词。
        // 说明页不能抢在表头指纹前面：若 Sheet 名叫「填写说明」但里面装的是真的项目表（有人就这样拿它当项目表用），
        // 只看 Sheet 名就会把整张表当说明页丢掉。表头比名字可靠，这条与类注释的口径一致。
        String byHeader = inferByHeader(headers);
        if (byHeader != null) {
            return byHeader;
        }
        String notice = inferNoticeOrNull(sheetName, headers);
        if (notice != null) {
            return notice;
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
