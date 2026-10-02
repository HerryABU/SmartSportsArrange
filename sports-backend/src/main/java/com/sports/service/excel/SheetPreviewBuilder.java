package com.sports.service.excel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单张表的「预览报告」组装：判定成什么类型、列怎么对、哪些列没对上、给几行样例、能不能导。
 *
 * <p>从 {@link MultiTableImportService#preview} 里拆出来的理由很直接：探测（预览）和导入是两条链路，
 * 但「一张表长什么样、映射成什么样」这半段逻辑是共用的。原先它埋在 preview 的 for 循环里，
 * 后来做「按用户指定重新解析」和「可视化网格预览」都要复用同一份字段口径，
 * 再不抽出来就会各写各的，前端拿到的 JSON 字段开始漂移。</p>
 *
 * <p>本类<b>不碰数据库、不碰 Spring</b>，纯拼装 Map —— 报告形状就是给前端看的契约，
 * 放在这里比散在 Service 里好单测（断言字段名即可）。</p>
 */
public final class SheetPreviewBuilder {

    /** 默认样例行数：预览面板上够看清「这一列到底读到了什么」。 */
    public static final int DEFAULT_SAMPLE_SIZE = 5;
    /** 前端可视化预览时想要更多行 */
    public static final int LARGE_SAMPLE_SIZE = 30;

    private SheetPreviewBuilder() {
    }

    // ==================== 主入口 ====================

    /**
     * @param job        待探测的表（已含人工覆盖）
     * @param hasHeader  第 0 行是否是表头
     * @param sampleSize 样例行数；&lt;=0 时用 {@link #DEFAULT_SAMPLE_SIZE}
     * @param withAdvice 是否附带逐列「可选字段」下拉数据（预览面板要人工改列映射时才需要，代价略高）
     */
    public static Map<String, Object> build(SheetJob job, boolean hasHeader, int sampleSize, boolean withAdvice) {
        int n = sampleSize <= 0 ? DEFAULT_SAMPLE_SIZE : sampleSize;
        String type = SheetTypeResolver.resolve(job.sheetName(), job.headers(), job.effectiveType());
        Map<String, String> columnMap = effectiveColumnMap(job, type);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sheetIndex", job.sheetIndex());
        s.put("sheetName", job.sheetName());
        s.put("headers", job.headers());
        s.put("type", type);
        s.put("resolvedBy", resolvedBy(job));
        s.put("columnMap", columnMap);
        s.put("mappedFields", describeMapped(type, job.headers(), columnMap));
        s.put("unmappedHeaders", unmappedHeaders(job.headers(), columnMap));
        s.put("totalRows", Math.max(0, job.rows().size() - (hasHeader ? 1 : 0)));
        s.put("sampleRows", sampleRows(job, columnMap, hasHeader, n));
        s.put("importable", type != null && !columnMap.isEmpty());
        s.put("reason", skipReason(type, columnMap));
        if (withAdvice) {
            s.put("advice", ImportColumnAdvisor.advise(type, job.headers()));
            s.put("fieldLabels", ExcelColumnMapping.fieldsOf(type));
        }
        return s;
    }

    /** 无列映射覆盖时用自动映射，有则用人工的（人工优先，这是「按用户指定解析」的落点）。 */
    public static Map<String, String> effectiveColumnMap(SheetJob job, String type) {
        if (!job.overrideColumnMap().isEmpty()) {
            return job.overrideColumnMap();
        }
        return SheetTypeResolver.autoColumnMap(type, job.headers());
    }

    /** 该表由什么判定出来：人工指定 / 表头指纹 / 表名关键词（认不出则 null）。 */
    public static String resolvedBy(SheetJob job) {
        if (job.effectiveType() != null) {
            return "override";
        }
        if (SheetTypeResolver.inferByHeader(job.headers()) != null) {
            return "header";
        }
        return ExcelService.detectTypeOrNull(job.sheetName()) != null ? "name" : null;
    }

    /** 不能导的原因（能导返回 null）。前端据此提示「可在列表里手动指定类型 / 列映射」。 */
    public static String skipReason(String type, Map<String, String> columnMap) {
        if (type == null) {
            return "无法识别该表类型（Sheet 名与表头都不足以判定），可在列表里手动指定类型";
        }
        if (columnMap == null || columnMap.isEmpty()) {
            return "表头没有可识别的列（与「" + type + "」的字段不匹配），可在列表里手动指定列映射";
        }
        return null;
    }

    // ==================== 明细 ====================

    /** 「表头 → 字段」清单（前端 chip 展示用）。 */
    public static List<Map<String, String>> describeMapped(String type, List<String> headers,
                                                           Map<String, String> columnMap) {
        List<Map<String, String>> list = new ArrayList<>();
        if (columnMap == null) {
            return list;
        }
        for (Map.Entry<String, String> e : columnMap.entrySet()) {
            int col;
            try {
                col = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ignore) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("column", String.valueOf(col));
            m.put("header", col < headers.size() ? headers.get(col) : "");
            m.put("field", e.getValue());
            m.put("label", ExcelColumnMapping.getFieldLabel(type, e.getValue()));
            list.add(m);
        }
        return list;
    }

    /** 没有被任何映射覆盖的表头（前端标灰）。 */
    public static List<String> unmappedHeaders(List<String> headers, Map<String, String> columnMap) {
        List<String> unmapped = new ArrayList<>();
        if (headers == null) {
            return unmapped;
        }
        Set<String> mappedCols = columnMap == null ? Set.of() : columnMap.keySet();
        for (int c = 0; c < headers.size(); c++) {
            String h = headers.get(c);
            if (h == null || h.isBlank()) {
                continue;
            }
            if (!mappedCols.contains(String.valueOf(c))) {
                unmapped.add(h);
            }
        }
        return unmapped;
    }

    /**
     * 样例行：按<b>映射后的字段顺序</b>取值（不是原始列顺序），前端直接当表格列渲染。
     */
    public static List<List<String>> sampleRows(SheetJob job, Map<String, String> columnMap,
                                                boolean hasHeader, int limit) {
        List<List<String>> samples = new ArrayList<>();
        int from = hasHeader ? 1 : 0;
        for (int r = from; r < job.rows().size() && samples.size() < limit; r++) {
            Map<Integer, String> row = job.rows().get(r);
            if (ExcelSheetReader.isBlankRow(row)) {
                continue;
            }
            List<String> line = new ArrayList<>();
            for (String col : columnMap.keySet()) {
                try {
                    String v = row.get(Integer.parseInt(col));
                    line.add(v == null ? "" : v);
                } catch (NumberFormatException ignore) {
                    line.add("");
                }
            }
            samples.add(line);
        }
        return samples;
    }
}
