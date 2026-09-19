package com.sports.schedule.support;

import com.sports.common.Grades;
import com.sports.schedule.verify.ScheduleViolation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 赛程编排的<b>纯函数工具</b>：类型/字符串/时间/场地配置解析。
 *
 * <p>这些方法<b>无状态、无 IO、不依赖 Spring</b>，从 {@code ScheduleService} 拆出来，
 * 让编排主流程专注在「决策」而不是「解析脏数据」。任何方法都可脱离服务独立单测。</p>
 *
 * <p>命名刻意简短（{@code str}/{@code n}/{@code intVal}/{@code dblVal}…）：编排主流程里
 * 这些解析是高频原语，短名更紧凑。含义见各方法注释。</p>
 */
public final class ScheduleSupport {

    private ScheduleSupport() {
    }

    /**
     * 解析 "HH:mm" → 当日分钟（与 {@link #fmt} 互逆）；解析不了返回 0。
     *
     * <p>本方法是 {@link #parseMinute} 的语义别名：赛程主流程里多处调用它解析起止时间
     * （必然带冒号），而 {@code parseMinute} 同时兼容裸整数分钟，能力是超集。
     * 这里直接委托，避免两份「HH:mm → 分钟」实现各写各的、日后逻辑漂移、出现
     * 一处修一处不修的不一致。</p>
     */
    public static int parseHhMm(String hhmm) {
        return parseMinute(hhmm);
    }

    /** 解析 "HH:mm"（与 {@link #fmt} 互逆）；解析不了返回 0 */
    public static int parseMinute(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return 0;
        String t = hhmm.trim();
        int colon = t.indexOf(':');
        try {
            if (colon < 0) return Integer.parseInt(t);
            int h = Integer.parseInt(t.substring(0, colon));
            int m = Integer.parseInt(t.substring(colon + 1));
            return Math.max(0, h * 60 + m);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 当日分钟 → "HH:mm"（与 {@link #parseMinute} 互逆） */
    public static String fmt(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /** 日期顺延 offset 天（解析失败原样返回，不抛异常） */
    public static String shiftDate(String startDate, int offset) {
        try {
            return LocalDate.parse(startDate).plusDays(offset).toString();
        } catch (Exception e) {
            return startDate;
        }
    }

    /** 读取 int 配置项，解析失败返回默认值 */
    public static int intVal(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /** 读取 double 配置项，解析失败返回默认值 */
    public static double dblVal(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /** 读取字符串配置项（空值回退默认） */
    public static String str(Object v, String def) {
        return v != null && !String.valueOf(v).isBlank() ? String.valueOf(v) : def;
    }

    /** null 安全空串 */
    public static String n(String s) {
        return s != null ? s : "";
    }

    /** 两个年级是否同一（空 = 不分年级，视为相同） */
    public static boolean sameGrade(String a, String b) {
        boolean ea = a == null || a.isBlank();
        boolean eb = b == null || b.isBlank();
        if (ea && eb) return true;
        if (ea || eb) return false;
        return Grades.same(a, b);
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

    /** 解析田赛分组 [{name, eventIds:[...]}] → eventId → 组名（同名视为同组） */
    @SuppressWarnings("unchecked")
    public static Map<Long, String> parseFieldGroups(Object v) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (!(v instanceof List<?> list)) return map;
        int idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = (Map<String, Object>) o;
            String name = str(g.get("name"), null);
            if (name == null || name.isBlank()) name = "田赛组" + (++idx);
            if (!(g.get("eventIds") instanceof List<?> ids)) continue;
            for (Object idObj : ids) {
                Long id = asLong(idObj);
                if (id != null) map.put(id, name);
            }
        }
        return map;
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

    /** 摘出第一条阻塞级问题的摘要，用于 warnings 里的一行提示（完整清单走 verification 字段） */
    @SuppressWarnings("unchecked")
    public static String firstViolationBrief(Map<String, Object> verification) {
        Object vs = verification.get("violations");
        if (!(vs instanceof List<?> list)) return "";
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            if (!ScheduleViolation.LEVEL_BLOCKER.equals(String.valueOf(m.get("level")))) continue;
            return String.valueOf(m.get("subject")) + " → " + String.valueOf(m.get("detail"));
        }
        return "";
    }
}
