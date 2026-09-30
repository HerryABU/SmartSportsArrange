package com.sports.schedule.support.protection;

import com.sports.entity.protection.AdminTimeProtection;
import com.sports.schedule.core.primitive.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 行政时间保护的纯静态判定 / 切分工具（无 Spring 依赖，可独立单测）。
 *
 * <p>两种消费形态：</p>
 * <ul>
 *   <li>{@link #splitWindows(List, List)}：把时间窗列表按「GLOBAL 避让时段」切分——
 *       被避让覆盖的分钟被挖掉，剩余片段成为新的独立窗口，下游放置/容量/可行性因此自动避让，
 *       无需改动任何候选搜索逻辑；</li>
 *   <li>{@link #blockedByAny(int, int, int, List)} / {@link #overlaps}：按项目（TEACHER 个人时段）
 *       或按裁判（REFEREE 个人时段）判定某个时间区间是否落入保护范围。</li>
 * </ul>
 */
public final class ProtectionMath {

    private ProtectionMath() {
    }

    /** 解析 "HH:mm" → 当日分钟；非法返回 -1 */
    public static int toMin(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return -1;
        String[] p = hhmm.split(":");
        if (p.length < 2) return -1;
        try {
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** [startMin,endMin) 在第 day 天是否与某保护时段重叠（day=null 的保护对所有天生效） */
    public static boolean overlaps(int day, int startMin, int endMin, AdminTimeProtection p) {
        if (p == null || !Boolean.TRUE.equals(p.getEnabled())) return false;
        if (p.getDay() != null && p.getDay() != day) return false;
        int ps = toMin(p.getStartTime());
        int pe = toMin(p.getEndTime());
        if (ps < 0 || pe < 0 || pe <= ps) return false;
        return startMin < pe && ps < endMin;
    }

    /** 区间是否被任一保护阻挡 */
    public static boolean blockedByAny(int day, int startMin, int endMin, List<AdminTimeProtection> blocks) {
        if (blocks == null || blocks.isEmpty()) return false;
        for (AdminTimeProtection p : blocks) {
            if (overlaps(day, startMin, endMin, p)) return true;
        }
        return false;
    }

    /** 把时间窗列表按 GLOBAL 避让时段切分（挖掉受保护分钟，产出剩余片段） */
    public static List<Window> splitWindows(List<Window> windows, List<AdminTimeProtection> blocks) {
        if (blocks == null || blocks.isEmpty() || windows == null) return windows;
        List<Window> out = new ArrayList<>();
        for (Window w : windows) {
            List<int[]> cuts = new ArrayList<>();
            for (AdminTimeProtection p : blocks) {
                if (p.getDay() != null && p.getDay() != w.day) continue;
                int ps = toMin(p.getStartTime());
                int pe = toMin(p.getEndTime());
                if (ps < 0 || pe < 0 || pe <= ps) continue;
                int cs = Math.max(ps, w.startMinute);
                int ce = Math.min(pe, w.startMinute + w.capacity);
                if (cs < ce) cuts.add(new int[]{cs, ce});
            }
            if (cuts.isEmpty()) {
                out.add(w);
                continue;
            }
            cuts.sort(Comparator.comparingInt(a -> a[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] c : cuts) {
                if (merged.isEmpty() || c[0] > merged.get(merged.size() - 1)[1]) {
                    merged.add(c);
                } else {
                    merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], c[1]);
                }
            }
            int cur = w.startMinute;
            for (int[] m : merged) {
                if (m[0] > cur) out.add(new Window(w.day, w.date, w.slotName, cur, m[0] - cur));
                cur = Math.max(cur, m[1]);
            }
            int we = w.startMinute + w.capacity;
            if (cur < we) out.add(new Window(w.day, w.date, w.slotName, cur, we - cur));
        }
        return out;
    }
}
