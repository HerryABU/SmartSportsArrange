package com.sports.schedule.core.primitive;

/** 放置结果：某个窗口内的某个起点。 */
public class Slot {

    public Window window;
    public int startMinute;

    public Slot(Window window, int startMinute) {
        this.window = window;
        this.startMinute = startMinute;
    }
}
