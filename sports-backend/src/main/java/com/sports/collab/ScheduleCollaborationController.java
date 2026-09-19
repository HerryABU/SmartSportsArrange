package com.sports.collab;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 协作查询端点（轮询拉增量；SSE 实时推送）。
 *
 * <p>前端策略：打开编排页后记录当前版本号，之后每隔几秒拉 {@code /events?since=版本号}，
 * 一旦拿到增量事件就提示「赛程已被 {@code actor} 更新」并刷新视图——冲突在保存前暴露。</p>
 */
@RestController
@RequestMapping("/api/collaboration")
@RequiredArgsConstructor
public class ScheduleCollaborationController {

    private final ScheduleCollaborationService collaborationService;

    /** 当前全局版本号（轻量探活，前端初次握手用） */
    @GetMapping("/version")
    public Map<String, Object> version() {
        return Map.of("version", collaborationService.currentVersion());
    }

    /**
     * 拉取版本号 &gt; {@code since} 的增量事件（轮询主入口）。
     *
     * @param since 上次已看到的版本号（0 = 从头，但事件环只保留最近 200 条）
     */
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "0") long since) {
        return Map.of(
                "version", collaborationService.currentVersion(),
                "events", collaborationService.eventsSince(since).stream()
                        .map(ScheduleChangeEvent::toMap)
                        .toList());
    }
}
