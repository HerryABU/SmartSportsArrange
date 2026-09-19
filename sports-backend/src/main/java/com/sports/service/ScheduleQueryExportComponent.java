package com.sports.service;

import com.sports.collab.ScheduleCollaborationService;
import com.sports.entity.Event;
import com.sports.entity.EventSchedule;
import com.sports.repository.ArrangementRepository;
import com.sports.repository.EventRepository;
import com.sports.repository.EventScheduleRepository;
import com.sports.schedule.core.*;
import com.alibaba.excel.EasyExcel;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.sports.schedule.support.ScheduleSupport.*;

/**
 * 赛程查询 / 手动保存 / 清空 / 导出 / 结果组装组件（从 {@code ScheduleService} 抽出）：
 * 负责赛程列表读取、手动整表替换保存、清空、Excel 导出与统一结果结构组装。
 *
 * <p>持有赛程 / 项目 / 编排三个 Repository、协作中心与审计服务，全部为 Spring 无关的纯逻辑。
 * 事务边界由 facade 的公开委派方法承接（{@code @Transactional} 只声明在 @Service bean 上才生效）。</p>
 */
@Slf4j
public class ScheduleQueryExportComponent {

    private final EventScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final ArrangementRepository arrangementRepository;
    private final ScheduleCollaborationService collaborationService;
    private final AuditService auditService;

    public ScheduleQueryExportComponent(EventScheduleRepository scheduleRepository,
                                        EventRepository eventRepository,
                                        ArrangementRepository arrangementRepository,
                                        ScheduleCollaborationService collaborationService,
                                        AuditService auditService) {
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.arrangementRepository = arrangementRepository;
        this.collaborationService = collaborationService;
        this.auditService = auditService;
    }

    // ==================== 以下方法由 scripts/refactor_extract_query_export_component.py 从 ScheduleService 迁入 ====================

    public Map<String, Object> list() {
        return buildResult();
    }

    /**
     * 手动保存调整后的赛程（替换全部）。
     *
     * <p>B09/U09 修复：旧实现构建 {@link EventSchedule} 时<b>完全没写 round</b>，
     * 也不读取入参里的 round——手动保存一次（哪怕只是改个开始时间），
     * 整张赛程表的轮次就被清空为 null，导出/秩序册按 {@code RoundLabelUtil.label(null)}
     * 一律显示成「直接决赛」，预赛与决赛的区分彻底丢失。
     * {@link #buildResult()} 也没把 round 输出给前端，所以前端即使原样回传也无力回天。</p>
     *
     * <p>现在按三级取值保证轮次不丢：① 入参显式 round（前端已回传）；
     * ② 保存前既有行的 round（按 项目×年级×开始时刻×场地 匹配，兼容前端只传调整过的行）；
     * ③ 兜底按 {@code event.needHeats} 推断（需预赛→preliminary，否则 final）。</p>
     */
    public Map<String, Object> save(List<Map<String, Object>> items) {
        // ① 先给「旧行」建索引：手动保存会整表替换，替换前必须先把轮次捞出来
        Map<String, String> oldRoundByKey = new HashMap<>();
        Map<Long, String> oldRoundByEvent = new HashMap<>();
        for (EventSchedule old : scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc()) {
            if (old.getEvent() == null || old.getRound() == null || old.getRound().isBlank()) continue;
            oldRoundByKey.putIfAbsent(SchedulePlacementMath.roundKey(old.getEvent().getId(), old.getGrade(),
                    old.getStartTime(), old.getVenue()), old.getRound());
            oldRoundByEvent.putIfAbsent(old.getEvent().getId(), old.getRound());
        }

        scheduleRepository.deleteAllSchedules();
        int order = 1;
        for (Map<String, Object> item : items) {
            Long eventId = item.get("eventId") != null
                    ? ((Number) item.get("eventId")).longValue() : null;
            if (eventId == null) continue;
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) continue;

            String grade = str(item.get("grade"), null);
            String startTime = str(item.get("startTime"), null);
            String venue = str(item.get("venue"), "田径场");
            String round = resolveSavedRound(item, event, grade, startTime, venue,
                    oldRoundByKey, oldRoundByEvent);

            EventSchedule s = EventSchedule.builder()
                    .event(event)
                    .day(intVal(item.get("day"), 1))
                    .scheduleDate(str(item.get("scheduleDate"), null))
                    .grade(grade)
                    .timeSlot(str(item.get("timeSlot"), "上午"))
                    .startTime(startTime)
                    .endTime(str(item.get("endTime"), null))
                    .venue(venue)
                    .sortOrder(order++)
                    .durationMinutes(intVal(item.get("durationMinutes"), 30))
                    .round(round)
                    .remark(str(item.get("remark"), null))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            scheduleRepository.save(s);
        }
        log.info("手动保存赛程: 共{}条（轮次按入参/既有行/needHeats 三级保留）", order - 1);
        collaborationService.notify("schedule", "edited", "EventSchedule", null);
        // M5 修复（同 autoSchedule）：审计写入改到 afterCommit，规避 SQLite 单写者锁竞争；
        // 无活动事务时（单测直调）直接落审计。
        final int savedCount = order - 1;
        auditAfterCommit(() -> auditService.record("SCHEDULE_SAVE", "SCHEDULE", null, "手动保存赛程 " + savedCount + " 条"));
        return buildResult();
    }

    /** 轮次取值三级兜底：入参 → 既有行匹配 → event.needHeats 推断（B09/U09） */
    private String resolveSavedRound(Map<String, Object> item, Event event, String grade,
                                     String startTime, String venue,
                                     Map<String, String> oldRoundByKey,
                                     Map<Long, String> oldRoundByEvent) {
        Object raw = item.get("round");
        if (raw != null && !String.valueOf(raw).isBlank()) return String.valueOf(raw).trim();
        String byKey = oldRoundByKey.get(SchedulePlacementMath.roundKey(event.getId(), grade, startTime, venue));
        if (byKey != null) return byKey;
        String byEvent = oldRoundByEvent.get(event.getId());
        if (byEvent != null) return byEvent;
        return Boolean.TRUE.equals(event.getNeedHeats())
                ? ArrangementService.ROUND_PRELIM : ArrangementService.ROUND_FINAL;
    }

    public void clear() {
        scheduleRepository.deleteAllSchedules();
        log.info("清空项目赛程");
        collaborationService.notify("schedule", "deleted", "EventSchedule", null);
        // M5 修复（同 autoSchedule）：审计写入改到 afterCommit，规避 SQLite 单写者锁竞争；
        // 无活动事务时（单测直调）直接落审计。
        auditAfterCommit(() -> auditService.record("SCHEDULE_CLEAR", "SCHEDULE", null, "清空全部项目赛程"));
    }

    /**
     * M5 配套：审计写入优先走 afterCommit（规避 SQLite 单写者锁竞争）；
     * 若当前无活动事务（如单测直接调用编排方法），则直接落审计——
     * {@code auditService.record} 自身是 REQUIRES_NEW，不依赖外层事务。
     */
    public void auditAfterCommit(Runnable recordTask) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recordTask.run();
                }
            });
        } else if (auditService != null) {
            // 无活动事务且审计服务已注入（生产常态）时直接落审计；
            // 单测若未装配 auditService 则跳过，不因此抛 NPE。
            recordTask.run();
        }
    }

    public void export(HttpServletResponse response) {
        List<EventSchedule> schedules = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        // B09/U09：赛程表补「轮次」列——此前仅有项目名称，预赛与决赛混在一起无法区分，
        // 现场拿到赛程表看不出哪个是决赛。统一走 RoundLabelUtil（预赛/决赛/直接决赛）。
        Set<Long> prelimEventIds = arrangementRepository.findAll().stream()
                .filter(a -> ArrangementService.ROUND_PRELIM.equals(a.getRound()))
                .map(a -> a.getEvent() != null ? a.getEvent().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // B13/U11：赛程表是最容易「拿错版本」的一份出口，文件名统一带「阶段 + 版本 + 生成时间」，
        // 与 arrange_result.json / 编排表 / 成绩表 / 秩序册 命名口径一致。
        // 阶段判定：任一「有预赛的项目」已排出决赛条目 = 二次编排后。
        boolean afterSecond = schedules.stream()
                .anyMatch(s -> ArrangementService.ROUND_FINAL.equals(s.getRound())
                        && s.getEvent() != null && prelimEventIds.contains(s.getEvent().getId()));
        String fileName = "项目赛程表_" + com.sports.common.ExportNaming.stage(afterSecond)
                + "_v" + com.sports.common.ExportNaming.appVersion()
                + "_" + com.sports.common.ExportNaming.stamp() + ".xlsx";
        String enc = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + enc + ";filename*=UTF-8''" + enc);

        try (OutputStream out = response.getOutputStream()) {
            List<List<String>> data = new ArrayList<>();
            data.add(List.of("第几天", "日期", "时段", "开始", "结束", "场地", "年级", "项目名称", "项目编码",
                    "轮次", "类别", "是否田径", "道次", "项目内并发", "预计用时(分)"));
            for (EventSchedule s : schedules) {
                Event e = s.getEvent();
                boolean isTrack = e == null || !Boolean.FALSE.equals(e.getTrack());
                long seId = e != null && e.getId() != null ? e.getId() : -1L;
                String roundLabel = com.sports.common.RoundLabelUtil.label(
                        s.getRound(), prelimEventIds.contains(seId));
                data.add(List.of(
                        "第" + s.getDay() + "天",
                        n(s.getScheduleDate()), n(s.getTimeSlot()),
                        n(s.getStartTime()), n(s.getEndTime()), n(s.getVenue()),
                        n(s.getGrade()),
                        e != null ? n(e.getName()) : "",
                        e != null ? n(e.getCode()) : "",
                        roundLabel,
                        e != null ? n(e.getCategory()) : "",
                        isTrack ? "是" : "否",
                        e != null && e.getLaneCount() != null ? String.valueOf(e.getLaneCount()) : "0",
                        e != null ? String.valueOf(ScheduleAnalysisMath.concurrencyOf(e)) : "",
                        s.getDurationMinutes() != null ? String.valueOf(s.getDurationMinutes()) : ""));
            }
            List<List<String>> head = data.get(0).stream().map(List::of).collect(Collectors.toList());
            EasyExcel.write(out).head(head).sheet("项目赛程")
                    .doWrite(data.subList(1, data.size()));
        } catch (IOException e) {
            throw new RuntimeException("导出赛程失败: " + e.getMessage());
        }
        log.info("导出项目赛程: 共{}条", schedules.size());
    }

    public Map<String, Object> buildResult() {
        List<EventSchedule> schedules = scheduleRepository.findByOrderByDayAscSortOrderAscStartTimeAsc();

        // B09/U09：把「该项目的预赛是否已排出」一并算出来，供轮次标签判定
        // （round 为 null 的旧行：项目有预赛 → 决赛，无预赛 → 直接决赛）
        Set<Long> prelimEventIds = arrangementRepository.findAll().stream()
                .filter(a -> ArrangementService.ROUND_PRELIM.equals(a.getRound()))
                .map(a -> a.getEvent() != null ? a.getEvent().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Map<String, Object>> items = schedules.stream().map(s -> {
            Event e = s.getEvent();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("eventId", e != null ? e.getId() : null);
            m.put("eventName", e != null ? e.getName() : "");
            m.put("eventCode", e != null ? e.getCode() : "");
            m.put("category", e != null ? e.getCategory() : "");
            m.put("genderLimit", e != null ? e.getGenderLimit() : "");
            m.put("isTrack", e == null || !Boolean.FALSE.equals(e.getTrack()));
            m.put("laneCount", e != null ? e.getLaneCount() : 0);
            m.put("concurrency", e != null ? ScheduleAnalysisMath.concurrencyOf(e) : 0);
            m.put("isTeam", e != null && Boolean.TRUE.equals(e.getTeam()));
            m.put("teamSize", e != null ? e.getTeamMembers() : 0);
            m.put("day", s.getDay());
            m.put("scheduleDate", s.getScheduleDate());
            m.put("grade", s.getGrade());
            m.put("timeSlot", s.getTimeSlot());
            m.put("startTime", s.getStartTime());
            m.put("endTime", s.getEndTime());
            m.put("venue", s.getVenue());
            m.put("sortOrder", s.getSortOrder());
            m.put("durationMinutes", s.getDurationMinutes());
            // B09/U09：轮次随行返回，前端原样回传 → 手动保存不再丢轮次
            m.put("round", s.getRound());
            m.put("roundLabel", com.sports.common.RoundLabelUtil.label(
                    s.getRound(), e != null && prelimEventIds.contains(e.getId())));
            m.put("remark", s.getRemark());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("days", items.stream().mapToInt(i -> intVal(i.get("day"), 0)).max().orElse(0));

        // 按天分组，方便前端渲染甘特/时间表
        Map<Integer, List<Map<String, Object>>> byDay = items.stream()
                .collect(Collectors.groupingBy(i -> intVal(i.get("day"), 0), TreeMap::new, Collectors.toList()));
        result.put("byDay", byDay);
        return result;
    }
}
