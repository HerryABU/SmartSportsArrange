package com.sports.service.arrange.alloc;

import com.sports.entity.athlete.Athlete;

import java.util.Map;

/**
 * 运动员维度的<b>纯函数</b>键值工具。
 *
 * <p>为什么单独成类：这四个函数原先散落在 3000 行的服务里，被算法、种子计算、
 * 校验、冲突检测等多处调用（仅 {@code classIdOf} 就有 14 个调用点）。它们无状态、
 * 无依赖，放在算法包里可以让算法类不必为了「取一个字段」而持有一个巨型服务的引用。</p>
 *
 * <p>⚠️ 两个口径不得互相替代：{@code classIdOf} 把「班级缺失」归为 0，用于
 * 「同组不同班」的相等判定；{@code classKeyOf} 把缺失归为空串，用于排序。</p>
 */
public final class AthleteKeys {

    private AthleteKeys() {
    }

    /** 运动员种子名次（1=最快）；无成绩者返回 MAX（排序时落最后）。 */
    public static int seedRankOf(Athlete a, Map<Long, Integer> seedRank) {
        if (a == null || a.getId() == null || seedRank == null) return Integer.MAX_VALUE;
        return seedRank.getOrDefault(a.getId(), Integer.MAX_VALUE);
    }

    public static String nullSafeStr(String s) {
        return s == null ? "" : s;
    }

    public static String classKeyOf(Athlete a) {
        if (a == null || a.getClassInfo() == null || a.getClassInfo().getName() == null) return "";
        return a.getClassInfo().getName();
    }

    /** 班级 id（班级缺失归为 0，用于「同组不同班」分组与占位） */
    public static Long classIdOf(Athlete a) {
        return a != null && a.getClassInfo() != null ? a.getClassInfo().getId() : 0L;
    }

}
