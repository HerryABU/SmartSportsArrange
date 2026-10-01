package com.sports.schedule.tournament;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排球赛（球赛的一个实例）赛制生成门面：分组循环 + 交叉淘汰。
 *
 * <p>排球有局制（3 局 2 胜 / 5 局 3 胜），赛时长约束比田径强；本类把赛制结构串起来，
 * 供「赛程编排」在此结构之上分配时间槽与场地。</p>
 */
public final class VolleyballTournament {

    private VolleyballTournament() {
    }

    /** 生成排球赛完整赛制结构（可直接序列化为 API 响应）。 */
    public static Map<String, Object> plan(List<String> teams, int nGroups, int advancePerGroup, int bestOf) {
        HybridGenerator.Plan p = HybridGenerator.plan(teams, nGroups, advancePerGroup, false);
        int groupMatchCount = p.groupMatches().values().stream().mapToInt(List::size).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sport", "volleyball");
        out.put("bestOf", bestOf);
        out.put("teamCount", teams.size());
        out.put("groups", p.groups());
        out.put("groupMatches", p.groupMatches());
        out.put("groupMatchCount", groupMatchCount);
        out.put("crossPairs", p.crossPairs());
        out.put("knockout", p.knockout());
        return out;
    }
}
