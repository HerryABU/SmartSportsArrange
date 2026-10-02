package com.sports.service.excel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「导入计划」（前端确认后的那份 sheets JSON）的解析与匹配。
 *
 * <h3>为什么单独成一个文件</h3>
 * <p>多表导入的 plan 是自由形状的（{@code sheets} 既可能是 JSON 字符串也可能是数组，
 * 每项字段可能是数字也可能是字符串），解析逻辑塞在 {@link MultiTableImportService} 里会让那个类
 * 混杂「计划解析 + 探测 + 导入 + 报告组装」四件事。这里只负责<b>把脏输入变成一个干净、可匹配的
 * {@link PlanEntry} 列表</b>，其余一概不管，因此可独立单测。</p>
 *
 * <h3>用户「按指定重新解析」走的就是这条路</h3>
 * <p>前端在预览面板上改了某张表的类型 / 列映射 / 是否参与，把改动序列化成 plan 再打回后端，
 * 后端据此重新判定并重新出预览 —— 不必重新上传文件（前提是浏览器里仍持有同一个 File 对象）。</p>
 */
public final class ImportPlanParser {

    /** 计划里一张表的覆盖项。 */
    public record PlanEntry(
            /** 文件序号（优先）或文件名 */
            Integer fileIndex,
            String file,
            /** Sheet 序号（优先）或 Sheet 名 */
            Integer sheetIndex,
            String sheet,
            /** 人工指定的类型；null/空 = 自动判定 */
            String type,
            /** 人工指定的列映射：列号字符串 → 字段名 */
            Map<String, String> columnMap,
            /** 是否参与导入；true = 只按计划处理，false = 明确排除 */
            Boolean include
    ) {
        public boolean isIncluded() {
            return include == null || include;
        }
    }

    /** 解析结果 */
    public record PlanInfo(boolean hasHeader, List<PlanEntry> entries) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认：有表头、无覆盖项（等价于老行为，保证旧调用方不受影响）。 */
    public static final PlanInfo EMPTY = new PlanInfo(true, List.of());

    private ImportPlanParser() {
    }

    // ==================== 解析 ====================

    /**
     * 解析前端传来的 plan。容忍 {@code sheets} 是 JSON 字符串或数组两种形态，
     * 且每项的 {@code columnMap} 同样是「JSON 字符串或 Map」两种形态 —— 早先前端用
     * {@code JSON.stringify} 拼、后端用 {@code ObjectMapper} 回读，两边各写过一遍解析，
     * 拆开后只剩这一处。
     */
    public static PlanInfo parse(Map<String, Object> plan) {
        if (plan == null || plan.isEmpty()) {
            return EMPTY;
        }
        return new PlanInfo(hasHeader(plan), sheetsOf(plan.get("sheets")));
    }

    /** 只判 hasHeader（构建 SheetJob 前就要用，不能等 sheets 解析完）。 */
    public static boolean hasHeader(Map<String, Object> plan) {
        if (plan == null) {
            return true;
        }
        Object v = plan.get("hasHeader");
        if (v == null) {
            return true;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return !"false".equalsIgnoreCase(String.valueOf(v).trim());
    }

    // ==================== 匹配 ====================

    /**
     * 在计划里找到匹配「这个文件的这张表」的覆盖项。
     *
     * <p>匹配口径：<b>文件按「序号优先、名字兜底」，Sheet 同理</b>。前端在预览面板上看到的是
     * Sheet 名，但跨文件重命名后名字会变，所以序号必须优先。</p>
     *
     * @return 匹配不到返回 null（表示该表未被计划覆盖，走自动判定）
     */
    public static PlanEntry match(List<PlanEntry> entries, int fileIndex, String fileName, ExcelSheetRef ref) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        PlanEntry byIndex = null;
        PlanEntry byName = null;
        for (PlanEntry e : entries) {
            if (e.fileIndex() != null && e.fileIndex() == fileIndex
                    && e.sheetIndex() != null && e.sheetIndex() == ref.index()) {
                byIndex = e;
                break;
            }
            if (e.file() != null && ref.name() != null && e.file().equals(fileName) && e.sheet() != null
                    && ref.name().equals(e.sheet())) {
                byName = e;
            }
        }
        return byIndex != null ? byIndex : byName;
    }

    /** 把计划项里的 columnMap 抽成干净的「列号 → 字段名」。 */
    public static Map<String, String> columnMapOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().toString().isBlank()) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()).trim());
                }
            }
            return out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                Map<String, String> out = new LinkedHashMap<>();
                Map<?, ?> m = MAPPER.readValue(s, new TypeReference<Map<String, String>>() {
                });
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getValue() != null && !String.valueOf(e.getValue()).trim().isEmpty()) {
                        out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()).trim());
                    }
                }
                return out;
            } catch (Exception ignore) {
                return Map.of();
            }
        }
        return Map.of();
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private static List<PlanEntry> sheetsOf(Object raw) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                items = MAPPER.readValue(s, new TypeReference<List<Map<String, Object>>>() {
                });
            } catch (Exception ignore) {
                items = List.of();
            }
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    items.add((Map<String, Object>) m);
                }
            }
        }

        List<PlanEntry> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object includeRaw = item.get("include");
            Boolean include = null;
            if (includeRaw != null) {
                String s = String.valueOf(includeRaw).trim();
                include = !"false".equalsIgnoreCase(s) && !"0".equals(s);
            }
            out.add(new PlanEntry(
                    intOf(item.get("fileIndex")),
                    str(item.get("file")),
                    intOf(item.get("sheetIndex")),
                    str(item.get("sheet")),
                    str(item.get("type")),
                    columnMapOf(item.get("columnMap")),
                    include));
        }
        return out;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOf(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
