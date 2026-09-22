package com.sports.schedule.support;

import com.sports.schedule.support.config.ConfigReaders;
import com.sports.schedule.support.group.FieldGroups;
import com.sports.schedule.support.list.ListCasters;
import com.sports.schedule.support.time.TimeParse;
import com.sports.schedule.support.venue.VenueConfig;
import com.sports.schedule.support.verify.ViolationBrief;

import java.util.List;
import java.util.Map;
import com.sports.service.arrange.ArrangementService;
import com.sports.service.schedule.ScheduleBuildComponent;
import com.sports.service.schedule.SchedulePlacementComponent;
import com.sports.service.schedule.ScheduleQueryExportComponent;
import com.sports.service.schedule.ScheduleSelfCheckComponent;
import com.sports.service.schedule.ScheduleService;
import com.sports.service.schedule.ScheduleSolveComponent;

/**
 * 赛程编排的<b>纯函数工具门面</b>：类型/字符串/时间/场地配置解析。
 *
 * <p>本类仅作稳定门面，把所有静态方法委托到 {@code support.*} 下的算法系子包
 * （time/config/list/venue/group/verify），按职责拆分；公开方法签名与常量保持不变，
 * 调用方（ArrangementService / ScheduleBuildComponent / SchedulePlacementComponent /
 * ScheduleQueryExportComponent / ScheduleService / ScheduleSolveComponent /
 * ScheduleSelfCheckComponent，含 {@code import static ...ScheduleSupport.*}）无需改动。</p>
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

    // ── 时间解析与格式化（support.time） ─────────────────────────────────────

    /** 解析 "HH:mm" → 当日分钟（与 {@link #fmt} 互逆）；解析不了返回 0 */
    public static int parseHhMm(String hhmm) {
        return TimeParse.parseHhMm(hhmm);
    }

    /** 解析 "HH:mm"（与 {@link #fmt} 互逆）；解析不了返回 0 */
    public static int parseMinute(String hhmm) {
        return TimeParse.parseMinute(hhmm);
    }

    /** 当日分钟 → "HH:mm"（与 {@link #parseMinute} 互逆） */
    public static String fmt(int minuteOfDay) {
        return TimeParse.fmt(minuteOfDay);
    }

    /** 日期顺延 offset 天（解析失败原样返回，不抛异常） */
    public static String shiftDate(String startDate, int offset) {
        return TimeParse.shiftDate(startDate, offset);
    }

    // ── 配置项读取（support.config） ─────────────────────────────────────────

    /** 读取 int 配置项，解析失败返回默认值 */
    public static int intVal(Object v, int def) {
        return ConfigReaders.intVal(v, def);
    }

    /** 读取 double 配置项，解析失败返回默认值 */
    public static double dblVal(Object v, double def) {
        return ConfigReaders.dblVal(v, def);
    }

    /** 读取字符串配置项（空值回退默认） */
    public static String str(Object v, String def) {
        return ConfigReaders.str(v, def);
    }

    /** null 安全空串 */
    public static String n(String s) {
        return ConfigReaders.n(s);
    }

    /** 两个年级是否同一（空 = 不分年级，视为相同） */
    public static boolean sameGrade(String a, String b) {
        return ConfigReaders.sameGrade(a, b);
    }

    // ── 列表/集合安全转换（support.list） ────────────────────────────────────

    /** 解析自定义项目顺序（eventId 列表，非法项跳过） */
    public static List<Long> longList(Object v) {
        return ListCasters.longList(v);
    }

    /** 把「任意列表」安全转成 List&lt;Map&lt;String,Object&gt;&gt;（元素非 Map 则跳过） */
    public static List<Map<String, Object>> castList(Object v) {
        return ListCasters.castList(v);
    }

    /** 把「任意列表」安全转成 List&lt;String&gt;（去空、trim；兼容逗号分隔的字符串） */
    public static List<String> strList(Object v) {
        return ListCasters.strList(v);
    }

    /** 任意对象 → Long（Number 直接转，字符串尝试解析，失败返回 null） */
    public static Long asLong(Object o) {
        return ListCasters.asLong(o);
    }

    // ── 田赛分组解析（support.group） ─────────────────────────────────────────

    /** 解析田赛分组 [{name, eventIds:[...]}] → eventId → 组名（同名视为同组） */
    public static Map<Long, String> parseFieldGroups(Object v) {
        return FieldGroups.parseFieldGroups(v);
    }

    // ── 场地配置解析（support.venue） ─────────────────────────────────────────

    /** 解析场地名列表：兼容旧字符串数组与新对象数组 */
    public static List<String> venueNames(Object v) {
        return VenueConfig.venueNames(v);
    }

    /** 解析场地列表为完整 {name, code} 对象列表（保留编码，用于按项目级场地编码绑定并发池） */
    public static List<Map<String, Object>> venueListOf(Object v) {
        return VenueConfig.venueListOf(v);
    }

    /** 取场地对象的编码（trim 后）；无编码返回 null */
    public static String codeOf(Map<String, Object> v) {
        return VenueConfig.codeOf(v);
    }

    /** 取场地对象的名字（缺省回退编码） */
    public static String nameOf(Map<String, Object> v) {
        return VenueConfig.nameOf(v);
    }

    /** 取某类型的场地子集（type 精确匹配，忽略大小写） */
    public static List<Map<String, Object>> venuesOfType(List<Map<String, Object>> venueList, String type) {
        return VenueConfig.venuesOfType(venueList, type);
    }

    // ── 校验摘要（support.verify） ────────────────────────────────────────────

    /** 摘出第一条阻塞级问题的摘要，用于 warnings 里的一行提示（完整清单走 verification 字段） */
    public static String firstViolationBrief(Map<String, Object> verification) {
        return ViolationBrief.firstViolationBrief(verification);
    }
}
