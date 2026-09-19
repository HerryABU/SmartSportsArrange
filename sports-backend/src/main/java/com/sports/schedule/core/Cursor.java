package com.sports.schedule.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场地时间游标：逐窗口记账（每个窗口各自的已用分钟），而不是只记「当前推进到哪」。
 *
 * <p>U25/B22：旧实现是「单向传送带」——只有 windowIdx + used 两个标量，一旦推进到后面的窗口，
 * 前面窗口的剩余空间就再也回不去。改为按窗口记账后，放置可以回填任意窗口的剩余空间。</p>
 *
 * <p>U28/B25：从「只记一个前沿」升级为「记区间集合」。约束求解器给出的位置是乱序的，
 * 只靠单一前沿判断会把合法位置误判为冲突；区间集合同时让「能回填任意空隙」从口头约定变成可判定的事实。</p>
 */
public class Cursor {

    /** 已推进到的窗口（仅供参考/田赛分组的「不倒退」下界） */
    public int windowIdx = 0;
    /** windowIdx 窗口内的已用分钟（含间隔） */
    public int used = 0;
    final Map<Integer, Integer> usedByWindow = new HashMap<>();
    /** 该窗口内已占用的时间段（相对窗口起点的 [start, end) 列表，按 start 升序） */
    final Map<Integer, List<int[]>> occupied = new HashMap<>();

    /** 某窗口已用分钟（含该项目的前置间隔） */
    public int usedAt(int wi) {
        return usedByWindow.getOrDefault(wi, 0);
    }

    /** 记下「第 wi 个窗口的 [startRel, endRel) 已被占用」，并同步推进标记 */
    void mark(int wi, int startRel, int endRel) {
        List<int[]> list = occupied.computeIfAbsent(wi, k -> new ArrayList<>());
        list.add(new int[]{startRel, endRel});
        list.sort(Comparator.comparingInt(iv -> iv[0]));
        usedByWindow.merge(wi, endRel, Math::max);
        if (wi >= windowIdx) {
            windowIdx = wi;
            used = usedByWindow.getOrDefault(wi, 0);
        }
    }

    /** 该区间是否与该窗口已有占用冲突（含段前间隔；interval 取 0 即纯重叠判定） */
    boolean conflict(int wi, int startRel, int endRel, int interval) {
        for (int[] iv : occupied.getOrDefault(wi, List.of())) {
            if (startRel < iv[1] + interval && iv[0] < endRel + interval) return true;
        }
        return false;
    }

    /**
     * 预定一段区间（供约束求解结果落位使用）。与 {@link #placeAt} 的关键区别是
     * 不做「不得早于前沿」的顺位假设：求解器可能先给出靠后的位置、再给出靠前的空档。
     * 仍然严格校验区间不重叠，所以放开顺位不会产生重叠赛程。
     *
     * @return 预定成功；该区间与已有占用冲突时返回 false（调用方应回退贪心放置）
     */
    public boolean reserve(int wi, int startRel, int duration, int interval) {
        if (wi < 0 || startRel < 0) return false;
        int endRel = startRel + duration;
        if (conflict(wi, startRel, endRel, interval)) return false;
        mark(wi, startRel, endRel);
        return true;
    }

    /** 放进最早的「还放得下」窗口（回填允许）；都放不下返回 null */
    public Slot place(List<Window> windows, int duration, int interval) {
        return place(windows, duration, interval, 0);
    }

    /** 同上，但起点不早于 minWindowIdx 号窗口（用于田赛分组：同组落在同一天起） */
    public Slot place(List<Window> windows, int duration, int interval, int minWindowIdx) {
        for (int wi = Math.max(0, minWindowIdx); wi < windows.size(); wi++) {
            Window w = windows.get(wi);
            int u = usedAt(wi);
            int gap = u == 0 ? 0 : interval;     // 段前间隔：除窗口起点外，项目之间留间隔
            if (u + gap + duration <= w.capacity) {
                mark(wi, u + gap, u + gap + duration);
                return new Slot(w, w.startMinute + u + gap);
            }
        }
        return null;
    }

    /** 探测最早可放位置（只读，不记账）；起点不早于 minWindowIdx 号窗口 */
    public Probe probe(List<Window> windows, int duration, int interval, int minWindowIdx) {
        for (int wi = Math.max(0, minWindowIdx); wi < windows.size(); wi++) {
            Window w = windows.get(wi);
            int u = usedAt(wi);
            int gap = u == 0 ? 0 : interval;
            if (u + gap + duration <= w.capacity) {
                return new Probe(wi, new Slot(w, w.startMinute + u + gap));
            }
        }
        return null;
    }

    /**
     * 在指定窗口、指定起点放置；越界 / 与该窗口已有占用重叠则返回 null。
     * 用于田赛分组/捆绑组：把同组项目强制落到同一 (窗口, 起点) 以实现同时开赛。
     * 允许「回填」——只要起点不在已有占用的前沿之前。
     */
    public Probe placeAt(List<Window> windows, int wi, int startMinute, int duration) {
        if (wi < 0 || wi >= windows.size()) return null;
        Window w = windows.get(wi);
        int rel = startMinute - w.startMinute;      // 相对窗口起点的偏移
        if (rel < 0) return null;
        if (rel + duration > w.capacity) return null;
        int u = usedAt(wi);
        if (u > 0 && rel < u) return null;          // 不许压到已占用区间上
        mark(wi, rel, rel + duration);
        return new Probe(wi, new Slot(w, startMinute));
    }

    /** 比较推进程度：窗口更靠前、或同窗口已用时间更短的更"空闲" */
    boolean aheadOf(Cursor other) {
        if (windowIdx != other.windowIdx) return windowIdx < other.windowIdx;
        return used < other.used;
    }
}
