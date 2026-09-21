package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.athlete.Athlete;
import com.sports.entity.event.Event;
import com.sports.entity.result.Result;
import com.sports.repository.arrange.ArrangementRepository;
import com.sports.repository.athlete.AthleteRepository;
import com.sports.repository.event.EventRepository;
import com.sports.repository.result.ResultRepository;
import com.sports.service.result.ResultService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 成绩 Excel 导入监听器
 */
@Slf4j
public class ScoreDataListener implements ReadListener<ScoreExcelModel> {

    private final ResultRepository resultRepository;
    private final EventRepository eventRepository;
    private final AthleteRepository athleteRepository;
    private final ArrangementRepository arrangementRepository;

    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final List<Map<String, Object>> skipped = new ArrayList<>();
    private int successCount = 0;
    private int errorCount = 0;

    // L3 修复：批量落库 + 导入内幂等/冲突判定
    private final List<Result> batch = new ArrayList<>();
    private final Map<String, String> seenRawByKey = new HashMap<>();
    private static final int BATCH_SIZE = 500;

    public ScoreDataListener(ResultRepository resultRepository, EventRepository eventRepository,
                             AthleteRepository athleteRepository, ArrangementRepository arrangementRepository) {
        this.resultRepository = resultRepository;
        this.eventRepository = eventRepository;
        this.athleteRepository = athleteRepository;
        this.arrangementRepository = arrangementRepository;
    }

    @Override
    public void invoke(ScoreExcelModel model, AnalysisContext context) {
        int rowNum = context.readRowHolder().getRowIndex() + 1;
        String sheetName = context.readSheetHolder() != null
                ? context.readSheetHolder().getSheetName() : null;

        // B02/U13 修复：成绩导入走 doReadAll() 读取全部 Sheet，而模板与导出表都会带一个
        // 「填写说明」Sheet。说明行的第 0 列是字段名（如「项目编码」「运动员号码」），
        // 会被当作项目编码去查库并抛出「项目编码 '项目编码' 不存在」——
        // 于是「导出的模板/成绩表无法原样回导」，用户拿到的模板本身是坏的。
        // 说明/辅助 Sheet 直接整表跳过（不产生任何 error）。
        if (isAuxiliarySheet(sheetName)) {
            return;
        }

        // 空行跳过
        boolean blank = isBlank(model.getEventCode()) && isBlank(model.getAthleteNumber())
                && isBlank(model.getAthleteName());
        if (blank) {
            return;
        }

        // 成绩行必须能定位到运动员（号码布或姓名）；否则视为说明/汇总行，计入 skipped 而非 error，
        // 既不污染错误清单，也不静默丢弃。
        if (isBlank(model.getAthleteNumber()) && isBlank(model.getAthleteName())) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("row", rowNum);
            skip.put("sheet", sheetName);
            skip.put("value", model.getEventCode());
            skip.put("reason", "无运动员标识（说明行或汇总行）");
            skipped.add(skip);
            return;
        }

        try {
            // 查找项目
            Event event = null;
            if (model.getEventCode() != null && !model.getEventCode().isBlank()) {
                event = eventRepository.findByCode(model.getEventCode().trim())
                        .orElseThrow(() -> new RuntimeException("项目编码 '" + model.getEventCode() + "' 不存在"));
            }

            // 查找运动员
            Athlete athlete = null;
            if (model.getAthleteNumber() != null && !model.getAthleteNumber().isBlank()) {
                // B02/U02：按「号码布 或 学号」匹配——导入模板「填写说明」明确写了
                // 「运动员号码：填号码布编号；也可填学号（系统按号码/学号匹配运动员）」，
                // 但旧实现只调 findByNumber，学号分支从未生效。
                // 而号码布是「系统按规则生成」的，未生成时 number 全空、导出列会回填学号，
                // 于是导出的成绩表回导时报「号码簿 '102002' 不存在」——文档与实现对不上。
                String ref = model.getAthleteNumber().trim();
                athlete = athleteRepository.findByNumber(ref)
                        .or(() -> athleteRepository.findByStudentId(ref))
                        .orElseThrow(() -> new RuntimeException(
                                "号码布/学号 '" + ref + "' 不存在（该列可填号码布编号或学号）"));
            } else if (model.getAthleteName() != null && !model.getAthleteName().isBlank()) {
                List<Athlete> byName = athleteRepository.findByName(model.getAthleteName().trim());
                if (byName.size() == 1) {
                    athlete = byName.get(0);
                } else if (byName.isEmpty()) {
                    throw new RuntimeException("运动员 '" + model.getAthleteName() + "' 不存在");
                } else {
                    throw new RuntimeException("运动员 '" + model.getAthleteName() + "' 存在 " + byName.size()
                            + " 个重名，请改用号码布编号或学号填写「运动员号码」列");
                }
            }

            if (event == null || athlete == null) {
                throw new RuntimeException("项目或运动员信息不完整");
            }

            String key = event.getId() + "_" + athlete.getId();
            String inRaw = model.getRawTime() != null ? model.getRawTime().trim() : "";

            // L3 修复：批量导入性能 + 导入内幂等/冲突判定。原实现逐行 save 且依赖「DB 实时落库」做去重；
            // 现用内存 seenRawByKey 维护本次导入已处理的 (项目,运动员)→成绩 映射，既支持批量 saveAll，
            // 又能在文件内做幂等/冲突判定（不依赖未刷盘的批次）。跨导入幂等仍由 DB 查询兜底。
            if (seenRawByKey.containsKey(key)) {
                String exRaw = seenRawByKey.get(key);
                if (exRaw.equals(inRaw)) {
                    successCount++;
                    log.info("成绩已存在且一致，跳过（一致性校验幂等）: {} / {}", event.getCode(), athlete.getNumber());
                    return;
                }
                throw new RuntimeException("成绩冲突: 运动员 '" + athlete.getName() + "' 在项目 '"
                        + event.getName() + "' 已有成绩 " + exRaw + "，导入值 " + inRaw);
            }

            // B02/U02：一致性校验式去重——已存在且成绩一致则跳过（幂等，可作一致性校验），
            // 成绩不一致则明确报冲突，便于审计发现差异。
            Optional<Result> existing = resultRepository
                    .findByEventIdAndAthleteId(event.getId(), athlete.getId());
            if (existing.isPresent()) {
                Result ex = existing.get();
                String exRaw = ex.getRawTime() != null ? ex.getRawTime().trim() : "";
                if (exRaw.equals(inRaw)) {
                    successCount++;
                    log.info("成绩已存在且一致，跳过（一致性校验幂等）: {} / {}", event.getCode(), athlete.getNumber());
                    return;
                }
                throw new RuntimeException("成绩冲突: 运动员 '" + athlete.getName() + "' 在项目 '"
                        + event.getName() + "' 已有成绩 " + exRaw + "，导入值 " + inRaw);
            }

            // R-2 严密性：DNF/DNS/DSQ 等非完赛标记——模板填写说明承诺支持，但此前被当成畸形时间
            // 静默转 null 仍以 valid 落库，导致弃赛者被算作「有效成绩」且名次排到冠军前。
            // 现识别为独立状态（dnf/dns/dsq），自动退出计分（findAllValid 只取 valid）并排在项目末尾。
            String nonFinishStatus = ResultService.normalizeNonFinishStatus(model.getRawTime());
            // R-3 严密性：非完赛标记已归一为 dnf/dns/dsq（timeSeconds=null）；
            // 否则走 resolveTimeSeconds——空白时间留空待补录，非空白但无法解析或越界一律抛异常，
            // 由外层统一计入 errors，杜绝「畸形时间静默以 valid 落库」的旧问题。
            Double timeSeconds = nonFinishStatus != null ? null : resolveTimeSeconds(model.getRawTime());

            // 查找编排信息
            Integer heat = model.getHeat();
            Integer lane = model.getLane();
            if (heat == null || lane == null) {
                Optional<Arrangement> arr = arrangementRepository
                        .findByEventIdAndAthleteId(event.getId(), athlete.getId());
                if (arr.isPresent()) {
                    heat = arr.get().getHeat();
                    lane = arr.get().getLane();
                }
            }

            Result result = Result.builder()
                    .event(event)
                    .athlete(athlete)
                    .heat(heat)
                    .lane(lane)
                    .rawTime(model.getRawTime())
                    .timeSeconds(timeSeconds)
                    .status(nonFinishStatus != null ? nonFinishStatus : "valid")
                    .remark(model.getRemark())
                    .enteredAt(java.time.LocalDateTime.now())
                    .createdAt(java.time.LocalDateTime.now())
                    .updatedAt(java.time.LocalDateTime.now())
                    .build();

            // 备注含破纪录标记（破纪录/纪录/记录/record，不区分大小写）→ 标记为破纪录成绩，
            // 使「破纪录」可由成绩导入 备注 列直接带入，records 榜与团体加分据此计算。
            if (isRecordRemark(model.getRemark())) {
                result.setIsRecord(true);
            }

            if (model.getWindSpeed() != null && !model.getWindSpeed().isBlank()) {
                try {
                    result.setWindSpeed(Double.parseDouble(model.getWindSpeed().trim()));
                } catch (NumberFormatException ignored) {}
            }

            // L3 修复：批量累积，达到阈值后一次性 saveAll，避免逐条 save 的 N 次写库开销
            batch.add(result);
            seenRawByKey.put(key, inRaw);
            successCount++;
            if (batch.size() >= BATCH_SIZE) {
                flush();
            }

        } catch (Exception e) {
            errorCount++;
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("row", rowNum);
            error.put("message", e.getMessage());
            errors.add(error);
            log.warn("行 {} 导入成绩失败: {}", rowNum, e.getMessage());
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush();
        log.info("成绩导入完成: 成功 {} 条, 失败 {} 条, 跳过(说明/汇总行) {} 条",
                successCount, errorCount, skipped.size());
    }

    /** L3 修复：将累积的 Result 批次一次性落库（避免逐条 save 的 N 次写库开销） */
    private void flush() {
        if (!batch.isEmpty()) {
            resultRepository.saveAll(batch);
            batch.clear();
        }
    }

    public int getSuccessCount() { return successCount; }
    public int getErrorCount() { return errorCount; }
    public List<Map<String, Object>> getErrors() { return errors; }
    public List<Map<String, Object>> getSkipped() { return skipped; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** 备注是否为破纪录标记（破纪录/破记录/新纪录/新记录/纪录/记录/record，不区分大小写） */
    private static boolean isRecordRemark(String remark) {
        if (remark == null || remark.isBlank()) return false;
        String r = remark.trim().toLowerCase();
        return r.contains("破纪录") || r.contains("破记录")
                || r.contains("新纪录") || r.contains("新记录")
                || r.contains("纪录") || r.contains("记录")
                || r.contains("record");
    }

    /**
     * 辅助 Sheet 判定：模板/导出表里的「填写说明」Sheet 不是数据。
     * 只按明确的辅助语义匹配，避免误伤以项目编码命名的数据 Sheet（如 M100 / LJM）。
     */
    private static boolean isAuxiliarySheet(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        return n.contains("说明") || n.contains("模板")
                || n.equalsIgnoreCase("README") || n.equalsIgnoreCase("Notes");
    }

    /** 时间解析：支持 12.34 / 2:35.67 / 2:35 等格式 */
    private Double parseTimeToSeconds(String time) {
        if (time == null || time.isBlank()) return null;
        time = time.trim();
        try {
            if (time.contains(":")) {
                String[] parts = time.split(":");
                int minutes = Integer.parseInt(parts[0]);
                double seconds = Double.parseDouble(parts[1]);
                return minutes * 60.0 + seconds;
            }
            return Double.parseDouble(time);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * R-3 严密性：解析成绩时间并做边界校验。
     * 返回 null 仅当原始时间为空——属合法情形（留待后续补录，仍以 valid 落库）。
     * 非空白但无法解析为数值/时间（如 "12.5.3"），或超出合理区间（<=0 或 >100000 秒），
     * 一律抛 RuntimeException，由 invoke 外层的 try/catch 统一计入 errors，
     * 而非像旧实现那样被 parseTimeToSeconds 静默转 null 后以 valid 落库。
     */
    private Double resolveTimeSeconds(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return null;
        }
        Double secs = parseTimeToSeconds(rawTime);
        if (secs == null) {
            throw new RuntimeException("成绩格式非法: '" + rawTime.trim()
                    + "' 既不是合法时间（如 12.34 / 2:35.67），也不是 DNF/DNS/DSQ 标记");
        }
        if (secs <= 0.0 || secs > 100000.0) {
            throw new RuntimeException("成绩超出合理范围: " + secs + " 秒（须 >0 且 <=100000）");
        }
        return secs;
    }
}
