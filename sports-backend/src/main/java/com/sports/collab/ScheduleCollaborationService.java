package com.sports.collab;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 赛程协作中心（实时协作的核心）。
 *
 * <h2>它解决什么</h2>
 * 多人同时打开编排页时，A 一改、B 还盯着旧数据，等 B 保存时就把 A 的改动覆盖了。
 * 协作中心维护一个<b>全局单调版本号</b>：任何编排/调整落库后 {@link #notify} 一次，
 * 版本号 +1 并留痕。前端（轮询或 SSE）发现版本号变了，就立刻刷新并提示「赛程已被他人更新」，
 * 冲突在<b>保存之前</b>暴露，而不是保存之后互相覆盖。
 *
 * <h2>两种消费方式</h2>
 * <ul>
 *   <li><b>轮询</b>（最稳）：客户端定时拉 {@code /api/collaboration/events?since=上次版本}，
 *       拿增量事件自己决定是否刷新——JWT 走请求头即可，无跨域/长连接顾虑；</li>
 *   <li><b>SSE</b>：订阅 {@code /api/collaboration/stream}，服务端主动推（可选，见 Controller）。</li>
 * </ul>
 *
 * <p>本服务<b>只进内存</b>（最近 N 条事件 + 版本号），重启即清零——它回答的是「现在有没有人改」，
 * 不承担「历史谁改了什么」（那是 AuditService 的职责）。</p>
 */
@Slf4j
@Service
public class ScheduleCollaborationService {

    /** 全局单调版本号（协作层的「时钟」） */
    private final AtomicLong version = new AtomicLong(0);

    /** 最近的事件环（供轮询补拉增量；只保留最近 N 条，防止内存无界增长） */
    private final Deque<ScheduleChangeEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 200;

    /**
     * 广播一次改动：版本号 +1、留痕，返回生成的事件（含新版本号）。
     *
     * @param type     领域类型（schedule / arrangement）
     * @param action   动作（auto-arranged / arranged / edited / deleted …）
     * @param entity   实体名（可读性用途）
     * @param entityId 被改动的实体 id（可为 null）
     */
    public ScheduleChangeEvent notify(String type, String action, String entity, Object entityId) {
        ScheduleChangeEvent event = new ScheduleChangeEvent(type, action, entity, entityId,
                version.incrementAndGet(), currentActor(), System.currentTimeMillis());
        recentEvents.addLast(event);
        while (recentEvents.size() > MAX_RECENT) {
            recentEvents.pollFirst();
        }
        log.info("协作通知 #{}: {} {} {} ({})", event.version(), type, action, entity, entityId);
        return event;
    }

    /** 当前版本号 */
    public long currentVersion() {
        return version.get();
    }

    /** 取版本号 &gt; {@code sinceVersion} 的增量事件（升序） */
    public List<ScheduleChangeEvent> eventsSince(long sinceVersion) {
        return recentEvents.stream()
                .filter(e -> e.version() > sinceVersion)
                .collect(Collectors.toList());
    }

    /** 当前操作者：优先取认证上下文里的用户名，取不到（后台/无人值守）回退 "system" */
    private String currentActor() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getName() != null
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // 无认证上下文（单元测试 / 后台任务）→ 用 system
        }
        return "system";
    }
}
