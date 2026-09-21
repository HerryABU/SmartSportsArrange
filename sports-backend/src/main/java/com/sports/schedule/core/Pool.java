package com.sports.schedule.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 并发位池：slots 个并发槽位，槽位与场地一一对应（场地不足则复用）。
 *
 * <p>从 {@code ScheduleService} 抽出的顶层类，供编排主流程、放置与求解器装配共用。</p>
 */
public class Pool {

    public final String label;
    public final int slots;
    public final List<Cursor> cursors = new ArrayList<>();
    public final List<String> venueOf = new ArrayList<>();
    public final boolean venueShortage;

    public Pool(String label, int slots, List<String> venueNames) {
        this.label = label;
        this.slots = Math.max(1, slots);
        this.venueShortage = venueNames.size() < this.slots;
        for (int i = 0; i < this.slots; i++) {
            cursors.add(new Cursor());
            venueOf.add(venueNames.isEmpty()
                    ? "田径场"
                    : venueNames.get(i % venueNames.size()));
        }
    }

    /** 选当前推进最靠前（最空闲）的槽位 */
    int pickSlot() {
        int best = 0;
        for (int i = 1; i < cursors.size(); i++) {
            if (cursors.get(i).aheadOf(cursors.get(best))) best = i;
        }
        return best;
    }
}
