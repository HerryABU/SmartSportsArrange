package com.sports.service.schedule;

import com.sports.schedule.core.primitive.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 「多起点自适应放置」的**顺序策略族**（2026-10-06 从 {@code ScheduleService} 抽出）。
 *
 * <p>排布质量对「先排谁」高度敏感：真实报名结构是「少数大班 + 多数小班 + 若干兼项多的人」，
 * 贪心前几步走歪就再也纠不回来。与其去调一条固定的排序口径，不如<b>多起点重试</b>：
 * 用若干条确定性的顺序各排一趟，再按冲突统计择优 —— 本类就是「若干条顺序」的定义处。</p>
 *
 * <h2>为什么全部是确定性、固定种子的</h2>
 * <p>连随机扰动也用<b>固定种子</b>（由趟序号推导）：同一份配置与数据下结果稳定，
 * 才能核对与回归；否则「换个时间跑出来不一样」会让所有对比失去意义。
 * 需要真随机时由调用方传不同种子，而不是让本类自己取 {@code new Random()}。</p>
 *
 * <h2>为什么是静态工具类而不是 Spring 组件</h2>
 * <p>这些函数只吃 {@code units} 与序号，<b>不碰任何仓储或组件</b> —— 是纯函数。
 * 抽出来后可脱离 Spring 容器直接单测，不必为了验证「第 3 条策略是不是固定种子」
 * 而装配整个 {@code ScheduleService}。</p>
 */
public final class MultiStartPlacementStrategy {

    private MultiStartPlacementStrategy() {
    }

    public static boolean passIsBetter(PlacementPassResult a, PlacementPassResult b) {
        if (a.conflictStat[1] != b.conflictStat[1]) return a.conflictStat[1] < b.conflictStat[1];
        if (a.conflictStat[0] != b.conflictStat[0]) return a.conflictStat[0] > b.conflictStat[0];
        return a.autoArrangeFails.size() < b.autoArrangeFails.size();
    }

    /**
     * 第 p 套放置顺序策略（确定性、可复现）：0=原始顺序，1=按参与人数降序，2=按兼项度降序，
     * 其后为固定种子随机扰动（种子随 p 递增）。供「多策略自适应重试」逐趟取用——
     * 既支持有限轮（p 上限=请求轮数），也支持无限轮（p 一直递增直到收敛判据触发）。
     * 随机扰动用固定种子保证同一份数据下结果稳定，便于核对与回归。
     */
    public static List<Integer> strategyAt(List<Unit> units, int p) {
        if (p == 0) return identityOrder(units.size());
        if (p == 1) return orderByParticipants(units);
        if (p == 2) return orderByDegree(units);
        return orderByShuffle(units.size(), 0x9e3779b97f4a7c15L + ((long) (p - 2)) * 0x85ebca6bL);
    }

    public static String strategyNameAt(int p) {
        if (p == 0) return "original";
        if (p == 1) return "byParticipantsDesc";
        if (p == 2) return "byDegreeDesc";
        return "shuffle#" + (p - 2);
    }

    private static List<Integer> identityOrder(int n) {
        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) idx.add(i);
        return idx;
    }

    /** 参与人数多的项目先排，先占无冲突时段 */
    private static List<Integer> orderByParticipants(List<Unit> units) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) idx.add(i);
        idx.sort((x, y) -> {
            int px = units.get(x).participants, py = units.get(y).participants;
            if (px != py) return Integer.compare(py, px);
            return Integer.compare(x, y);
        });
        return idx;
    }

    /** 与别的项目共享运动员越多的项目先排（兼项度高者先占位，余者避让） */
    private static List<Integer> orderByDegree(List<Unit> units) {
        Map<Long, Integer> freq = new HashMap<>();
        for (Unit u : units) for (Long a : u.athleteIds) freq.put(a, freq.getOrDefault(a, 0) + 1);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) idx.add(i);
        idx.sort((x, y) -> {
            int dx = degreeOf(units.get(x), freq), dy = degreeOf(units.get(y), freq);
            if (dx != dy) return Integer.compare(dy, dx);
            return Integer.compare(x, y);
        });
        return idx;
    }

    private static int degreeOf(Unit u, Map<Long, Integer> freq) {
        int d = 0;
        for (Long a : u.athleteIds) if (freq.getOrDefault(a, 0) >= 2) d++;
        return d;
    }

    public static List<Integer> orderByShuffle(int n, long seed) {
        List<Integer> idx = identityOrder(n);
        // 固定种子可复现：同一份配置/数据下结果稳定，便于核对与回归
        Collections.shuffle(idx, new Random(seed));
        return idx;
    }
}
