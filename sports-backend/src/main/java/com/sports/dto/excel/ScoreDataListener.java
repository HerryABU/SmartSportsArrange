package com.sports.dto.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.sports.entity.*;
import com.sports.repository.*;
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
                athlete = athleteRepository.findByNumber(model.getAthleteNumber().trim())
                        .orElseThrow(() -> new RuntimeException("号码簿 '" + model.getAthleteNumber() + "' 不存在"));
            } else if (model.getAthleteName() != null && !model.getAthleteName().isBlank()) {
                List<Athlete> byName = athleteRepository.findByName(model.getAthleteName().trim());
                if (byName.size() == 1) {
                    athlete = byName.get(0);
                } else if (byName.isEmpty()) {
                    throw new RuntimeException("运动员 '" + model.getAthleteName() + "' 不存在");
                } else {
                    throw new RuntimeException("运动员 '" + model.getAthleteName() + "' 存在多个重名，请使用号码簿");
                }
            }

            if (event == null || athlete == null) {
                throw new RuntimeException("项目或运动员信息不完整");
            }

            // B02/U02：一致性校验式去重——已存在且成绩一致则跳过（幂等，可作一致性校验），
            // 成绩不一致则明确报冲突，便于审计发现差异。
            Optional<Result> existing = resultRepository
                    .findByEventIdAndAthleteId(event.getId(), athlete.getId());
            if (existing.isPresent()) {
                Result ex = existing.get();
                String exRaw = ex.getRawTime() != null ? ex.getRawTime().trim() : "";
                String inRaw = model.getRawTime() != null ? model.getRawTime().trim() : "";
                if (exRaw.equals(inRaw)) {
                    successCount++;
                    log.info("成绩已存在且一致，跳过（一致性校验幂等）: {} / {}", event.getCode(), athlete.getNumber());
                    return;
                }
                throw new RuntimeException("成绩冲突: 运动员 '" + athlete.getName() + "' 在项目 '"
                        + event.getName() + "' 已有成绩 " + exRaw + "，导入值 " + inRaw);
            }

            // 解析成绩
            Double timeSeconds = parseTimeToSeconds(model.getRawTime());

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
                    .status("valid")
                    .remark(model.getRemark())
                    .enteredAt(java.time.LocalDateTime.now())
                    .createdAt(java.time.LocalDateTime.now())
                    .updatedAt(java.time.LocalDateTime.now())
                    .build();

            if (model.getWindSpeed() != null && !model.getWindSpeed().isBlank()) {
                try {
                    result.setWindSpeed(Double.parseDouble(model.getWindSpeed().trim()));
                } catch (NumberFormatException ignored) {}
            }

            resultRepository.save(result);
            successCount++;

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
        log.info("成绩导入完成: 成功 {} 条, 失败 {} 条, 跳过(说明/汇总行) {} 条",
                successCount, errorCount, skipped.size());
    }

    public int getSuccessCount() { return successCount; }
    public int getErrorCount() { return errorCount; }
    public List<Map<String, Object>> getErrors() { return errors; }
    public List<Map<String, Object>> getSkipped() { return skipped; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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
}
