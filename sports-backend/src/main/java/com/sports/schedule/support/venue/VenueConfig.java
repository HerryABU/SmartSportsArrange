package com.sports.schedule.support.venue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场地配置解析（support 系：场地）。
 *
 * <p>兼容旧「字符串数组」与新「{name, code, type} 对象数组」两种场地配置形态，
 * 并提供编码/名称摘取与按类型子集筛选。纯函数、无状态、不依赖 Spring，可独立单测。</p>
 */
public final class VenueConfig {

    private VenueConfig() {
    }

    /** 解析场地名列表：兼容旧字符串数组 ["田径场", …] 与新对象数组 [{name, code}, …] */
    @SuppressWarnings("unchecked")
    public static List<String> venueNames(Object v) {
        List<String> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o == null) continue;
            if (o instanceof Map<?, ?> m) {
                Object name = ((Map<String, Object>) m).get("name");
                if (name != null && !String.valueOf(name).isBlank()) out.add(String.valueOf(name).trim());
            } else {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** 解析场地列表为完整 {name, code} 对象列表（保留编码，用于按项目级场地编码绑定并发池） */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> venueListOf(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> mm = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) mm.put(String.valueOf(en.getKey()), en.getValue());
            boolean hasName = mm.get("name") != null && !String.valueOf(mm.get("name")).isBlank();
            boolean hasCode = mm.get("code") != null && !String.valueOf(mm.get("code")).isBlank();
            if (hasName || hasCode) out.add(mm);
        }
        return out;
    }

    /** 取场地对象的编码（trim 后）；无编码返回 null */
    public static String codeOf(Map<String, Object> v) {
        if (v == null) return null;
        Object code = v.get("code");
        return code != null && !String.valueOf(code).isBlank() ? String.valueOf(code).trim() : null;
    }

    /** 取场地对象的名字（缺省回退编码） */
    public static String nameOf(Map<String, Object> v) {
        Object n = v.get("name");
        return n != null && !String.valueOf(n).isBlank() ? String.valueOf(n).trim() : codeOf(v);
    }

    /** 取某类型的场地子集（type 精确匹配，忽略大小写） */
    public static List<Map<String, Object>> venuesOfType(List<Map<String, Object>> venueList, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : venueList) {
            Object t = v.get("type");
            if (t != null && type.equalsIgnoreCase(String.valueOf(t).trim())) out.add(v);
        }
        return out;
    }
}
