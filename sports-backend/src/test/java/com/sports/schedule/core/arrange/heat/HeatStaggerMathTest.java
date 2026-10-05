package com.sports.schedule.core.arrange.heat;

import com.sports.schedule.core.arrange.heat.HeatStaggerMath.HeatSlot;
import com.sports.schedule.core.arrange.heat.HeatStaggerMath.Move;
import com.sports.schedule.core.arrange.heat.HeatStaggerMath.SlotRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组次错开求解单测。
 *
 * <p>本类的价值全在「不动项目时间也能消解冲突」，因此测试重点是：
 * ① 真的错开了；② 三道红线一条都不许破；③ 本来就错得开的不许乱动
 * （乱动会把好方案改坏，而这类改动<b>不报错</b>，是最难发现的一类回归）。</p>
 */
class HeatStaggerMathTest {

    private static final int BUFFER = 15;
    private static final int PER_ROUND = 10;
    private static final int START = 9 * 60;   // 09:00

    /** 构造一个项目的某个组次：day=1，起点 START，每组 PER_ROUND 分钟，共 heatCount 组 */
    private static HeatSlot slot(long eventId, String name, int heat, int heatCount) {
        return new HeatSlot(eventId, name, "高一", "M", "final", heat, heatCount, 1, START, PER_ROUND);
    }

    private static SlotRef ref(long athleteId, String name, String classId, HeatSlot s, boolean manual) {
        return new SlotRef(athleteId, name, classId, s, manual);
    }

    @Test
    @DisplayName("核心场景：两个项目赛程行完全重叠，但把甲从第1组换到第4组即可错开")
    void staggersHeatToResolveOverlap() {
        // 甲在 A 的第 1 组（09:00-09:10）、B 的第 1 组（09:00-09:10）→ 撞
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 6), false);
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 3), false);
        Map<Long, Integer> lanes = new HashMap<>();
        lanes.put(10L, 8);
        lanes.put(20L, 8);

        List<Move> moves = HeatStaggerMath.resolve(new ArrayList<>(List.of(a, b)), lanes, BUFFER);

        assertFalse(moves.isEmpty(), "应当给出换组建议");
        Move m = moves.get(0);
        assertEquals(1, m.from().heat(), "应从当前组次换出");
        assertTrue(m.gapAfter() >= BUFFER, "换后间隔必须达到缓冲要求，实际=" + m.gapAfter());
        assertTrue(m.gapAfter() > m.gapBefore(), "换后间隔必须比换前更宽");
        // 择优判据是「换后间隔最大」：A 共 6 组、每组 10 分钟，与 B 第1组(09:00-09:10)
        // 间隔最大的是最后一组（09:50-10:00，间隔 40 分钟）
        assertEquals(6, m.to().heat(), "应选间隔最大的目标组次");
    }

    @Test
    @DisplayName("换组必须真消解：错开一格仍不足缓冲时，不得采纳（否则白改一次组次）")
    void refusesMoveThatDoesNotReachBuffer() {
        // A 共 2 组（09:00-09:10 / 09:10-09:20），B 第1组 09:00-09:10。
        // 换到 A 第2组后间隔仅 0 分钟 < 15 → 无合法解，必须如实返回「无解」而不是硬改。
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 2), false);
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 2), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, b)), new HashMap<>(), BUFFER);

        assertTrue(moves.isEmpty(), "换不开就该保持原样（组次已定，乱改比不改更糟）");
    }

    @Test
    @DisplayName("本来就错得开 → 一动不动（乱动会把好方案改坏且不报错）")
    void leavesNonConflictingAlone() {
        // 甲在 A 的第 5 组（09:40-09:50）、B 的第 1 组（09:00-09:10）→ 间隔 30 分钟，不撞
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 5, 6), false);
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 3), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, b)), new HashMap<>(), BUFFER);

        assertTrue(moves.isEmpty(), "无冲突时不得换组，实际=" + moves);
    }

    @Test
    @DisplayName("红线①：目标组次有同班同学 → 跳过该候选（不能靠制造新违规换冲突）")
    void refusesSameClassTarget() {
        // 甲(c1) 在 A 第1组；A 第2组已有乙(c1) 同班 → 不能把甲换进第2组
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 3), false);
        SlotRef sameClassInHeat2 = ref(2L, "乙", "c1", slot(10L, "100米", 2, 3), false);
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 3), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, sameClassInHeat2, b)), new HashMap<>(), BUFFER);

        for (Move m : moves) {
            assertFalse(m.to().heat() == 2 && m.classId().equals("c1"),
                    "不得把 c1 的运动员换进已有 c1 同学的组次");
        }
    }

    @Test
    @DisplayName("红线②：目标组次人数已达并道数 → 不可换入")
    void refusesOverCapacityHeat() {
        // 并道数 2：A 只有 2 组，第 2 组已被甲乙占满 → 只能换到……无处可换
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 2), false);
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 2), false);
        SlotRef other = ref(2L, "乙", "c2", slot(10L, "100米", 2, 2), false);
        Map<Long, Integer> lanes = new HashMap<>();
        lanes.put(10L, 2);
        lanes.put(20L, 2);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, other, b)), lanes, BUFFER);

        // A 侧无处可换；B 侧同理（并道 2、2 组各 1 人 + 甲占 1 组 = 满）→ 只能不改
        assertTrue(moves.isEmpty(), "所有目标组次都满员时不得硬换，实际=" + moves);
    }

    @Test
    @DisplayName("红线③：人工锁定的编排项不动（那是编排者明确指定的位置）")
    void neverMovesManualArrangement() {
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 3), true);   // 人工锁定
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 3), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, b)), new HashMap<>(), BUFFER);

        for (Move m : moves) {
            assertFalse(m.from().eventId() == 10L, "人工锁定项不得被移动");
        }
    }

    @Test
    @DisplayName("换后不得与其余项目产生新冲突（只看刚冲突的那一对是不够的）")
    void avoidsCreatingNewClash() {
        // 甲：A 第1组(09:00-09:10)、B 第1组(09:00-09:10)、C 第3组(09:20-09:30)
        // 换到 A 第4组(09:30-09:40) 会与 C 第3组 撞 → 必须避开，改选别处或不动
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 6), false);
        SlotRef b = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 3), false);
        SlotRef c = ref(1L, "甲", "c1", slot(30L, "铅球", 3, 3), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, b, c)), new HashMap<>(), BUFFER);

        for (Move m : moves) {
            if (m.to().eventId() != 10L) continue;
            int toStart = m.to().start(), toEnd = m.to().end();
            int cStart = c.slot().start(), cEnd = c.slot().end();
            int gap = Math.max(cStart - toEnd, toStart - cEnd);
            assertTrue(gap >= BUFFER, "换后不得与 C 撞车，实际间隔 " + gap);
        }
    }

    @Test
    @DisplayName("clash：不同天永不冲突；对称间隔口径与 ConflictMath 一致")
    void clashSemantics() {
        HeatSlot x = slot(10L, "A", 1, 3);
        HeatSlot y = new HeatSlot(20L, "B", "高一", "M", "final", 1, 3, 2, START, PER_ROUND);
        assertFalse(HeatStaggerMath.clash(x, y, BUFFER), "不同天不该判冲突");

        // 同天、同起点 → 重叠
        assertTrue(HeatStaggerMath.clash(x, x, BUFFER));
        // 相邻两组紧贴：09:00-09:10 与 09:10-09:20 → 间隔 0 < 15 → 冲突
        HeatSlot next = slot(20L, "B", 2, 3);
        assertTrue(HeatStaggerMath.clash(x, next, BUFFER));
        // 隔两组：09:00-09:10 与 09:20-09:30 → 间隔 10 < 15 → 仍冲突
        HeatSlot third = slot(20L, "B", 3, 3);
        assertTrue(HeatStaggerMath.clash(x, third, BUFFER));
        // 隔三组：09:00-09:10 与 09:30-09:40 → 间隔 20 ≥ 15 → 不冲突
        HeatSlot fourth = new HeatSlot(20L, "B", "高一", "M", "final", 4, 4, 1, START, PER_ROUND);
        assertFalse(HeatStaggerMath.clash(x, fourth, BUFFER));
    }

    @Test
    @DisplayName("同一项目同一赛次的不同组次不算兼项冲突（它们本就在同一条赛程行里）")
    void sameEventSameRoundNotClash() {
        SlotRef a = ref(1L, "甲", "c1", slot(10L, "100米", 1, 3), false);
        SlotRef b = ref(1L, "甲", "c1", slot(10L, "100米", 2, 3), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a, b)), new HashMap<>(), BUFFER);

        assertTrue(moves.isEmpty(), "同项目不同组次不得被当成兼项冲突来「错开」");
    }

    @Test
    @DisplayName("多运动员互不影响：只动真正撞车的那一位")
    void onlyMovesTheClashingAthlete() {
        // 乙 本来就错得开；甲 撞车 → 只应换甲
        SlotRef a1 = ref(1L, "甲", "c1", slot(10L, "100米", 1, 3), false);
        SlotRef a2 = ref(1L, "甲", "c1", slot(20L, "跳远", 1, 3), false);
        SlotRef b1 = ref(2L, "乙", "c2", slot(10L, "100米", 3, 3), false);
        SlotRef b2 = ref(2L, "乙", "c2", slot(20L, "跳远", 3, 3), false);

        List<Move> moves = HeatStaggerMath.resolve(
                new ArrayList<>(List.of(a1, a2, b1, b2)), new HashMap<>(), BUFFER);

        assertNotNull(moves);
        for (Move m : moves) {
            assertEquals(1L, m.athleteId(), "只应移动撞车的甲，乙不该被动");
        }
    }
}
