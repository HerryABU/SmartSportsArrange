package com.sports.service.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.read.metadata.ReadSheet;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作簿读取工具：列出全部 Sheet（含<b>真实表名</b>）与读取指定 Sheet 的原始行。
 *
 * <p>与「一次性读单表」的导入相比，这里必须两件事分开做：</p>
 * <ol>
 *   <li><b>先取目录</b>：{@code ExcelReader.excelExecutor().sheetList()} 拿到所有 Sheet 的序号与名称
 *       （EasyExcel 4.x 的读取目录 API；{@code com.alibaba.excel.metadata.Sheet} 在 4.x 已移除，
 *       不要再按 3.x 的写法去用）。</li>
 *   <li><b>再按需读行</b>：对每个 Sheet 单独读一遍，避免一次性把整本工作簿灌进内存。</li>
 * </ol>
 *
 * <p>读取统一用 {@code headRowNumber(0)}：把表头也当作数据行取出，由调用方决定第 0 行是表头还是数据——
 * 这样「表头在不在」不会把列号整体错位一列。</p>
 */
public final class ExcelSheetReader {

    /** 保护：单个 Sheet 最多读这么多行，避免超大文件把内存吃光（超出部分会被截断并在报告里标注）。 */
    public static final int MAX_ROWS_PER_SHEET = 20_000;

    private ExcelSheetReader() {
    }

    /** 列出工作簿里的全部 Sheet（序号 + 真实名称）。读不出目录时返回空列表（由调用方如实报错）。 */
    public static List<ExcelSheetRef> listSheets(MultipartFile file) {
        List<ExcelSheetRef> refs = new ArrayList<>();
        try (InputStream in = file.getInputStream();
             ExcelReader reader = EasyExcel.read(in).build()) {
            List<ReadSheet> sheets = reader.excelExecutor().sheetList();
            if (sheets == null) {
                return refs;
            }
            for (ReadSheet s : sheets) {
                Integer no = s.getSheetNo();
                String name = s.getSheetName();
                int idx = no != null ? no : refs.size();
                refs.add(new ExcelSheetRef(idx, name != null && !name.isBlank() ? name : ("Sheet" + (idx + 1))));
            }
        } catch (IOException e) {
            throw new RuntimeException("读取工作簿失败: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("解析工作簿失败（请确认是 .xlsx/.xls）: " + e.getMessage());
        }
        return refs;
    }

    /**
     * 读取指定 Sheet 的原始行（第 0 行通常为表头）。
     *
     * @return 每行是「列号 → 文本值」；空单元格不出现在 Map 里
     */
    public static List<Map<Integer, String>> readRows(MultipartFile file, int sheetIndex) {
        try (InputStream in = file.getInputStream()) {
            List<Map<Integer, String>> rows =
                    EasyExcel.read(in).sheet(sheetIndex).headRowNumber(0).doReadSync();
            return rows == null ? List.of() : rows;
        } catch (IOException e) {
            throw new RuntimeException("读取工作表失败: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("读取第 " + (sheetIndex + 1) + " 个工作表失败: " + e.getMessage());
        }
    }

    /**
     * 取表头列表（第 0 行），按「最大列号」补齐空洞，保证列号与数组下标一致。
     */
    public static List<String> headersOf(List<Map<Integer, String>> rows) {
        List<String> headers = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return headers;
        }
        Map<Integer, String> headerRow = rows.get(0);
        int maxCol = -1;
        for (Integer c : headerRow.keySet()) {
            if (c != null && c > maxCol) maxCol = c;
        }
        for (int c = 0; c <= maxCol; c++) {
            String v = headerRow.get(c);
            headers.add(v == null ? "" : v);
        }
        return headers;
    }

    /**
     * 把一行原始数据按「列号 → 字段名」映射成处理器要的 {@code field → 值}。
     *
     * @param columnMap 列号(字符串) → 字段名
     */
    public static Map<String, String> rowToValues(Map<Integer, String> row, Map<String, String> columnMap) {
        Map<String, String> values = new LinkedHashMap<>();
        if (row == null || columnMap == null) {
            return values;
        }
        for (Map.Entry<String, String> e : columnMap.entrySet()) {
            Integer col;
            try {
                col = Integer.valueOf(e.getKey().trim());
            } catch (NumberFormatException ignore) {
                continue;
            }
            String field = e.getValue();
            if (field == null || field.isBlank()) {
                continue;
            }
            String val = row.get(col);
            if (val != null && !val.isBlank()) {
                values.put(field.trim(), val.trim());
            }
        }
        return values;
    }

    /** 整行是否全空（用于跳过 Excel 里的空行，避免把空行算成导入失败）。 */
    public static boolean isBlankRow(Map<Integer, String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String v : row.values()) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
