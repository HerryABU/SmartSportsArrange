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

            // 检查重复
            if (resultRepository.existsByEventIdAndAthleteId(event.getId(), athlete.getId())) {
                throw new RuntimeException("运动员 '" + athlete.getName() + "' 在项目 '" + event.getName() + "' 中已有成绩");
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
        log.info("成绩导入完成: 成功 {} 条, 失败 {} 条", successCount, errorCount);
    }

    public int getSuccessCount() { return successCount; }
    public int getErrorCount() { return errorCount; }
    public List<Map<String, Object>> getErrors() { return errors; }

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
