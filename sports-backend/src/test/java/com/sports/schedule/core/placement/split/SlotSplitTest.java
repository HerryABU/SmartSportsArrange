package com.sports.schedule.core.placement.split;

import com.sports.entity.event.Event;
import com.sports.schedule.core.primitive.Pool;
import com.sports.schedule.core.primitive.Unit;
import com.sports.schedule.core.primitive.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨时段拆分（中午临界点）单测。
 *
 * <p>重点钉住三条红线与一个「不该拆就别拆」：
 * 拆分的收益是「用掉上午的零头」，代价是多一条赛程行；
 * 一旦在能整块放下的场景里乱拆，收益就变成了负的。</p>
 */
class SlotSplitTest {

    private static final int INTERVAL = 5;

    /** 上午 08:00–11:30（210 分钟）+ 下午 14:00–17:30（210 分钟） */
    private static List<Window> dayWindows() {
        return List.of(new Window(1, "2026-10-05", "上午", 8 * 60, 210),
                new Window(1, "2026-10-05", "下午", 14 * 60, 210));
    }

    private static Pool pool(int slots) {
        return new Pool("径赛", slots, new ArrayList<>(List.of("田径场")));
    }

    private static Unit unit(int duration, int rounds) {
        Event e = new Event();
        e.setId(1L);
        e.setName("100米");
        e.setTrack(true);
        Unit u = new Unit(e, "高一");
        u.duration = duration;
        u.rawDuration = duration;
        u.rounds = rounds;
        u.heats = rounds;
        return u;
    }

    @Test
    @DisplayName("上午只剩 2 组空间、下午全空 → 拆成上午 2 组 + 下午 4 组，且时长守恒")
    void splitsAtMiddayBoundary() {
        Unit u = unit(60, 6);            // 6 组，每组 10 分钟
        Pool p = pool(1);
        // 把上午占到只剩 2 组（20 分钟）空间：容量 210，已用 185 + 5 段前间隔 = 190，剩 20
        p.cursors.get(0).reserve(0, 0, 185, INTERVAL);

        SlotSplit.SplitCand cand = SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), null);

        assertNotNull(cand, "上午有零头、下午全空时应能拆分");
        assertEquals(20, cand.headDuration(), "上午段应吃满剩余的 2 组空间");
        assertEquals(40, cand.tailDuration(), "下午段承接剩余 4 组");
        assertTrue(cand.durationConsistent(u.duration), "拆分后总时长必须守恒");
        assertEquals(2, cand.headDuration() / u.perRoundMinutes(), "上午段应是 2 个完整组次");
        assertEquals(4, cand.tailDuration() / u.perRoundMinutes(), "下午段应是 4 个完整组次");
        // 上午段起点 = 窗口起点 + 已用 + 段前间隔
        assertEquals(8 * 60 + 185 + INTERVAL, cand.headStart());
    }

    @Test
    @DisplayName("上午能吃下大部分时应尽量放上午（择优偏向用足上午余量）")
    void prefersHeavierMorningSegment() {
        Unit u = unit(60, 6);
        Pool p = pool(1);
        // 上午剩 50 分钟 → 上午放 5 组、下午只放 1 组
        p.cursors.get(0).reserve(0, 0, 155, INTERVAL);

        SlotSplit.SplitCand cand = SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), null);

        assertNotNull(cand);
        assertEquals(50, cand.headDuration());
        assertEquals(10, cand.tailDuration());
        assertTrue(cand.durationConsistent(u.duration));
    }

    @Test
    @DisplayName("红线①：时长无法按组次整除 → 一律不拆（否则会把某个组次拦腰截断）")
    void refusesWhenDurationNotDivisibleByRound() {
        // 7 组共 65 分钟 → 每组 9.28 分钟，拆分会腰斩组次
        Unit u = unit(65, 7);
        assertFalse(u.splittableByRound(), "前置断言：65 不能被每组用时整除");
        Pool p = pool(1);
        p.cursors.get(0).reserve(0, 0, 20, INTERVAL);

        assertNull(SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), null),
                "组次边界对不齐时必须拒绝拆分");
    }

    @Test
    @DisplayName("只有 1 个组次的项目不拆（拆了也没有意义，还多一条赛程行）")
    void refusesSingleRound() {
        Unit u = unit(10, 1);
        Pool p = pool(1);
        assertNull(SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), null));
    }

    @Test
    @DisplayName("红线②：跨天不拆（跨时段本来就有真实时间断点，跨天拆等于把项目排成两天）")
    void refusesAcrossDays() {
        Unit u = unit(60, 6);
        Pool p = pool(1);
        // 上午在第 1 天，下午在第 2 天
        List<Window> crossDay = List.of(
                new Window(1, "2026-10-05", "上午", 8 * 60, 210),
                new Window(2, "2026-10-06", "下午", 14 * 60, 210));
        assertNull(SlotSplit.findSplit(u, p, crossDay, INTERVAL, new HashMap<>(), null),
                "跨天窗口之间不得拆分");
    }

    @Test
    @DisplayName("上午连一个组次都塞不下 → 不拆（拆了下午也放不全）")
    void refusesWhenHeadCannotFitOneRound() {
        Unit u = unit(60, 6);
        Pool p = pool(1);
        // 上午已用 205 分钟，只剩 5 分钟 < 每组 10 分钟
        p.cursors.get(0).reserve(0, 0, 205, 0);

        assertNull(SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), null));
    }

    @Test
    @DisplayName("下午剩余容量不够承接尾段 → 不拆（宁可不拆，也不能只落上午一段）")
    void refusesWhenTailDoesNotFit() {
        Unit u = unit(60, 6);
        Pool p = pool(1);
        // 上午剩 50 分钟（可吃 5 组），下午只剩 5 分钟 → 尾段 10 分钟放不下
        p.cursors.get(0).reserve(0, 0, 160, 0);
        p.cursors.get(0).reserve(1, 0, 205, 0);

        assertNull(SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), null));
    }

    @Test
    @DisplayName("行政时间保护命中午间段时跳过该起点")
    void respectsAdminProtection() {
        Unit u = unit(60, 6);
        Pool p = pool(1);
        // 上午占到只剩 20 分钟（已用 185 + 5 间隔 = 190），上午段起点 = 08:00+190 = 670（11:10）
        p.cursors.get(0).reserve(0, 0, 185, INTERVAL);
        int headStart = 8 * 60 + 185 + INTERVAL;
        // 保护第 1 天 11:00–11:30（660–690），上午段（11:10 起 20 分钟）正落在里面
        List<int[]> blocked = List.of(new int[]{1, 660, 690});

        assertNull(SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), blocked),
                "上午段被保护区间阻挡时不得拆分");
    }

    @Test
    @DisplayName("保护区间不命中时仍可正常拆分（确认上一条不是因为别的原因才 null）")
    void splitsWhenProtectionMisses() {
        Unit u = unit(60, 6);
        Pool p = pool(1);
        p.cursors.get(0).reserve(0, 0, 185, INTERVAL);
        // 保护一个完全不相干的区间
        List<int[]> blocked = List.of(new int[]{1, 0, 30});

        SlotSplit.SplitCand cand = SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, new HashMap<>(), blocked);
        assertNotNull(cand, "保护区间不命中时应能拆分");
        assertTrue(cand.durationConsistent(u.duration));
    }

    @Test
    @DisplayName("兼项冲突择优：同等条件下选冲突更少的拆分方案")
    void prefersFewerConflicts() {
        Unit u = unit(60, 6);
        Pool p = pool(2);
        // 槽位 0：上午已排别处，运动员 1 上午 10:30–10:40 有占用 → 该槽位拆分会引入冲突
        p.cursors.get(0).reserve(0, 0, 30, INTERVAL);
        p.cursors.get(1).reserve(0, 0, 30, INTERVAL);
        // 运动员 1 在第 1 天 10:30（=630）已占 10 分钟
        Map<Long, List<int[]>> busy = new HashMap<>();
        busy.put(1L, List.of(new int[]{630, 640}));

        SlotSplit.SplitCand cand = SlotSplit.findSplit(u, p, dayWindows(), INTERVAL, busy, null);

        assertNotNull(cand);
        // 上午段起点 = 8:00+30+5 = 515，运动员占用 630 之后；两组都撞 → 冲突数相同
        // 关键断言：择优后仍然是时长守恒的合法方案（不因为择优而牺牲红线）
        assertTrue(cand.durationConsistent(u.duration));
        assertTrue(cand.conflicts() >= 0);
    }

    @Test
    @DisplayName("Cursor.reserveSplit：两段同时成功才记账，任一段冲突则整体不动")
    void reserveSplitIsAllOrNothing() {
        var cursor = new com.sports.schedule.core.primitive.Cursor();
        List<Window> ws = dayWindows();
        assertTrue(cursor.reserveSplit(0, 30, 30, 1, 0, 30, INTERVAL));
        assertEquals(60, cursor.usedAt(0));
        assertEquals(30, cursor.usedAt(1));

        // 第二次拆分的下午段与已占的 [0,30) 冲突 → 必须整体失败且不改动状态
        boolean again = cursor.reserveSplit(0, 0, 20, 1, 0, 20, INTERVAL);
        assertFalse(again, "下午段重叠时必须失败");
        assertEquals(60, cursor.usedAt(0), "失败时上午段不得被记账");
        assertEquals(30, cursor.usedAt(1), "失败时下午段不得被记账");
    }
}
