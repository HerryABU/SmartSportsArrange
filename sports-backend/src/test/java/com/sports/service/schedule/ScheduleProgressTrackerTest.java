package com.sports.service.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排进度表测试。
 *
 * <p>重点不是「能存能取」，而是三条**契约**：</p>
 * <ol>
 *   <li>百分比**单调不减**——编排中途做回退性打点（如从 65% 的精修回到 35% 的重试）不能让进度条倒退，
 *       否则前端看起来像「进度回滚」，比没有进度更令人困惑；</li>
 *   <li>**未绑定即静默**——同步调用路径完全不感知进度机制，不得因新增能力而变脆；</li>
 *   <li>**失败可归因**——失败任务必须留下 error 文本，供前端直接展示。</li>
 * </ol>
 */
class ScheduleProgressTrackerTest {

    @Test
    @DisplayName("生命周期：开始 → 打点 → 成功，百分比单调不减")
    void lifecycle() {
        ScheduleProgressTracker tracker = new ScheduleProgressTracker();
        String id = tracker.begin("赛程自动编排");

        ScheduleProgressTracker.Snapshot s0 = tracker.get(id).orElseThrow();
        assertEquals(0, s0.percent());
        assertFalse(s0.done());
        assertFalse(s0.failed());

        tracker.bind(id);
        tracker.stage("求解", 35, "约束求解中");
        ScheduleProgressTracker.Snapshot s1 = tracker.get(id).orElseThrow();
        assertEquals("求解", s1.stage());
        assertEquals(35, s1.percent());
        assertEquals("约束求解中", s1.message());

        // 回退性打点不得让进度倒退（重试场景会出现）
        tracker.stage("求解重试", 20, "回退重排");
        assertEquals(35, tracker.get(id).orElseThrow().percent(), "百分比必须单调不减");
        assertEquals("求解重试", tracker.get(id).orElseThrow().stage(), "阶段文案应更新");

        tracker.unbind();
        tracker.succeed(id, Map.of("total", 3));
        ScheduleProgressTracker.Snapshot done = tracker.get(id).orElseThrow();
        assertTrue(done.done());
        assertEquals(100, done.percent());
        assertEquals(3, ((Map<?, ?>) done.result()).get("total"));
    }

    @Test
    @DisplayName("失败：标记 failed 并保留可展示的 error")
    void failureKeepsError() {
        ScheduleProgressTracker tracker = new ScheduleProgressTracker();
        String id = tracker.begin("编排");
        tracker.bind(id);
        tracker.stage("求解", 40, "…");
        tracker.fail(id, "约束求解器异常");
        tracker.unbind();

        ScheduleProgressTracker.Snapshot s = tracker.get(id).orElseThrow();
        assertTrue(s.failed());
        assertFalse(s.done());
        assertEquals("约束求解器异常", s.error());
        assertEquals("失败", s.stage());
    }

    @Test
    @DisplayName("未绑定线程时打点静默：同步编排路径零影响")
    void markWithoutBindingIsSilent() {
        ScheduleProgressTracker tracker = new ScheduleProgressTracker();
        String id = tracker.begin("编排");
        // 不 bind：该线程没有当前任务，打点必须什么都不做（更不得抛异常）
        assertDoesNotThrow(() -> tracker.stage("求解", 50, "无任务"));
        assertEquals(0, tracker.get(id).orElseThrow().percent());
        assertFalse(tracker.active());
        // 静态入口在未注册/未绑定时同样静默——这正是 ScheduleService 敢直接调它的前提
        assertDoesNotThrow(() -> ScheduleProgressTracker.mark("求解", 50, "无任务"));
    }

    @Test
    @DisplayName("对不存在的任务打点不抛异常，查询返回空")
    void unknownTaskIsSafe() {
        ScheduleProgressTracker tracker = new ScheduleProgressTracker();
        tracker.bind("not-exist");
        assertDoesNotThrow(() -> tracker.stage("x", 10, "y"));
        assertDoesNotThrow(() -> tracker.succeed("not-exist", Map.of()));
        assertDoesNotThrow(() -> tracker.fail("not-exist", "e"));
        tracker.unbind();
        assertTrue(tracker.get("not-exist").isEmpty());
        assertTrue(tracker.get(null).isEmpty());
    }

    @Test
    @DisplayName("recent 返回任务列表；任务数与快照字段完整")
    void recentAndSnapshotFields() {
        ScheduleProgressTracker tracker = new ScheduleProgressTracker();
        String a = tracker.begin("A");
        String b = tracker.begin("B");
        assertEquals(2, tracker.size());
        assertEquals(2, tracker.recent().size());

        ScheduleProgressTracker.Snapshot s = tracker.get(b).orElseThrow();
        assertNotNull(s.taskId());
        assertEquals("B", s.title());
        assertNotNull(s.stage());
        assertNotNull(s.message());
        assertTrue(s.elapsedMs() >= 0);
        assertNull(s.error());
        assertTrue(tracker.get(a).isPresent());
    }
}
