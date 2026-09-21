package com.sports.schedule.support.group;

import com.sports.schedule.support.config.ConfigReaders;
import com.sports.schedule.support.list.ListCasters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 田赛分组解析（support 系：分组）。
 *
 * <p>解析自定义田赛分组配置 [{name, eventIds:[...]}] → eventId → 组名（同名视为同组），
 * 复用通用配置读取与列表转换原语。纯函数、无状态、不依赖 Spring，可独立单测。</p>
 */
public final class FieldGroups {

    private FieldGroups() {
    }

    /** 解析田赛分组 [{name, eventIds:[...]}] → eventId → 组名（同名视为同组） */
    @SuppressWarnings("unchecked")
    public static Map<Long, String> parseFieldGroups(Object v) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (!(v instanceof List<?> list)) return map;
        int idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = (Map<String, Object>) o;
            String name = ConfigReaders.str(g.get("name"), null);
            if (name == null || name.isBlank()) name = "田赛组" + (++idx);
            if (!(g.get("eventIds") instanceof List<?> ids)) continue;
            for (Object idObj : ids) {
                Long id = ListCasters.asLong(idObj);
                if (id != null) map.put(id, name);
            }
        }
        return map;
    }
}
