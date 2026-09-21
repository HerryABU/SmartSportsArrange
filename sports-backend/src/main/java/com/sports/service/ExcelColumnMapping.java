package com.sports.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 列别名 / 类型字段映射（从 {@code ExcelService} 拆出，M1）。
 *
 * <p>纯静态配置 + 纯函数：标准字段 → 候选列名别名、各导入类型的字段清单、
 * 以及「表格列名 → 标准字段」的智能匹配。不依赖任何 Repository / Spring，
 * 因此可独立单测，也与导入/导出两条链路解耦（导出侧不再被这批导入配置拖累）。</p>
 */
public final class ExcelColumnMapping {

    /** 列别名：标准字段 → 可能的列名列表 */
    public static final Map<String, List<String>> COLUMN_ALIASES = new LinkedHashMap<>();

    /** 每种类型的可用字段：fieldName → 中文标签 */
    public static final Map<String, Map<String, String>> TYPE_FIELDS = new LinkedHashMap<>();

    /**
     * 类型专属列名别名（表头 → 该类型处理器<b>真正读取</b>的字段名）。
     *
     * <p>为什么需要它：全局别名表是「中文列名 → 通用字段名」，而各类型的处理器读的键并不总等于通用字段名
     * （如 {@code event} 类型读 {@code name/code}，通用匹配却会给出 {@code eventName/eventCode}）。
     * 多表导入靠表头自动映射，若在此分叉就会「导入成功但字段全空」。本表显式给出该类型的落点，
     * 且用 {@link LinkedHashMap} 固定顺序，使**同长度别名的平局有确定结果**（{@code Map.of} 的组合顺序
     * 由 JVM 每次运行随机化，不能用来做平局判定）。</p>
     */
    private static final Map<String, Map<String, String>> TYPE_COLUMN_ALIASES = new LinkedHashMap<>();

    static {
        initColumnAliases();
        initTypeFields();
        initTypeColumnAliases();
    }

    private static void initColumnAliases() {
        COLUMN_ALIASES.put("name",         List.of("姓名","名字","name","运动员名称","学生姓名","选手","学生"));
        COLUMN_ALIASES.put("gender",       List.of("性别","sex","gender","男女"));
        COLUMN_ALIASES.put("grade",        List.of("年级","grade","年段","年级名称"));
        COLUMN_ALIASES.put("className",    List.of("班级","class","班别","班级名称","班","班号"));
        COLUMN_ALIASES.put("number",       List.of("号码簿","号码布","号码","number","参赛号","编号","号码牌"));
        COLUMN_ALIASES.put("studentId",    List.of("学号","studentId","学籍号"));
        COLUMN_ALIASES.put("idCard",       List.of("身份证号","身份证","idCard"));
        COLUMN_ALIASES.put("birthDate",    List.of("出生日期","生日","birthDate"));
        COLUMN_ALIASES.put("emergencyContact", List.of("紧急联系人","联系人","emergencyContact"));
        COLUMN_ALIASES.put("emergencyPhone",   List.of("紧急联系电话","联系电话","电话","phone"));
        COLUMN_ALIASES.put("healthStatus",     List.of("健康状况","健康","healthStatus"));
        COLUMN_ALIASES.put("remark",       List.of("备注","remark","说明","描述"));
        COLUMN_ALIASES.put("eventCode",    List.of("项目编码","项目代码","项目","code","eventCode"));
        COLUMN_ALIASES.put("eventName",    List.of("项目名称","项目","eventName"));
        COLUMN_ALIASES.put("athleteNumber",List.of("运动员号码","号码","运动员编号","athleteNumber"));
        COLUMN_ALIASES.put("athleteName",  List.of("运动员姓名","姓名","运动员","athleteName"));
        COLUMN_ALIASES.put("teamTag",      List.of("团队标识号","队伍标识","组号","组别标识","teamTag","team"));
        COLUMN_ALIASES.put("rawTime",      List.of("成绩","时间","result","rawTime","比赛成绩","用时"));
        COLUMN_ALIASES.put("heat",         List.of("组别","组","heat","组号","轮次"));
        COLUMN_ALIASES.put("lane",         List.of("道次","道","lane","跑道"));
        COLUMN_ALIASES.put("windSpeed",    List.of("风速","windSpeed"));
        COLUMN_ALIASES.put("rank",         List.of("名次","rank","排名","第几名"));
        COLUMN_ALIASES.put("score",        List.of("积分","score","point","得分"));
        COLUMN_ALIASES.put("classCode",    List.of("班级编码","班级编号","classCode"));
        COLUMN_ALIASES.put("teacherName",  List.of("班主任","teacherName","班主任姓名"));
        COLUMN_ALIASES.put("username",     List.of("用户名","账号","username","用户名"));
        COLUMN_ALIASES.put("password",     List.of("密码","password"));
        COLUMN_ALIASES.put("realName",     List.of("姓名","真实姓名","realName"));
        COLUMN_ALIASES.put("role",         List.of("角色","role","身份"));
        COLUMN_ALIASES.put("phone",        List.of("电话","手机号","手机","phone"));
        COLUMN_ALIASES.put("category",     List.of("类别","类型","项目类型","category","项目类别"));
        COLUMN_ALIASES.put("genderLimit",  List.of("性别限制","性别","genderLimit"));
        COLUMN_ALIASES.put("defaultLanes", List.of("跑道数","道数","lanes","defaultLanes"));
        COLUMN_ALIASES.put("scoringType",  List.of("计分方式","计分规则","scoringType"));
        COLUMN_ALIASES.put("record",       List.of("校纪录","纪录","record"));
        COLUMN_ALIASES.put("refereesPerGroup", List.of("组次裁判数量","每组裁判数","每组次裁判数","裁判人数","裁判数","refereesPerGroup"));
        // 运动项目表（7列精简模板）专用字段
        COLUMN_ALIASES.put("teamMembers",      List.of("每组人数","每队人数","团队人数","teamMembers"));
        COLUMN_ALIASES.put("concurrency",      List.of("每批组数","并发组数","每批同时组数","concurrency"));
        COLUMN_ALIASES.put("defaultVenueCode", List.of("场地号","场地编码","defaultVenueCode"));
        COLUMN_ALIASES.put("perBatchMinutes",  List.of("每批所需时间","每批分钟","每批所需分钟","perBatchMinutes"));
    }

    private static void initTypeFields() {
        Map<String, String> athleteFields = new LinkedHashMap<>();
        athleteFields.put("name","姓名"); athleteFields.put("gender","性别");
        athleteFields.put("grade","年级"); athleteFields.put("className","班级");
        athleteFields.put("studentId","学号"); athleteFields.put("number","号码布编号");
        athleteFields.put("idCard","身份证号"); athleteFields.put("birthDate","出生日期");
        athleteFields.put("emergencyContact","紧急联系人");
        athleteFields.put("emergencyPhone","紧急联系电话");
        athleteFields.put("healthStatus","健康状况"); athleteFields.put("remark","备注");
        TYPE_FIELDS.put("athlete", athleteFields);
        TYPE_FIELDS.put("score", new LinkedHashMap<>(Map.of(
            "eventCode","项目编码","athleteNumber","运动员号码","athleteName","运动员姓名",
            "rawTime","成绩","heat","组别","lane","道次","windSpeed","风速","remark","备注")));
        TYPE_FIELDS.put("registration", new LinkedHashMap<>(Map.of(
            "eventCode","项目编码","athleteNumber","运动员号码","athleteName","运动员姓名",
            "grade","年级","className","班级","remark","备注")));
        TYPE_FIELDS.put("class", new LinkedHashMap<>(Map.of(
            "name","班级名称","code","班级编码","grade","年级","teacherName","班主任")));
        TYPE_FIELDS.put("user", new LinkedHashMap<>(Map.of(
            "username","用户名","password","密码","realName","姓名","role","角色","phone","电话")));
        TYPE_FIELDS.put("event", new LinkedHashMap<>(Map.of(
            "name","项目名称","code","项目编码","category","类别","genderLimit","性别限制",
            "defaultLanes","跑道数","scoringType","计分规则","record","校纪录","refereesPerGroup","组次裁判数量")));
        // 运动项目表（7列精简模板）：项目代码/名称/每组人数/每批组数/项目类型/场地号/每批所需时间
        TYPE_FIELDS.put("eventsimple", new LinkedHashMap<>(Map.of(
            "eventCode","项目代码","eventName","项目名称","teamMembers","每组人数","concurrency","每批组数",
            "category","项目类型","defaultVenueCode","场地号","perBatchMinutes","每批所需时间(分)")));
        // 全名单表（5列）：年级/班级/姓名/学号/性别 —— 运动员主数据，按学号 upsert，班级缺失自动创建
        TYPE_FIELDS.put("roster", new LinkedHashMap<>(Map.of(
            "grade","年级","className","班级","name","姓名","studentId","学号","gender","性别")));
        // 报名表（7列）：年级/班级/姓名/学号/性别/项目/组号 —— 个人项目严禁填组号；团体/接力组号标记 A/B
        TYPE_FIELDS.put("signup", new LinkedHashMap<>(Map.of(
            "grade","年级","className","班级","name","姓名","studentId","学号","gender","性别",
            "eventCode","项目","teamTag","组号")));
        // 年级表（1~2列）：年级 / 序号 —— 供「把年级拆成独立表」的场景（写入系统年级配置）
        TYPE_FIELDS.put("grade", new LinkedHashMap<>(Map.of(
            "name","年级","sortOrder","序号")));
    }

    private ExcelColumnMapping() {
    }

    /**
     * 类型专属别名的初始化。
     *
     * <p>只为「通用字段名与该类型处理器读取的键不一致」或「同长度别名有歧义」的类型补表；
     * 其余类型（roster/signup/athlete/score/registration/eventsimple）用改进后的全局匹配已能正确落点。</p>
     */
    private static void initTypeColumnAliases() {
        // 班级表：处理器读 name(班级名称)/code(班级编码)/grade/teacherName。
        // 全局匹配会把「班级名称/班级」落到 className（处理器不读）→ 必须给专属表。
        Map<String, String> cls = new LinkedHashMap<>();
        cls.put("班级名称", "name");
        cls.put("班级", "name");
        cls.put("班别", "name");
        cls.put("班级编码", "code");
        cls.put("班级编号", "code");
        cls.put("年级", "grade");
        cls.put("班主任", "teacherName");
        cls.put("班主任姓名", "teacherName");
        cls.put("联系电话", "phone");
        TYPE_COLUMN_ALIASES.put("class", cls);

        // 表格2 项目表：处理器读 name/code（不是 eventName/eventCode）
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("项目名称", "name");
        ev.put("项目", "name");
        ev.put("项目编码", "code");
        ev.put("项目代码", "code");
        ev.put("类别", "category");
        ev.put("项目类型", "category");
        ev.put("类型", "category");
        ev.put("性别限制", "genderLimit");
        ev.put("跑道数", "defaultLanes");
        ev.put("道数", "defaultLanes");
        ev.put("计分规则", "scoringType");
        ev.put("计分方式", "scoringType");
        ev.put("校纪录", "record");
        ev.put("纪录", "record");
        ev.put("组次裁判数量", "refereesPerGroup");
        ev.put("裁判人数", "refereesPerGroup");
        TYPE_COLUMN_ALIASES.put("event", ev);

        // 用户表：处理器读 realName；全局匹配会把「姓名」落到 name（处理器不读）
        Map<String, String> user = new LinkedHashMap<>();
        user.put("用户名", "username");
        user.put("账号", "username");
        user.put("密码", "password");
        user.put("姓名", "realName");
        user.put("真实姓名", "realName");
        user.put("角色", "role");
        user.put("电话", "phone");
        user.put("手机号", "phone");
        TYPE_COLUMN_ALIASES.put("user", user);

        // 年级表：只有一列年级（可带序号）
        Map<String, String> grade = new LinkedHashMap<>();
        grade.put("年级", "name");
        grade.put("年级名称", "name");
        grade.put("年段", "name");
        grade.put("序号", "sortOrder");
        grade.put("排序", "sortOrder");
        grade.put("顺序", "sortOrder");
        TYPE_COLUMN_ALIASES.put("grade", grade);

        // 报名表：处理器读 eventCode/teamTag（与全局一致），但「组号」在全局表里与 heat 相邻易歧义 → 显式固定
        Map<String, String> signup = new LinkedHashMap<>();
        signup.put("年级", "grade");
        signup.put("班级", "className");
        signup.put("姓名", "name");
        signup.put("学号", "studentId");
        signup.put("性别", "gender");
        signup.put("项目", "eventCode");
        signup.put("项目编码", "eventCode");
        signup.put("项目代码", "eventCode");
        signup.put("组号", "teamTag");
        signup.put("队伍标识", "teamTag");
        TYPE_COLUMN_ALIASES.put("signup", signup);
    }

    /** 归一化：去空白/常见分隔符与大小写差异，便于「表头 ↔ 别名」比较。 */
    private static String normalize(String s) {
        return s == null ? null : s.trim().toLowerCase().replaceAll("[\\s\\-_/（）():：、]", "");
    }

    /**
     * 智能匹配列名 → 标准字段（全局）。
     *
     * <p><b>判定顺序：① 归一化后完全相等 → ② 包含匹配取「最长别名」，平局按别名表插入顺序。</b></p>
     *
     * <p>为什么不能沿用「按别名表顺序取首个包含命中」：那会让更具体的列名被更短的别名抢走——
     * 「项目名称」会被 {@code eventCode} 的别名「项目」命中（该键排在 {@code eventName} 之前），
     * 「项目类型」同样落到 {@code eventCode}。症状是<b>导入成功但字段全空/串列，且不报错</b>。</p>
     */
    public static String matchColumnName(String colName) {
        String s = normalize(colName);
        if (s == null || s.isEmpty()) return null;
        // ① 完全相等
        for (Map.Entry<String, List<String>> e : COLUMN_ALIASES.entrySet()) {
            for (String alias : e.getValue()) {
                if (s.equals(normalize(alias))) return e.getKey();
            }
        }
        // ② 包含匹配（最长别名优先）
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, List<String>> e : COLUMN_ALIASES.entrySet()) {
            for (String alias : e.getValue()) {
                String a = normalize(alias);
                if (a == null || a.isEmpty()) continue;
                if (s.contains(a) || a.contains(s)) {
                    if (a.length() > bestLen) {
                        bestLen = a.length();
                        best = e.getKey();
                    }
                }
            }
        }
        return best;
    }

    /**
     * 按「类型优先」匹配表头 → 该类型处理器读取的字段名（多表导入自动映射用）。
     *
     * <p>顺序：① 类型专属别名表（精确 → 最长包含）→ ② 全局别名匹配。</p>
     */
    public static String matchHeaderForType(String type, String header) {
        String s = normalize(header);
        if (s == null || s.isEmpty()) return null;

        Map<String, String> typeAliases = TYPE_COLUMN_ALIASES.get(type);
        if (typeAliases != null) {
            for (Map.Entry<String, String> e : typeAliases.entrySet()) {
                if (s.equals(normalize(e.getKey()))) return e.getValue();
            }
            String best = null;
            int bestLen = -1;
            for (Map.Entry<String, String> e : typeAliases.entrySet()) {
                String a = normalize(e.getKey());
                if (a == null || a.isEmpty()) continue;
                if (s.contains(a) || a.contains(s)) {
                    if (a.length() > bestLen) {
                        bestLen = a.length();
                        best = e.getValue();
                    }
                }
            }
            if (best != null) return best;
        }
        return matchColumnName(header);
    }

    /** 该类型自动映射时是否「认得」这个表头。 */
    public static boolean knowsHeader(String type, String header) {
        return matchHeaderForType(type, header) != null;
    }

    public static String getFieldLabel(String type, String field) {
        Map<String, String> fields = TYPE_FIELDS.getOrDefault(type, TYPE_FIELDS.get("athlete"));
        return fields.getOrDefault(field, field);
    }

    /** 该类型全部候选字段（供前端「手动指定列映射」下拉）。 */
    public static Map<String, String> fieldsOf(String type) {
        return TYPE_FIELDS.getOrDefault(type, TYPE_FIELDS.get("athlete"));
    }
}
