package com.sports.service.excel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张「待导入的表」：文件定位 + 表头 + 已读出的原始行 + 人工覆盖（类型 / 列映射 / 是否参与）。
 *
 * <p>之所以从 {@link MultiTableImportService} 里抽成独立类型：多表导入链路上有三个角色都要用到它——
 * ①{@link MultiTableImportService} 探测与导入，②{@link ImportPlanParser} 按计划挑表，
 * ③{@link ExcelSheetInspector} 做可视化预览。放在某个 Service 内部就成了隐式私有契约，
 * 后来加一个能力就得解锁（private record 不能跨类引用）。独立成 record 后谁都能构造，互不依赖。</p>
 *
 * <p>{@code rows} 是<b>原始行</b>（列号 → 单元格值，表头行也保留在原位），由
 * {@link ExcelSheetReader#readRows} 读出一次后反复复用，避免同一个 MultipartFile 被反复解析
 * （MultipartFile 的流只能读一次）。</p>
 */
public record SheetJob(
        /** 文件序号（与上传 files[] 的角标一致） */
        int fileIndex,
        /** 文件原始名 */
        String fileName,
        /** Sheet 序号（0-based） */
        int sheetIndex,
        /** Sheet 名 */
        String sheetName,
        /** 表头行（hasHeader=false 时为空表） */
        List<String> headers,
        /** 全部原始行（含表头行，除非 hasHeader=false） */
        List<Map<Integer, String>> rows,
        /** 人工指定的类型（null = 交给自动判定） */
        String overrideType,
        /** 人工指定的列映射（列号字符串 → 字段名；空 = 交给自动映射） */
        Map<String, String> overrideColumnMap,
        /** 是否参与本次导入（false = 用户勾选排除） */
        boolean include
) {

    public SheetJob {
        if (overrideColumnMap == null) overrideColumnMap = Map.of();
        if (headers == null) headers = List.of();
        if (rows == null) rows = List.of();
    }

    /** 人工覆盖后的类型：非空即代表「人工指定」。 */
    public String effectiveType() {
        return (overrideType == null || overrideType.isBlank()) ? null : overrideType.trim();
    }

    /** 生成一份「换了列映射」的新 SheetJob（前端改完映射回填用，不改类型判定结果以外的东西）。 */
    public SheetJob withColumnMap(Map<String, String> columnMap) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (columnMap != null) {
            copy.putAll(columnMap);
        }
        return new SheetJob(fileIndex, fileName, sheetIndex, sheetName, headers, rows, overrideType, copy, include);
    }

    /** 生成一份「换了类型」的新 SheetJob（列映射若仍为空则保持空，由自动映射补齐）。 */
    public SheetJob withType(String type) {
        return new SheetJob(fileIndex, fileName, sheetIndex, sheetName, headers, rows, type, overrideColumnMap, include);
    }
}
