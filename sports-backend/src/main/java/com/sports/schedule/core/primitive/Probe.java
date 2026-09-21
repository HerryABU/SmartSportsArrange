package com.sports.schedule.core.primitive;

/** 探测结果（只读，不修改游标状态）：给出某槽位最早可放位置。 */
public class Probe {

    public final int windowIdx;
    public final Slot slot;

    public Probe(int windowIdx, Slot slot) {
        this.windowIdx = windowIdx;
        this.slot = slot;
    }
}
