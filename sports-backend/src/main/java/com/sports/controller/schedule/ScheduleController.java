package com.sports.controller.schedule;

import com.sports.common.web.ApiResponse;
import com.sports.service.schedule.ScheduleService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目赛程编排控制器（项目编排）
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /** 查看当前赛程 */
    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.success(scheduleService.list());
    }

    /** 自动编排赛程 */
    @PostMapping("/auto")
    public ApiResponse<?> autoSchedule(@RequestBody(required = false) Map<String, Object> config) {
        log.info("自动编排项目赛程: config={}", config);
        return ApiResponse.success("赛程编排完成", scheduleService.autoSchedule(config));
    }

    /** 手动保存赛程（替换全部） */
    @PostMapping("/save")
    public ApiResponse<?> save(@RequestBody List<Map<String, Object>> items) {
        log.info("手动保存赛程: {}条", items != null ? items.size() : 0);
        return ApiResponse.success("赛程保存成功", scheduleService.save(items != null ? items : List.of()));
    }

    /**
     * 赛程自检（内置裁判）。
     *
     * <p>对「当前库里的赛程表」做一次独立校验：按真实场地查重叠、按运动员查赶场、
     * 查时间自洽性与被压过头的时长，并附上「最忙的运动员 / 最紧张的场地」这两个对抗性聚焦结果
     * 与「实际比对了多少对」的审计计数。</p>
     *
     * <p>这是人机对抗循环的入口：编排人员手动调整后随时调用，立刻知道这次调整破坏了什么。
     * 返回的 violations 带类型码与严重级别，前端可据此高亮到具体行。</p>
     */
    @GetMapping("/verify")
    public ApiResponse<?> verify() {
        return ApiResponse.success(scheduleService.verifyCurrentSchedule());
    }

    /** 清空赛程 */
    @DeleteMapping
    public ApiResponse<?> clear() {
        scheduleService.clear();
        return ApiResponse.success("赛程已清空", null);
    }

    /** 导出赛程 Excel */
    @GetMapping("/export")
    public void export(HttpServletResponse response) {
        scheduleService.export(response);
    }
}
