package com.sports.service.excel;

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
 *
 * <h3>本类只管编排，不管细节</h3>
 * <ul>
 *   <li>{@link ImportPlanParser}：前端那份「导入计划」（sheets JSON）的解析与匹配。</li>
 *   <li>{@link SheetPreviewBuilder}：单张表的预览报告（类型判定 / 列映射 / 样例行 / 可否导入）。</li>
 *   <li>{@link ExcelSheetReader}：EasyExcel 读目录与原始行。</li>
 *   <li>{@link ImportBatchGuard}：批次内去重 / 冲突 / 名单一致性。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiTableImportService {

    private final ExcelService excelService;

    // ==================== 探测（不落库） ====================

    /**
     * 解析文件清单：每个文件有哪些 Sheet、表头是什么、被判定成什么类型、自动列映射结果、样例行。
     *
     * <p>供前端「先看清再导」：管理员可在导入前逐表确认/改写类型、逐列改写映射，
     * 改完点「重新解析」把计划打回后端（{@code plan}），后端按用户指定重新判定并再出一遍预览。</p>
     */
    public Map<String, Object> preview(List<MultipartFile> files, Map<String, Object> plan) {
        return preview(files, plan, SheetPreviewBuilder.DEFAULT_SAMPLE_SIZE, false);
    }

    /**
     * @param sampleSize 每表样例行数
     * @param withAdvice 是否附带逐列「可选字段」下拉数据（可视化预览面板用）
     */
    public Map<String, Object> preview(List<MultipartFile> files, Map<String, Object> plan,
                                       int sampleSize, boolean withAdvice) {
        boolean hasHeader = ImportPlanParser.hasHeader(plan);
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
            Map<String, Object> s = SheetPreviewBuilder.build(job, hasHeader, sampleSize, withAdvice);
            s.put("include", job.include());
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
     * @param plan 可选的按表覆盖：{@code {hasHeader:true, sheets:[{file|fileIndex, sheet|sheetIndex,
     *             type, columnMap, include}]}}；{@code include=false} 的表整表跳过。
     */
    @Transactional
    public Map<String, Object> importAll(List<MultipartFile> files, Map<String, Object> plan) {
        boolean hasHeader = ImportPlanParser.hasHeader(plan);
        List<SheetJob> jobs = buildJobs(files, plan, hasHeader);

        // 按依赖顺序执行，但报告按「文件 × Sheet 原始顺序」呈现（管理员对照的是自己那份表）
        List<SheetJob> ordered = new ArrayList<>(jobs);
        ordered.sort(Comparator
                .comparingInt((SheetJob j) -> SheetTypeResolver.priority(
                        SheetTypeResolver.resolve(j.sheetName(), j.headers(), j.effectiveType())))
                .thenComparingInt(SheetJob::fileIndex)
                .thenComparingInt(SheetJob::sheetIndex));

        Map<String, Map<String, Object>> resultByKey = new LinkedHashMap<>();
        int totalSuccess = 0, totalSkipped = 0, totalFailed = 0, skippedSheets = 0;
        int totalDup = 0, totalConflict = 0, totalRosterMismatch = 0;
        List<String> notes = new ArrayList<>();
        List<String> inconsistencies = new ArrayList<>();

        // ---- 阶段 1：逐表读行，并先建「名单索引」（供报名一致性校验）----
        record Pending(SheetJob job, String type, Map<String, String> columnMap, String key, String reason,
                       List<Map<String, String>> values, List<Integer> rowNos, int blank) {
        }
        List<Pending> pendings = new ArrayList<>();
        ImportBatchGuard guard = new ImportBatchGuard();
        for (SheetJob job : ordered) {
            String key = job.fileIndex() + "::" + job.sheetIndex();
            String type = SheetTypeResolver.resolve(job.sheetName(), job.headers(), job.effectiveType());
            Map<String, String> columnMap = SheetPreviewBuilder.effectiveColumnMap(job, type);
            String reason = SheetPreviewBuilder.skipReason(type, columnMap);
            if (!job.include()) {
                // 未勾选的表同样按「整表跳过」报告：不置 null 是因为置 null 会让它再走一遍空行处理，
                // 报告上显示成「成功 0 行」，管理员看不出这张表到底是被跳过了还是压根没读到。
                if (reason == null) {
                    reason = "用户指定不导入该表";
                }
            }
            List<Map<String, String>> values = new ArrayList<>();
            List<Integer> rowNos = new ArrayList<>();
            int blank = 0;
            if (reason == null) {
                int from = hasHeader ? 1 : 0;
                List<Map<Integer, String>> rows = job.rows();
                for (int r = from; r < rows.size(); r++) {
                    Map<Integer, String> row = rows.get(r);
                    if (ExcelSheetReader.isBlankRow(row)) {
                        blank++;
                        continue;
                    }
                    values.add(ExcelSheetReader.rowToValues(row, columnMap));
                    rowNos.add(r + 1); // Excel 里的 1-based 行号
                }
                for (Map<String, String> v : values) {
                    guard.indexAthlete(type, v);
                }
            }
            pendings.add(new Pending(job, type, columnMap, key, reason, values, rowNos, blank));
        }

        // ---- 阶段 2：逐表去重 / 冲突 / 名单校验 → 导入 ----
        for (Pending p : pendings) {
            SheetJob job = p.job();
            String type = p.type();

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sheetIndex", job.sheetIndex());
            s.put("sheetName", job.sheetName());
            s.put("type", type);
            s.put("resolvedBy", SheetPreviewBuilder.resolvedBy(job));
            s.put("headers", job.headers());
            s.put("columnMap", p.columnMap());
            s.put("mappedFields", SheetPreviewBuilder.describeMapped(type, job.headers(), p.columnMap()));

            if (p.reason() != null) {
                s.put("skipped", true);
                s.put("include", job.include());
                s.put("reason", job.include() ? p.reason() : "用户指定不导入该表");
                s.put("totalRows", Math.max(0, job.rows().size() - (hasHeader ? 1 : 0)));
                s.put("success", 0);
                s.put("failed", 0);
                s.put("rowSkipped", 0);
                s.put("errors", List.of());
                s.put("skipNotes", List.of());
                s.put("inconsistencies", List.of());
                skippedSheets++;
                notes.add("Sheet「" + job.sheetName() + "」"
                        + (job.include() ? "已跳过：" + p.reason() : "按设置跳过（未勾选）"));
                resultByKey.put(p.key(), s);
                continue;
            }

            // 去重（同实体只导一次）/ 冲突（同键字段不一致）/ 名单不一致（报名与名单不符）
            List<Map<String, String>> kept = new ArrayList<>();
            List<Map<String, Object>> issues = new ArrayList<>();
            int dup = 0, conflict = 0, mismatch = 0;
            for (int i = 0; i < p.values().size(); i++) {
                Map<String, String> v = p.values().get(i);
                int rowNo = p.rowNos().get(i);
                String where = job.fileName() + "[" + job.sheetName() + "] 第 " + rowNo + " 行";
                boolean skipRow = false;
                for (ImportBatchGuard.Issue it : guard.check(type, v, where)) {
                    issues.add(Map.of("row", rowNo, "kind", it.kind().name(), "message", it.detail()));
                    inconsistencies.add(job.sheetName() + " 第 " + rowNo + " 行：" + it.detail());
                    switch (it.kind()) {
                        case DUPLICATE -> {
                            dup++;
                            skipRow = true;
                        }
                        case CONFLICT -> {
                            conflict++;
                            skipRow = true;
                        }
                        case ROSTER_MISMATCH -> mismatch++;
                    }
                }
                if (!skipRow) {
                    kept.add(v);
                }
            }

            Map<String, Object> rowResult;
            try {
                rowResult = excelService.importRows(type, kept);
            } catch (Exception e) {
                // 防御：单表意外异常不应把整批导入拖垮（也避免外层事务被标记回滚）
                log.warn("多表导入：Sheet「{}」导入异常: {}", job.sheetName(), e.toString());
                s.put("skipped", false);
                s.put("include", job.include());
                s.put("totalRows", p.values().size() + p.blank());
                s.put("success", 0);
                s.put("rowSkipped", dup + conflict);
                s.put("failed", kept.size());
                s.put("errors", List.of(Map.of("row", 0, "message", "整表导入异常: " + e.getMessage())));
                s.put("skipNotes", List.of());
                s.put("dupSkipped", dup);
                s.put("conflicts", conflict);
                s.put("rosterMismatches", mismatch);
                s.put("inconsistencies", issues);
                totalFailed += kept.size();
                totalDup += dup;
                totalConflict += conflict;
                totalRosterMismatch += mismatch;
                resultByKey.put(p.key(), s);
                continue;
            }

            int success = ((Number) rowResult.getOrDefault("success", 0)).intValue();
            int rowSkipped = ((Number) rowResult.getOrDefault("skipped", 0)).intValue();
            int failed = ((Number) rowResult.getOrDefault("failed", 0)).intValue();

            s.put("skipped", false);
            s.put("include", job.include());
            s.put("reason", null);
            s.put("totalRows", p.values().size() + p.blank());
            s.put("success", success);
            s.put("rowSkipped", rowSkipped + dup + conflict);
            s.put("failed", failed);
            s.put("errors", rowResult.get("errors"));
            s.put("skipNotes", rowResult.get("skipNotes"));
            s.put("blankRows", p.blank());
            s.put("dupSkipped", dup);
            s.put("conflicts", conflict);
            s.put("rosterMismatches", mismatch);
            s.put("inconsistencies", issues);
            if (job.rows().size() >= ExcelSheetReader.MAX_ROWS_PER_SHEET) {
                s.put("truncated", true);
                notes.add("Sheet「" + job.sheetName() + "」行数达到上限 " + ExcelSheetReader.MAX_ROWS_PER_SHEET + "，超出部分未导入");
            }
            totalSuccess += success;
            totalSkipped += rowSkipped;
            totalFailed += failed;
            totalDup += dup;
            totalConflict += conflict;
            totalRosterMismatch += mismatch;
            resultByKey.put(p.key(), s);
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
        summary.put("duplicateRows", totalDup);
        summary.put("conflictRows", totalConflict);
        summary.put("rosterMismatches", totalRosterMismatch);

        if (totalDup > 0 || totalConflict > 0 || totalRosterMismatch > 0) {
            notes.add("去重 / 一致性检查：重复行 " + totalDup + "、同键冲突 " + totalConflict
                    + "、报名与名单不一致 " + totalRosterMismatch + "（明细见各表「不一致」列）");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", fileReports);
        out.put("summary", summary);
        out.put("notes", notes);
        out.put("inconsistencies", inconsistencies.size() > 200 ? inconsistencies.subList(0, 200) : inconsistencies);
        log.info("多表导入完成: 文件 {}，Sheet {}，成功 {} 行，跳过(已存在 {}/重复 {} ) {} 行，失败 {} 行，跳过表 {}，冲突 {}，名单不一致 {}",
                fileReports.size(), jobs.size(), totalSuccess, totalSkipped - totalDup - totalConflict, totalDup,
                totalSkipped, totalFailed, skippedSheets, totalConflict, totalRosterMismatch);
        return out;
    }

    // ==================== 内部 ====================

    /**
     * 按文件展开成「一张表一条 SheetJob」。
     *
     * <p>每个 Sheet 的原始行只读一次并在整条导入链路里复用 ——
     * {@link MultipartFile} 的输入流读完就没了，重复读要么报流已关闭，要么把整本工作簿反复解析一遍。</p>
     */
    public List<SheetJob> buildJobs(List<MultipartFile> files, Map<String, Object> plan, boolean hasHeader) {
        ImportPlanParser.PlanInfo info = ImportPlanParser.parse(plan);
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
            List<ExcelSheetRef> refs;
            try {
                refs = ExcelSheetReader.listSheets(file);
            } catch (Exception e) {
                log.warn("多表导入：文件「{}」目录读取失败: {}", fileName, e.toString());
                refs = List.of();
            }
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
                ImportPlanParser.PlanEntry pe = ImportPlanParser.match(info.entries(), fi, fileName, ref);
                String detectedType = SheetTypeResolver.resolve(ref.name(), headers, pe == null ? null : pe.type());
                // 说明页（模板自带的「填写说明」）默认不勾：让管理员不必为每份模板挨个取消勾选，
                // 想导别的表照样能导 —— 这里只影响默认勾选状态，不改判定结果。
                boolean included = pe == null || pe.isIncluded();
                if (SheetTypeResolver.isNotice(detectedType)) {
                    included = false;
                }
                jobs.add(new SheetJob(fi, fileName, ref.index(), ref.name(), headers, rows,
                        pe == null ? null : pe.type(),
                        pe == null ? Map.of() : pe.columnMap(),
                        included));
            }
        }
        return jobs;
    }

    private Map<String, Map<String, String>> fieldLabels() {
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        for (String type : SheetTypeResolver.allTypes()) {
            labels.put(type, ExcelColumnMapping.fieldsOf(type));
        }
        return labels;
    }
}
