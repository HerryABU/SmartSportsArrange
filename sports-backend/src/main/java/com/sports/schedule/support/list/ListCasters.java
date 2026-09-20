package com.sports.schedule.support.list;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表/集合安全转换（support 系：列表转换）。
 *
 * <p>把「任意 Object（List/Map/逗号分隔字符串）」安全转成强类型列表，越界/非预期元素跳过，
 * 不抛异常。纯函数、无状态、不依赖 Spring，可独立单测。</p>
 */
public final class ListCasters {

    private ListCasters() {
    }

    /** 解析自定义项目顺序（eventId 列表，非法项跳过） */
    public static List<Long> longList(Object v) {
        List<Long> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            Long id = asLong(o);
            if (id != null) out.add(id);
        }
        return out;
    }

    /** 把「任意列表」安全转成 List&lt;Map&lt;String,Object&gt;&gt;（元素非 Map 则跳过） */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> castList(Object v) {
        if (!(v instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        }
        return out;
    }

    /** 把「任意列表」安全转成 List&lt;String&gt;（去空、trim；兼容逗号分隔的字符串） */
    @SuppressWarnings("unchecked")
    public static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，]")) {
                if (!part.trim().isEmpty()) out.add(part.trim());
            }
        }
        return out;
    }

    /** 任意对象 → Long（Number 直接转，字符串尝试解析，失败返回 null） */
    public static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
