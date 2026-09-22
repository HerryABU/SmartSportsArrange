package com.sports.collab;

import java.util.LinkedHashMap;
import java.util.Map;
import com.sports.entity.arrange.Arrangement;
import com.sports.entity.event.EventSchedule;
import com.sports.service.audit.AuditService;

/**
 * 一次赛程改动的通知事件——协作层在编排/调整落库后广播出去，
 * 让「打开同一页面的另一个人」尽早知道数据变了、冲突提前暴露。
 *
 * <p>这是<b>内存态</b>的轻量事件，只用于实时通知与版本号推进，不做持久化
 * （持久化审计另由 AuditService 负责），因此字段刻意保持最小。</p>
 *
 * @param type      领域类型：schedule（赛程）/ arrangement（道次编排）
 * @param action    动作：auto-arranged / arranged / edited / deleted …
 * @param entity    实体名（可读性用途），如 EventSchedule / Arrangement
 * @param entityId  被改动的实体 id（可能是 meetId / eventId 等，可为 null）
 * @param version   全局单调递增的版本号（协作层的「时钟」）
 * @param actor     操作者用户名（无认证上下文时为 "system"）
 * @param timestamp 发生时间（epoch millis）
 */
public record ScheduleChangeEvent(String type, String action, String entity, Object entityId,
                                  long version, String actor, long timestamp) {

    /** 序列化为 JSON 友好结构（SSE/轮询接口直接返回） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("action", action);
        m.put("entity", entity);
        m.put("entityId", entityId);
        m.put("version", version);
        m.put("actor", actor);
        m.put("timestamp", timestamp);
        return m;
    }
}
