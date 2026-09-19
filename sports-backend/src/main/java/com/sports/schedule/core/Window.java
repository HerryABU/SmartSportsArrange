package com.sports.schedule.core;

/** 一个时段窗口（第几天 + 该天的某个时段）。 */
public class Window {

    public int day;
    public String date;
    public String slotName;
    /** 该时段起点（分钟，自 00:00 起算） */
    public int startMinute;
    /** 该时段可用分钟数 */
    public int capacity;

    public Window(int day, String date, String slotName, int startMinute, int capacity) {
        this.day = day;
        this.date = date;
        this.slotName = slotName;
        this.startMinute = startMinute;
        this.capacity = capacity;
    }
}
