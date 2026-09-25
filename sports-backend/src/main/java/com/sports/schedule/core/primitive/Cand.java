package com.sports.schedule.core.primitive;
import com.sports.schedule.core.math.SchedulePlacementMath;
import com.sports.service.schedule.ScheduleService;

/**
 * 候选位置：某槽位 × 某窗口 × 某起点，及把该单元放在此处的兼项冲突条数与到相邻已排占用的最小间隔。
 *
 * <p>由 {@link SchedulePlacementMath#findBestSlot} 产出，随后被编排层
 * （{@code com.sports.service.schedule.ScheduleService#placeOne} / {@code placeBatch}）
 * 跨包读取其字段以落位，故字段与构造器均须为 public。</p>
 */
public class Cand {

    public final int slotIdx;
    public final int windowIdx;
    public final int startMinute;
    public final int conflicts;
    /** 到该单元运动员最近一条同天已排占用的最小间隔（分钟）；整段空闲时为极大值 */
    public final int gap;

    public Cand(int slotIdx, int windowIdx, int startMinute, int conflicts, int gap) {
        this.slotIdx = slotIdx;
        this.windowIdx = windowIdx;
        this.startMinute = startMinute;
        this.conflicts = conflicts;
        this.gap = gap;
    }
}
