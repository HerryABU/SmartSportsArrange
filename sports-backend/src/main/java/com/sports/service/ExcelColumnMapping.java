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

    static {
        initColumnAliases();
        initTypeFields();
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
    }

    private ExcelColumnMapping() {
    }

    /** 智能匹配列名→标准字段 */
    public static String matchColumnName(String colName) {
        if (colName == null || colName.isBlank()) return null;
        String s = colName.trim().toLowerCase().replaceAll("[\\s\\-_/（）()]", "");
        for (Map.Entry<String, List<String>> e : COLUMN_ALIASES.entrySet()) {
            for (String alias : e.getValue()) {
                String a = alias.toLowerCase().replaceAll("[\\s\\-_/（）()]", "");
                if (s.equals(a) || s.contains(a) || a.contains(s))
                    return e.getKey();
            }
        }
        return null;
    }

    public static String getFieldLabel(String type, String field) {
        Map<String, String> fields = TYPE_FIELDS.getOrDefault(type, TYPE_FIELDS.get("athlete"));
        return fields.getOrDefault(field, field);
    }
}
