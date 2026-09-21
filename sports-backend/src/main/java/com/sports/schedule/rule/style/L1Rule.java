package com.sports.schedule.rule.style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sports.service.arrange.ArrangementService;

/**
 * L1「自定义规则」可选款型（编排分组的 L1 层规则目录）。
 *
 * <p><b>名义</b>：L1 就是「自定义规则」层——用户可选择用哪一款规则来分组分道。
 * 新增一款规则只需在此加一个枚举值并在 {@code ArrangementService.allocate} 里分流；
 * 前端通过 {@code GET /api/arrange/l1-rules} 拉取目录动态列出，<b>无需改前端</b>。</p>
 *
 * <p><b>后续（预告）</b>：将新增「伪代码 / 自定义编排脚本」支持（仿 Minecraft 的
 * 命令方块 / 数据包脚本思路，得益于 Java 成熟的 DSL 生态）——它同属 L1 层，作为本目录的新款型接入；
 * 两条路径并存：<i>外部 DSL</i>（自定义语法 + ANTLR 解析，声明式）与
 * <i>脚本注入</i>（JSR-223 嵌入 Groovy/JS 引擎，命令式）。前端可再叠加
 * 「代码 / 积木」双模式（共享同一棵编排 AST）。</p>
 */
public enum L1Rule {

    /** 默认：同组不同班 + 匈牙利分道（班级均衡） */
    CLASS("class", "班级均衡", "同组不同班，组内按班级错开道次（默认）"),

    /** 蛇形排布：按年级/班级排序后 S 形分散到各组（确定性），不强制同组不同班 */
    SNAKE("snake", "蛇形排布", "按年级→班级排序后 S 形分散到各组，组内道次蛇形落位（确定性）"),

    /** 种子蛇形：按成绩/种子（预赛名次·成绩）排序后 S 形分散到各组，使各组种子强度均衡 */
    SNAKE_SEEDED("snakeSeed", "种子蛇形", "按成绩/种子（预赛名次·成绩）排序后 S 形分散，各组强度均衡；无成绩时退回报名序");

    public final String id;
    public final String label;
    public final String description;

    L1Rule(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    /** 是否属于「蛇形」家族（同一套入组/分道实现，仅排序口径不同）。 */
    public boolean isSnake() {
        return this == SNAKE || this == SNAKE_SEEDED;
    }

    /** 按款型 id 解析（大小写不敏感）；未知/为空一律回退默认 {@link #CLASS}。 */
    public static L1Rule of(String id) {
        if (id != null) {
            String t = id.trim();
            for (L1Rule r : values()) {
                if (r.id.equalsIgnoreCase(t)) return r;
            }
        }
        return CLASS;
    }

    /** 款型目录（供前端「选择哪一款」渲染下拉/单选）。 */
    public static List<Map<String, String>> catalog() {
        List<Map<String, String>> list = new ArrayList<>();
        for (L1Rule r : values()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("label", r.label);
            m.put("description", r.description);
            list.add(m);
        }
        return list;
    }
}
