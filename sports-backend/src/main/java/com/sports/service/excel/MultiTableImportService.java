package com.sports.service.excel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sports.service.excel.ExcelColumnMapping;
import com.sports.service.excel.ExcelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多表导入（管理员批量导入）：<b>一个工作簿里的多个 Sheet</b> 或 <b>多个工作簿文件</b> 一次导入。
 *
 * <p>典型场景：把「年级 / 班级 / 全名单 / 报名表 / 运动项目表」拆成同一个 Excel 的多个 Sheet，
 * 一次上传全部导入；或把各年级的名单拆成多个文件一次选中。</p>
 *
 * <h3>三条硬规矩</h3>
 * <ol>
 *   <li><b>按依赖顺序处理，而不是按 Sheet 物理顺序</b>：年级 → 班级 → 运动员 → 项目 → 报名 → 成绩。
 *       制表人把「报名表」排在「名单表」前面是常事，按物理顺序处理会让整张报名表全行失败。</li>
 *   <li><b>认不出类型的 Sheet 一律跳过并如实报告</b>，绝不默认按「运动员表」硬导——
 *       那会把班级表写成运动员，且全程不报错。</li>
 *   <li><b>逐表、逐行如实计数</b>（成功 / 跳过(已存在) / 失败 + 行号原因）。
 *       多表导入常被反复重跑，把「已存在」单独归类，才看得出这次到底改动了什么。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiTableImportService {

    private static final ObjectMapper PLAN_MAPPER = new ObjectMapper();

    private final ExcelService excelService;

    // ==================== 探测（不落库） ====================

    /**
     * 解析文件清单：每个文件有哪些 Sheet、表头是什么、被判定成什么类型、自动列映射结果、样例行。
     *
     * <p>供前端「先看清再导」：管理员可在导入前逐表确认/改写类型。</p>
     */
    public Map<String, Object> preview(List<MultipartFile> files, Map<String, Object> plan) {
        boolean hasHeader = hasHeader(plan);
        List<SheetJob> jobs = buildJobs(files, plan, hasHeader);

        List<Map<String, Object>> fileReports = new ArrayList<>();
        int fi = -1;
        Map<String, Object> current = null;
        for (SheetJob job : jobs) {
            if (job.fileIndex() != fi) {
                fi = job.fileIndex();
                current = new LinkedHashMap<>();
                current.put("fileIndex", fi);
                current.put("fileName", job.fileName());
                current.put("sheets", new ArrayList<Map<String, Object>>());
                fileReports.add(current);
            }
            String type = SheetTypeResolver.resolve(job.sheetName(), job.headers(), job.overrideType());
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
            s.put("sampleRows", sampleRows(job, columnMap, hasHeader));
            s.put("importable", type != null && !columnMap.isEmpty());
            s.put("reason", skipReason(type, columnMap));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) current.get("sheets");
            list.add(s);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", fileReports);
        out.put("hasHeader", hasHeader);
        out.put("allTypes", SheetTypeResolver.allTypes());
        out.put("typeLabels", SheetTypeResolver.typeLabels());
        out.put("fieldLabels", fieldLabels());
        return out;
    }

    // ==================== 导入 ====================

    /**
     * 执行多表导入。
     *
     * @param plan 可选的按表覆盖：{@code {hasHeader:true, sheets:[{file|fileIndex, sheet|sheetIndex, type, columnMap}]}}
     */
    @Transactional
    public Map<String, Object> importAll(List<MultipartFile> files, Map<String, Object> plan) {
        boolean hasHeader = hasHeader(plan);
        List<SheetJob> jobs = buildJobs(files, plan, hasHeader);

        // 按依赖顺序执行，但报告按「文件 × Sheet 原始顺序」呈现（管理员对照的是自己那份表）
        List<SheetJob> ordered = new ArrayList<>(jobs);
        ordered.sort(Comparator
                .comparingInt((SheetJob j) -> SheetTypeResolver.priority(
                        SheetTypeResolver.resolve(j.sheetName(), j.headers(), j.overrideType())))
                .thenComparingInt(SheetJob::fileIndex)
                .thenComparingInt(SheetJob::sheetIndex));

        Map<String, Map<String, Object>> resultByKey = new LinkedHashMap<>();
        int totalSuccess = 0, totalSkipped = 0, totalFailed = 0, skippedSheets = 0;
        List<String> notes = new ArrayList<>();

        for (SheetJob job : ordered) {
            String key = job.fileIndex() + "::" + job.sheetIndex();
            String type = SheetTypeResolver.resolve(job.sheetName(), job.headers(), job.overrideType());
            Map<String, String> columnMap = effectiveColumnMap(job, type);

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sheetIndex", job.sheetIndex());
            s.put("sheetName", job.sheetName());
            s.put("type", type);
            s.put("resolvedBy", resolvedBy(job));
            s.put("headers", job.headers());
            s.put("columnMap", columnMap);
            s.put("mappedFields", describeMapped(type, job.headers(), columnMap));

            String reason = skipReason(type, columnMap);
            if (reason != null) {
                s.put("skipped", true);
                s.put("reason", reason);
                s.put("totalRows", Math.max(0, job.rows().size() - (hasHeader ? 1 : 0)));
                s.put("success", 0);
                s.put("failed", 0);
                s.put("rowSkipped", 0);
                s.put("errors", List.of());
                s.put("skipNotes", List.of());
                skippedSheets++;
                notes.add("Sheet「" + job.sheetName() + "」已跳过：" + reason);
                resultByKey.put(key, s);
                continue;
            }

            List<Map<String, String>> values = new ArrayList<>();
            int blank = 0;
            int from = hasHeader ? 1 : 0;
            List<Map<Integer, String>> rows = job.rows();
            for (int r = from; r < rows.size(); r++) {
                Map<Integer, String> row = rows.get(r);
                if (ExcelSheetReader.isBlankRow(row)) {
                    blank++;
                    continue;
                }
                values.add(ExcelSheetReader.rowToValues(row, columnMap));
            }

            Map<String, Object> rowResult;
            try {
                rowResult = excelService.importRows(type, values);
            } catch (Exception e) {
                // 防御：单表意外异常不应把整批导入拖垮（也避免外层事务被标记回滚）
                log.warn("多表导入：Sheet「{}」导入异常: {}", job.sheetName(), e.toString());
                s.put("skipped", false);
                s.put("totalRows", values.size());
                s.put("success", 0);
                s.put("rowSkipped", 0);
                s.put("failed", values.size());
                s.put("errors", List.of(Map.of("row", 0, "message", "整表导入异常: " + e.getMessage())));
                s.put("skipNotes", List.of());
                totalFailed += values.size();
                resultByKey.put(key, s);
                continue;
            }

            int success = ((Number) rowResult.getOrDefault("success", 0)).intValue();
            int rowSkipped = ((Number) rowResult.getOrDefault("skipped", 0)).intValue();
            int failed = ((Number) rowResult.getOrDefault("failed", 0)).intValue();

            s.put("skipped", false);
            s.put("reason", null);
            s.put("totalRows", values.size() + blank);
            s.put("success", success);
            s.put("rowSkipped", rowSkipped);
            s.put("failed", failed);
            s.put("errors", rowResult.get("errors"));
            s.put("skipNotes", rowResult.get("skipNotes"));
            s.put("blankRows", blank);
            if (rows.size() >= ExcelSheetReader.MAX_ROWS_PER_SHEET) {
                s.put("truncated", true);
                notes.add("Sheet「" + job.sheetName() + "」行数达到上限 " + ExcelSheetReader.MAX_ROWS_PER_SHEET + "，超出部分未导入");
            }
            totalSuccess += success;
            totalSkipped += rowSkipped;
            totalFailed += failed;
            resultByKey.put(key, s);
        }

        // 按原始顺序组装报告
        List<Map<String, Object>> fileReports = new ArrayList<>();
        int curFile = -1;
        for (SheetJob job : jobs) {
            if (job.fileIndex() != curFile) {
                curFile = job.fileIndex();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fileIndex", curFile);
                f.put("fileName", job.fileName());
                f.put("sheets", new ArrayList<Map<String, Object>>());
                fileReports.add(f);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) fileReports.get(fileReports.size() - 1).get("sheets");
            list.add(resultByKey.get(job.fileIndex() + "::" + job.sheetIndex()));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("files", fileReports.size());
        summary.put("sheets", jobs.size());
        summary.put("imported", totalSuccess);
        summary.put("rowSkipped", totalSkipped);
        summary.put("failed", totalFailed);
        summary.put("skippedSheets", skippedSheets);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", fileReports);
        out.put("summary", summary);
        out.put("notes", notes);
        log.info("多表导入完成: 文件 {}，Sheet {}，成功 {} 行，跳过(已存在) {} 行，失败 {} 行，跳过表 {}",
                fileReports.size(), jobs.size(), totalSuccess, totalSkipped, totalFailed, skippedSheets);
        return out;
    }

    // ==================== 内部 ====================

    /** 一个待处理的 Sheet（含已读出的行，避免反复读文件）。 */
    private record SheetJob(int fileIndex, String fileName, int sheetIndex, String sheetName,
                            List<String> headers, List<Map<Integer, String>> rows,
                            String overrideType, Map<String, String> overrideColumnMap) {
    }

    private List<SheetJob> buildJobs(List<MultipartFile> files, Map<String, Object> plan, boolean hasHeader) {
        List<Map<String, Object>> planSheets = planSheets(plan);
        List<SheetJob> jobs = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("未选择任何文件");
        }
        for (int fi = 0; fi < files.size(); fi++) {
            MultipartFile file = files.get(fi);
            if (file == null || file.isEmpty()) {
                continue;
            }
            String fileName = file.getOriginalFilename() == null ? ("文件" + (fi + 1)) : file.getOriginalFilename();
            List<ExcelSheetRef> refs = ExcelSheetReader.listSheets(file);
            if (refs.isEmpty()) {
                // 目录读不出来（例如纯 CSV）：退化成「第 0 个表」，仍如实走后续判定
                refs = List.of(new ExcelSheetRef(0, fileName));
            }
            for (ExcelSheetRef ref : refs) {
                List<Map<Integer, String>> rows;
                try {
                    rows = ExcelSheetReader.readRows(file, ref.index());
                } catch (Exception e) {
                    log.warn("多表导入：文件「{}」Sheet「{}」读取失败: {}", fileName, ref.name(), e.toString());
                    rows = List.of();
                }
                List<String> headers = hasHeader ? ExcelSheetReader.headersOf(rows) : List.of();
                PlanEntry pe = findPlanEntry(planSheets, fi, fileName, ref);
                jobs.add(new SheetJob(fi, fileName, ref.index(), ref.name(), headers, rows,
                        pe == null ? null : pe.type(), pe == null ? Map.of() : pe.columnMap()));
            }
        }
        return jobs;
    }

    private Map<String, String> effectiveColumnMap(SheetJob job, String type) {
        if (job.overrideColumnMap() != null && !job.overrideColumnMap().isEmpty()) {
            return job.overrideColumnMap();
        }
        return SheetTypeResolver.autoColumnMap(type, job.headers());
    }

    /** 该表由什么判定出来：人工指定 / 表头指纹 / 表名关键词（认不出则 null）。 */
    private String resolvedBy(SheetJob job) {
        if (job.overrideType() != null && !job.overrideType().isBlank()) {
            return "override";
        }
        if (SheetTypeResolver.inferByHeader(job.headers()) != null) {
            return "header";
        }
        return ExcelService.detectTypeOrNull(job.sheetName()) != null ? "name" : null;
    }

    private String skipReason(String type, Map<String, String> columnMap) {
        if (type == null) {
            return "无法识别该表类型（Sheet 名与表头都不足以判定），可在列表里手动指定类型";
        }
        if (columnMap.isEmpty()) {
            return "表头没有可识别的列（与「" + type + "」的字段不匹配），可在列表里手动指定列映射";
        }
        return null;
    }

    private List<Map<String, String>> describeMapped(String type, List<String> headers, Map<String, String> columnMap) {
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

    private List<String> unmappedHeaders(List<String> headers, Map<String, String> columnMap) {
        List<String> unmapped = new ArrayList<>();
        if (headers == null) {
            return unmapped;
        }
        java.util.Set<String> mappedCols = columnMap == null ? java.util.Set.of() : columnMap.keySet();
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

    private List<List<String>> sampleRows(SheetJob job, Map<String, String> columnMap, boolean hasHeader) {
        List<List<String>> samples = new ArrayList<>();
        int from = hasHeader ? 1 : 0;
        for (int r = from; r < job.rows().size() && samples.size() < 3; r++) {
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

    private Map<String, Map<String, String>> fieldLabels() {
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        for (String type : SheetTypeResolver.allTypes()) {
            labels.put(type, ExcelColumnMapping.fieldsOf(type));
        }
        return labels;
    }

    private boolean hasHeader(Map<String, Object> plan) {
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> planSheets(Map<String, Object> plan) {
        if (plan == null) {
            return List.of();
        }
        Object raw = plan.get("sheets");
        if (raw instanceof String s) {
            try {
                return PLAN_MAPPER.readValue(s, new TypeReference<List<Map<String, Object>>>() {
                });
            } catch (Exception e) {
                return List.of();
            }
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    /** 在计划里按「文件名 + Sheet 名」优先、其次「文件序号 + Sheet 序号」匹配一条覆盖项。 */
    private PlanEntry findPlanEntry(List<Map<String, Object>> planSheets, int fileIndex, String fileName, ExcelSheetRef ref) {
        for (Map<String, Object> entry : planSheets) {
            String entryFile = str(entry.get("file"));
            Integer entryFileIdx = intOf(entry.get("fileIndex"));
            boolean fileMatch = (entryFile != null && entryFile.equals(fileName))
                    || (entryFileIdx != null && entryFileIdx == fileIndex);
            if (!fileMatch) {
                continue;
            }
            String entrySheet = str(entry.get("sheet"));
            Integer entrySheetIdx = intOf(entry.get("sheetIndex"));
            boolean sheetMatch = (entrySheet != null && entrySheet.equals(ref.name()))
                    || (entrySheetIdx != null && entrySheetIdx == ref.index());
            if (!sheetMatch) {
                continue;
            }
            return new PlanEntry(str(entry.get("type")), columnMapOf(entry.get("columnMap")));
        }
        return null;
    }

    private record PlanEntry(String type, Map<String, String> columnMap) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> columnMapOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return out;
        }
        if (raw instanceof String s) {
            try {
                return PLAN_MAPPER.readValue(s, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception e) {
                return Map.of();
            }
        }
        return Map.of();
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
