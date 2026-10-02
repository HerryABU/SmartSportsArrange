package com.sports.service.excel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 列 → 字段的「建议」：给前端可视化预览里的每一列算出<b>该怎么对字段</b>，
 * 让管理员能在表格里逐列下拉改，而不是只能看一眼自动映射的结果。
 *
 * <p>和 {@link ExcelColumnMapping} 的分工：那边是<b>判定</b>（给一个表头，回答它是哪个字段，
 * 只出一个答案）；这边是<b>建议</b>（给一整张表的表头，回答「每列可以选哪些字段、哪个最可能」，
 * 出一份带优先级的候选清单）。同一套别名表，两种消费方式。</p>
 *
 * <h3>为什么不直接把 ExcelColumnMapping 的候选也导出来</h3>
 * <p>候选排序必须结合<b>具体表头</b>：同一个「姓名」列，在 {@code athlete} 类型下候选里
 * {@code name/athleteName/realName} 都算合理，但精确命中 {@code name} 的排最前；
 * 在 {@code user} 类型下处理器只读 {@code realName}，于是 {@code realName} 反而该排最前。
 * 这个排序依赖 type × header 的组合，不能写成静态表。</p>
 */
public final class ImportColumnAdvisor {

    /** 候选字段及其匹配等级（越小越靠前） */
    public record FieldOption(String field, String label, int rank) {
    }

    /** 一列的建议结果 */
    public record ColumnAdvice(int column, String header, String field, String label, List<FieldOption> options) {
        public boolean matched() {
            return field != null && !field.isBlank();
        }
    }

    /** 匹配等级：命中该类型的<b>专属</b>别名（最权威，如 class 表的「班级 → name」） */
    public static final int RANK_TYPE_EXACT = 0;
    /** 匹配等级：命中该类型专属别名，但是包含匹配 */
    public static final int RANK_TYPE_CONTAINS = 1;
    /** 匹配等级：命中全局别名的精确相等 */
    public static final int RANK_ALIAS_EXACT = 2;
    /** 匹配等级：命中全局别名，但是包含匹配 */
    public static final int RANK_ALIAS_CONTAINS = 3;
    /** 匹配等级：该类型认不出这个表头（仍可人工选，排在最后） */
    public static final int RANK_NONE = 4;

    /** 等级越小越靠前（给外部排序/筛选用）。 */
    public static int betterRank(int a, int b) {
        return a <= b ? a : b;
    }

    private ImportColumnAdvisor() {
    }

    /**
     * 逐列给建议。
     *
     * @param type    导入类型（null/空时退化为「全局别名」建议）
     * @param headers 表头行
     */
    public static List<ColumnAdvice> advise(String type, List<String> headers) {
        List<ColumnAdvice> out = new ArrayList<>();
        if (headers == null) {
            return out;
        }
        Map<String, String> fields = ExcelColumnMapping.fieldsOf(type);
        for (int c = 0; c < headers.size(); c++) {
            String header = headers.get(c);
            if (header == null || header.isBlank()) {
                continue;
            }
            // 该类型下每个字段与这个表头的关系；同等级时按类型字段声明顺序取第一个（fields 是 LinkedHashMap）
            List<FieldOption> options = new ArrayList<>();
            String bestField = null;
            int bestRank = Integer.MAX_VALUE;
            for (Map.Entry<String, String> f : fields.entrySet()) {
                String field = f.getKey();
                int rank = rankOf(type, header, field);
                options.add(new FieldOption(field, f.getValue(), rank));
                if (rank < bestRank) {
                    bestRank = rank;
                    bestField = field;
                }
            }
            // 稳定排序：等级优先，同级按该类型字段的声明顺序（LinkedHashMap 保序）
            List<String> order = new ArrayList<>(fields.keySet());
            options.sort(Comparator
                    .comparingInt(FieldOption::rank)
                    .thenComparingInt(o -> order.indexOf(o.field())));

            // 认不出这一列时 options 更要给出：没有自动落点，正是靠这份候选清单让管理员手选
            String field = bestRank == RANK_NONE ? null : bestField;
            out.add(new ColumnAdvice(c, header, field,
                    field == null ? null : ExcelColumnMapping.getFieldLabel(type, field), options));
        }
        return out;
    }

    /**
     * 字段与表头的匹配等级（越小越优先）。
     *
     * <p>分级口径和 {@link ExcelColumnMapping#matchHeaderForType} 完全一致，只是<b>不提前收口</b>：
     * 那边问「这个表头是哪个字段」，只给一个答案；这边要问「这个字段配不配这个表头、配到什么程度」，
     * 于是按<b>类型专属别名 → 全局别名</b>两级、每级再分「精确 / 包含」共五档。</p>
     *
     * <p>为什么必须分「专属 vs 全局」：{@code user} 表有「姓名」列，
     * {@code realName} 在它的专属别名表里（真实落点），而 {@code name} 靠全局别名「姓名」也能命中。
     * 两者都是「精确命中」，只比别名表顺序的话 {@code name} 反而可能排前 —— 用户选了却导入为空。
     * 专属别名先于全局别名，才对得上 {@link ExcelColumnMapping} 里那批「导入成功但字段全空」的旧坑。</p>
     */
    public static int rankOf(String type, String header, String field) {
        String h = ExcelColumnMapping.normalize(header);
        if (h == null || h.isEmpty() || field == null || field.isBlank()) {
            return RANK_NONE;
        }
        // 只比「专属表里映射到这个字段的那几个表头别名」。
        //
        // 踩过的坑：一开始把整个专属表的 keySet 丢给 relativeRank，结果只要有任意一个表头命中
        // （如 user 表有「姓名」→ realName），<b>所有</b>字段都拿到「类型专属精确命中」，
        // 于是「姓名」列的首选变成 phone —— 数字看着都很小，实际排序全乱了。
        int typeRank = relativeRank(typeKeysOf(type, field), h);
        if (typeRank != 2) {
            return typeRank; // 0 → 类型专属精确 / 1 → 类型专属包含
        }
        // 类型专属表没命中，才去看这个字段在全局别名表里的候选列名
        List<String> globalAliases = ExcelColumnMapping.COLUMN_ALIASES.getOrDefault(field, List.of());
        int globalRank = relativeRank(globalAliases, h);
        if (globalRank == 0) {
            return RANK_ALIAS_EXACT;
        }
        if (globalRank == 1) {
            return RANK_ALIAS_CONTAINS;
        }
        return RANK_NONE;
    }

    /** 某个字段在本类型专属别名表里对应的<b>表头别名</b>（没有则返回空表）。 */
    private static List<String> typeKeysOf(String type, String field) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> e : ExcelColumnMapping.typeAliases(type).entrySet()) {
            if (e.getValue().equals(field)) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    /**
     * 一组别名与表头的最好的「相对」匹配结果：{@code 0=精确相等、1=包含匹配(取最长别名)、2=无命中}。
     *
     * <p>取「最长别名」是为了复刻 {@link ExcelColumnMapping} 躲过的那个坑：
     * 按别名表顺序取首个包含命中，会让「项目名称」被 {@code eventCode} 的别名「项目」抢走。</p>
     */
    private static int relativeRank(Iterable<String> aliases, String normalizedHeader) {
        boolean contains = false;
        int longestContains = 0;
        for (String alias : aliases) {
            String n = ExcelColumnMapping.normalize(alias);
            if (n == null || n.isEmpty()) {
                continue;
            }
            if (normalizedHeader.equals(n)) {
                return 0; // 精确相等直接胜出，不必再看包含匹配
            }
            if (normalizedHeader.contains(n) || n.contains(normalizedHeader)) {
                if (n.length() > longestContains) {
                    longestContains = n.length();
                    contains = true;
                }
            }
        }
        return contains ? 1 : 2;
    }

    /** 该类型自动映射会落到哪个字段（与 {@link ExcelColumnMapping#matchHeaderForType} 同口径，只是多了精确优先）。 */
    public static String autoFieldOf(String type, String header) {
        String h = ExcelColumnMapping.normalize(header);
        Map<String, String> fields = ExcelColumnMapping.fieldsOf(type);
        for (Map.Entry<String, String> f : fields.entrySet()) {
            if (h.equals(ExcelColumnMapping.normalize(f.getKey()))) {
                return f.getKey();
            }
        }
        for (Map.Entry<String, String> f : fields.entrySet()) {
            String n = ExcelColumnMapping.normalize(f.getKey());
            if (!n.isEmpty() && (h.contains(n) || n.contains(h))) {
                return f.getKey();
            }
        }
        return null;
    }
}
