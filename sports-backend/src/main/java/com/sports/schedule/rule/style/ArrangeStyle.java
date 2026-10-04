package com.sports.schedule.rule.style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sports.service.arrange.ArrangementService;

/**
 * 「自定义规则」可选款型（编排分组的可选款型规则目录）。
 *
 * <p><b>名义</b>： 就是「自定义规则」层——用户可选择用哪一款规则来分组分道。
 * 新增一款规则只需在此加一个枚举值并在 {@code ArrangementService.allocate} 里分流；
 * 前端通过 {@code GET /api/arrange/styles} 拉取目录动态列出，<b>无需改前端</b>。</p>
 *
 * <p><b>后续（预告）</b>：将新增「伪代码 / 自定义编排脚本」支持（仿 Minecraft 的
 * 命令方块 / 数据包脚本思路，得益于 Java 成熟的 DSL 生态）——它同属自定义规则层，作为本目录的新款型接入；
 * 两条路径并存：<i>外部 DSL</i>（自定义语法 + ANTLR 解析，声明式）与
 * <i>脚本注入</i>（JSR-223 嵌入 Groovy/JS 引擎，命令式）。前端可再叠加
 * 「代码 / 积木」双模式（共享同一棵编排 AST）。</p>
 */
public enum ArrangeStyle {

    /** 默认：同组不同班 + 匈牙利分道（班级均衡） */
    CLASS("class", "班级均衡", "同组不同班，组内按班级错开道次（默认）"),

    /** 蛇形排布：按年级/班级排序后 S 形分散到各组（确定性），不强制同组不同班 */
    SNAKE("snake", "蛇形排布", "按年级→班级排序后 S 形分散到各组，组内道次蛇形落位（确定性）"),

    /** 种子蛇形：按成绩/种子（预赛名次·成绩）排序后 S 形分散到各组，使各组种子强度均衡 */
    SNAKE_SEEDED("snakeSeed", "种子蛇形", "按成绩/种子（预赛名次·成绩）排序后 S 形分散，各组强度均衡；无成绩时退回报名序"),

    /**
     * AI 派遣：派遣顺序由 AI 模型给出（班级/性别/报名项目数/种子特征 → 优先级），
     * 再走与蛇形相同的入组/分道实现。
     *
     * <p>与「种子蛇形」的区别在**排序依据**：种子蛇形只看成绩（无成绩就退回报名序，
     * 等于没优化）；AI 派遣同时考虑班级分布、兼项数量与性别，目标是「同班在时间上分散、
     * 各组实力均衡」这两个互相拉扯的目标同时尽量满足。</p>
     *
     * <p>模型缺失或推理失败时**自动回退**为种子蛇形顺序，接口行为不变。</p>
     */
    AI("ai", "AI 派遣", "由 AI 模型给出派遣顺序再做蛇形分散（兼顾同班分散与实力均衡）；模型不可用时回退"),

    /**
     * 规划层：贪心打底 + **显式代价的局部搜索**（搬迁 / 交换），把班级与人数分布压到更紧更均衡。
     *
     * <p>与「班级均衡」的区别在**目标**：班级均衡只用「人最少的组」这条贪心规则，
     * 一旦前几步把分布走歪就再也纠不回来；规划层则把「空道次浪费 + 组人数不均衡」
     * 写成一个代价，再用只接受严格变好的搬迁/交换把它压下去 ——
     * 是「先解出来、再谈好不好」的那一层（与 {@code PredictivePlanner} 同一套思路，
     * 只是对象从「赛会级的时段槽」换成「单项目的组次」）。</p>
     *
     * <p>确定性（无随机），相同报名必得相同分组；硬约束「同组不同班」与道次上限不变，
     * 因此结果<b>一定合法</b>，局部搜索只是让它更整齐。分道复用既有的匈牙利精确分道。</p>
     */
    PLAN("plan", "规划层", "贪心打底 + 显式代价的局部搜索（搬迁/交换压紧空道次与人数不均衡）；确定性，同组不同班不变");

    public final String id;
    public final String label;
    public final String description;

    ArrangeStyle(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    /** 是否属于「蛇形」家族（同一套入组/分道实现，仅排序口径不同）。 */
    public boolean isSnake() {
        return this == SNAKE || this == SNAKE_SEEDED || this == AI;
    }

    /** 是否需要「种子名次」表（种子蛇形与 AI 派遣都靠它驱动排序）。 */
    public boolean needsSeedRank() {
        return this == SNAKE_SEEDED || this == AI;
    }

    /**
     * 是否是「规划层」款型 —— 贪心打底后跑**显式代价的局部搜索**。
     *
     * <p>单独开一个判定而不是塞进 {@link #isSnake()}：它与蛇形家族没有任何共性
     * （蛇形是确定性排序分散、不强制同组不同班；规划层强制同组不同班并做搜索）。
     * 把不相关的款型塞进同一个分支，是「改一个款型顺手改坏另一个」的常见来源。</p>
     */
    public boolean isPlan() {
        return this == PLAN;
    }

    /** 按款型 id 解析（大小写不敏感）；未知/为空一律回退默认 {@link #CLASS}。 */
    public static ArrangeStyle of(String id) {
        if (id != null) {
            String t = id.trim();
            for (ArrangeStyle r : values()) {
                if (r.id.equalsIgnoreCase(t)) return r;
            }
        }
        return CLASS;
    }

    /** 款型目录（供前端「选择哪一款」渲染下拉/单选）。 */
    public static List<Map<String, String>> catalog() {
        List<Map<String, String>> list = new ArrayList<>();
        for (ArrangeStyle r : values()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("label", r.label);
            m.put("description", r.description);
            list.add(m);
        }
        return list;
    }
}
