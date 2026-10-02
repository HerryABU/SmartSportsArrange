package com.sports.service.excel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单张 Sheet 的<b>可视化预览</b>取数：把原始数据按「页」交出去，让前端画出真正的 Excel 网格
 * （而不是只给几行样例摘要）。
 *
 * <p>和 {@link SheetPreviewBuilder} 的区别：那边给的是<b>摘要</b>（判定 + 映射 + 3~5 行样例），
 * 这边给的是<b>网格</b>（指定页的逐行逐单元格 + 行号 + 分页信息）。两者共用同一套类型判定与
 * 「人工映射优先」的列映射口径，所以「预览里看到的列」和「导入时用的列」永远是同一份。</p>
 *
 * <h3>为什么不直接让前端读 Excel</h3>
 * <p>前端没有 xlsx 解析库，且解析口径（表头在哪、列名怎么归一化）必须和后端一致 ——
 * 否则「预览里看着是对的，导入却全空」。所以取数统一走后端，前端只负责画。</p>
 */
@Slf4j
public final class ExcelSheetInspector {

    /** 单页最多交出多少行（防止一张 2 万行的表把一次请求撑爆）。 */
    public static final int MAX_PAGE_SIZE = 200;
    /** 默认每页行数 */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private ExcelSheetInspector() {
    }

    /** 一行网格数据 */
    public record RowView(int rowNo, List<String> cells) {
    }

    /** 一页网格数据 + 这张表的判定现状 */
    public record SheetView(
            int fileIndex,
            String fileName,
            int sheetIndex,
            String sheetName,
            /** 表头行（hasHeader=false 时为空表） */
            List<String> headers,
            /** 列号字符串 → 字段名（与真正导入用的是同一份） */
            Map<String, String> columnMap,
            String type,
            String resolvedBy,
            /** 数据行总数（不含表头行、不含本次分页） */
            int totalRows,
            int page,
            int pageSize,
            boolean hasMore,
            List<RowView> rows
    ) {
    }

    /**
     * 取一页网格。
     *
     * @param file            上传的文件（流只能读一次，这里读完即释放）
     * @param fileName        展示用文件名（调用方从 MultipartFile 取原始名）
     * @param fileIndex       文件序号
     * @param sheetIndex      Sheet 序号
     * @param hasHeader       首行是否表头
     * @param page            页码，从 1 开始；&lt;=0 按 1 处理
     * @param pageSize        每页行数；&lt;=0 按 {@link #DEFAULT_PAGE_SIZE}，超过 {@link #MAX_PAGE_SIZE} 按上限截
     * @param typeOverride    人工指定的类型（null = 自动判定）
     * @param columnOverride  人工指定的列映射（空 = 自动映射）
     */
    public static SheetView inspect(MultipartFile file, String fileName, int fileIndex, int sheetIndex,
                                    boolean hasHeader, int page, int pageSize,
                                    String typeOverride, Map<String, String> columnOverride) {
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int sheet = Math.max(0, sheetIndex);
        int currentPage = page <= 0 ? 1 : page;

        List<Map<Integer, String>> rows;
        String sheetName;
        try {
            rows = ExcelSheetReader.readRows(file, sheet);
            sheetName = sheetNameOf(file, sheet, fileName);
        } catch (Exception e) {
            log.warn("可视化预览：文件「{}」Sheet#{} 读取失败: {}", fileName, sheet, e.toString());
            rows = List.of();
            sheetName = fileName;
        }

        List<String> headers = List.of();
        int from = 0;
        if (hasHeader) {
            headers = ExcelSheetReader.headersOf(rows);
            from = 1;
        }

        // 复用预览口径：人工类型 + 人工映射优先
        SheetJob job = new SheetJob(fileIndex, fileName, sheet, sheetName, headers, rows,
                typeOverride, columnOverride == null ? Map.of() : columnOverride, true);
        String type = SheetTypeResolver.resolve(sheetName, headers, job.effectiveType());
        Map<String, String> columnMap = SheetPreviewBuilder.effectiveColumnMap(job, type);
        String resolvedBy = SheetPreviewBuilder.resolvedBy(job);

        int total = Math.max(0, rows.size() - from);
        int start = from + (currentPage - 1) * size;
        int end = Math.min(start + size, rows.size());

        List<RowView> pageRows = new ArrayList<>();
        if (start < rows.size()) {
            int maxCol = columnCount(headers, hasHeader ? Map.of() : firstRow(rows));
            for (int r = start; r < end; r++) {
                pageRows.add(new RowView(r + 1, cellsOf(rows.get(r), maxCol)));
            }
        }

        return new SheetView(fileIndex, fileName, sheet, sheetName, headers, columnMap, type, resolvedBy,
                total, currentPage, size, end < rows.size(), pageRows);
    }

    // ==================== 内部 ====================

    private static String sheetNameOf(MultipartFile file, int sheetIndex, String fallback) {
        try {
            for (ExcelSheetRef ref : ExcelSheetReader.listSheets(file)) {
                if (ref.index() == sheetIndex) {
                    return ref.name();
                }
            }
        } catch (Exception e) {
            log.debug("可视化预览：读取 Sheet 目录失败，退回序号 {}", sheetIndex);
        }
        return fallback;
    }

    /** 一行按固定列数补齐（Excel 里的空洞列补空串，前端才能对齐网格）。 */
    private static List<String> cellsOf(Map<Integer, String> row, int columnCount) {
        List<String> cells = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            String v = row == null ? null : row.get(c);
            cells.add(v == null ? "" : v);
        }
        return cells;
    }

    private static int columnCount(List<String> headers, Map<Integer, String> firstDataRow) {
        if (!headers.isEmpty()) {
            return headers.size();
        }
        int max = -1;
        if (firstDataRow != null) {
            for (Integer c : firstDataRow.keySet()) {
                if (c != null && c > max) {
                    max = c;
                }
            }
        }
        return max + 1;
    }

    private static Map<Integer, String> firstRow(List<Map<Integer, String>> rows) {
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
