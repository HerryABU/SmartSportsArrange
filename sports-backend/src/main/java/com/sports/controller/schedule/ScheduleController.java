package com.sports.controller.schedule;

import com.sports.common.web.ApiResponse;
import com.sports.service.schedule.PredictivePlannerService;
import com.sports.service.schedule.ScheduleProgressTracker;
import com.sports.service.schedule.ScheduleService;
import com.sports.service.schedule.ScheduleTaskExecutor;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
    private final ScheduleProgressTracker progressTracker;
    private final ScheduleTaskExecutor taskExecutor;
    private final PredictivePlannerService predictivePlannerService;

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

    /**
     * 规划层**预演**（只读，不落库）：确定性启发 + 前向剪枝 + 局部回退，秒级给出方案与诊断。
     *
     * <p>为什么单独开一个预演端点：优化档与 AI 档都要跑秒级到分钟级，而「能不能排下、
     * 卡在哪」这个问题其实秒级就能回答。预演把这件事独立出来，用户可以：</p>
     * <ul>
     *   <li>先看可行性，再决定要不要花时间跑重档；</li>
     *   <li>拿到**节点扩展数 / 回退 / 修复 / 重启**这些诊断数字，把「排不出来」变成可定位的问题；</li>
     *   <li>对比不同配置（天数、时段、并行数）下的容量够不够。</li>
     * </ul>
     *
     * <p>返回中的 {@code violations} 恒应为空 —— 它只承载「非法落位」（超容/兼项）。
     * 一旦非空即说明规划器产出了非法方案，属缺陷；「排不下」一律走 {@code unplaced}。</p>
     */
    @PostMapping("/plan")
    public ApiResponse<?> planPreview(@RequestBody(required = false) Map<String, Object> config) {
        log.info("规划层预演: config={}", config);
        return ApiResponse.success(predictivePlannerService.plan(config));
    }

    /**
     * 自动编排赛程（异步版）——立即返回 taskId，前端轮询 {@link #progress} 看进度。
     *
     * <p>为什么需要它：真实学校规模的编排要走「构造启发式 → 算法组合波次 → GA/LNS/MNSA/ALNS/
     * Fix-opt 精修链 → 对抗式自检」，秒级到十秒级；而前端 axios 默认 30s 超时、页面只能干等。
     * 异步 + 轮询把「看不见的等待」变成「看得见的阶段进度」，也顺带规避了网关/代理超时。</p>
     *
     * <p>安全上下文（当前登录用户）在提交前捕获、在线程内恢复——否则审计日志会把编排记成匿名。</p>
     */
    @PostMapping("/auto/async")
    public ApiResponse<Map<String, Object>> autoScheduleAsync(
            @RequestBody(required = false) Map<String, Object> config) {
        Map<String, Object> override = config == null ? Map.of() : config;
        String taskId = progressTracker.begin("赛程自动编排");
        SecurityContext ctx = SecurityContextHolder.getContext();
        taskExecutor.submit(() -> {
            SecurityContextHolder.setContext(ctx);
            progressTracker.bind(taskId);
            try {
                progressTracker.stage("准备", 3, "读取编排配置与报名数据");
                Map<String, Object> result = scheduleService.autoSchedule(override);
                // 存**完整结果**而非摘要：前端轮询到 done 后可直接复用同一份结构，
                // 不必再发一次请求、也不会与同步接口的返回体产生两套解析逻辑。
                progressTracker.succeed(taskId, result);
                log.info("异步编排完成: taskId={}", taskId);
            } catch (Exception ex) {
                log.warn("异步编排失败: taskId={}, {}", taskId, ex.toString());
                progressTracker.fail(taskId, ex.getMessage());
            } finally {
                progressTracker.unbind();
                SecurityContextHolder.clearContext();
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("progressUrl", "/api/schedule/progress/" + taskId);
        return ApiResponse.success("已提交编排任务", out);
    }

    /**
     * 自动消解兼项冲突。
     *
     * <p>在 {@link #autoSchedule} 的基础上，强制 {@code max_attempts=0}（无限轮重排），
     * 以「真实兼项冲突数归零」为目标，直到冲突为 0 或收敛到不可再降的最低值。</p>
     */
    @PostMapping("/resolve-conflicts")
    public ApiResponse<?> resolveConflicts(@RequestBody(required = false) Map<String, Object> config) {
        log.info("自动消解兼项冲突: config={}", config);
        return ApiResponse.success("兼项冲突消解完成", scheduleService.resolveConflicts(config));
    }

    /** 查询编排进度（前端轮询）。 */
    @GetMapping("/progress/{taskId}")
    public ApiResponse<Map<String, Object>> progress(@PathVariable String taskId) {
        return progressTracker.get(taskId)
                .map(s -> ApiResponse.success(snapshotToMap(s)))
                .orElseGet(() -> ApiResponse.error(404, "任务不存在或已过期: " + taskId, null));
    }

    /** 最近编排任务（新的在前）——前端可展示「最近任务」或用于排障。 */
    @GetMapping("/progress")
    public ApiResponse<List<Map<String, Object>>> recentProgress() {
        return ApiResponse.success(progressTracker.recent().stream()
                .map(ScheduleController::snapshotToMap).toList());
    }

    private static Map<String, Object> snapshotToMap(ScheduleProgressTracker.Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", s.taskId());
        m.put("title", s.title());
        m.put("stage", s.stage());
        m.put("percent", s.percent());
        m.put("message", s.message());
        m.put("done", s.done());
        m.put("failed", s.failed());
        m.put("startedAt", s.startedAt());
        m.put("elapsedMs", s.elapsedMs());
        m.put("result", s.result());
        m.put("error", s.error());
        return m;
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

    /** 清空赛程（同步清空道次编排，含裁判分配与预留空位） */
    @DeleteMapping
    public ApiResponse<?> clear() {
        scheduleService.clear();
        return ApiResponse.success("赛程与道次编排已清空", null);
    }

    /** 导出赛程 Excel */
    @GetMapping("/export")
    public void export(HttpServletResponse response) {
        scheduleService.export(response);
    }
}
