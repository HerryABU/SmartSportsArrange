package com.sports.schedule.core;

/**
 * 候选位置：某槽位 × 某窗口 × 某起点，及把该单元放在此处的兼项冲突条数。
 *
 * <p>由 {@link SchedulePlacementMath#findBestSlot} 产出，随后被编排层
 * （{@code com.sports.service.ScheduleService#placeOne} / {@code placeBatch}）
 * 跨包读取其字段以落位，故字段与构造器均须为 public。</p>
 */
public class Cand {

    public final int slotIdx;
    public final int windowIdx;
    public final int startMinute;
    public final int conflicts;

    public Cand(int slotIdx, int windowIdx, int startMinute, int conflicts) {
        this.slotIdx = slotIdx;
        this.windowIdx = windowIdx;
        this.startMinute = startMinute;
        this.conflicts = conflicts;
    }
}
