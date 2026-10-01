package com.sports.service.schedule;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排进度表——让「长时间编排」对操作者**可见**。
 *
 * <p>背景：赛程自动编排要跑「构造启发式 + 算法组合波次 + GA/LNS/MNSA/ALNS/Fix-opt 精修链 +
 * 对抗式自检」，真实学校规模下是**秒级到十秒级**的阻塞操作。前端只能干转 loading，
 * 既不知道跑到哪一步，也不知道是不是卡死了。</p>
 *
 * <p>做法：编排主流程在关键节点调用 {@link #stage} 打点；异步接口先用 {@link #begin}
 * 开一个任务并返回 taskId，前端轮询 {@link #get}。任务与线程通过 {@link ThreadLocal} 绑定，
 * 因此<b>同步调用路径零改动零开销</b>——没有绑定任务时打点直接返回，不产生任何副作用
 * （与「模型缺失即回退」同一设计原则：新能力不得让老路径变脆）。</p>
 *
 * <p>内存表按 TTL 与容量上限清理，避免长跑进程里无限增长。</p>
 */
@Slf4j
@Component
public class ScheduleProgressTracker {

    /** 保留最近任务数上限（超出时淘汰最旧的已完成任务）。 */
    private static final int MAX_TASKS = 40;
    /** 任务保留时长（毫秒）：超过后从表中清除。 */
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    /** 一次编排的进度快照（前端轮询的返回体）。 */
    public record Snapshot(String taskId, String title, String stage, int percent, String message,
                           boolean done, boolean failed, long startedAt, long elapsedMs,
                           Map<String, Object> result, String error) {
    }

    private static final class Task {
        final String id;
        final String title;
        final long startedAt;
        volatile String stage = "排队中";
        volatile int percent = 0;
        volatile String message = "等待执行";
        volatile long updatedAt;
        volatile boolean done = false;
        volatile boolean failed = false;
        volatile Map<String, Object> result;
        volatile String error;

        Task(String id, String title) {
            this.id = id;
            this.title = title;
            this.startedAt = System.currentTimeMillis();
            this.updatedAt = this.startedAt;
        }
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentTask = new ThreadLocal<>();
    private final AtomicLong seq = new AtomicLong();

    // ------------------------------------------------------------------
    // 静态便捷入口
    //
    // ScheduleService 是**手工构造**的（new 出来的组件），给它加构造参数会连带改
    // 构造签名 + 4 个 @InjectMocks 测试类。而进度打点只是「有则记、无则跳过」的旁路能力，
    // 不值得为此改动主链路的装配方式——因此这里提供静态入口：Spring 装配时把自身注册进来，
    // 主链路直接 ScheduleProgressTracker.mark(...) 即可，零依赖、零构造改动、单测下静默无害。
    // ------------------------------------------------------------------

    private static volatile ScheduleProgressTracker instance;

    @PostConstruct
    void registerInstance() {
        instance = this;
        log.debug("编排进度表已注册");
    }

    /** 静态打点（未注册时静默无操作）。 */
    public static void mark(String stage, int percent, String message) {
        ScheduleProgressTracker t = instance;
        if (t != null) {
            t.stage(stage, percent, message);
        }
    }

    /** 当前线程是否在任务中（静态版）。 */
    public static boolean marking() {
        ScheduleProgressTracker t = instance;
        return t != null && t.active();
    }

    // ------------------------------------------------------------------
    // 任务生命周期
    // ------------------------------------------------------------------

    /** 开一个任务并返回 taskId（此时尚未绑定线程，由执行线程调用 {@link #bind}）。 */
    public String begin(String title) {
        purgeExpired();
        String id = "sch-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        tasks.put(id, new Task(id, title == null ? "赛程编排" : title));
        return id;
    }

    /** 把当前线程绑定到某个任务（异步执行线程进入时调用）。 */
    public void bind(String taskId) {
        currentTask.set(taskId);
    }

    /** 解绑（异步执行线程退出时必须调用，避免线程池复用导致串任务）。 */
    public void unbind() {
        currentTask.remove();
    }

    /** 当前线程是否绑定到任务（供主流程判断是否需要打点，避免无谓构造字符串）。 */
    public boolean active() {
        return currentTask.get() != null;
    }

    /**
     * 打点：更新当前绑定任务的阶段与百分比。
     *
     * <p>没有绑定任务（同步调用）时**静默返回**——这是「新能力零侵入老路径」的关键。</p>
     */
    public void stage(String stage, int percent, String message) {
        String id = currentTask.get();
        if (id == null) {
            return;
        }
        Task t = tasks.get(id);
        if (t == null || t.done || t.failed) {
            return;
        }
        t.stage = stage == null ? t.stage : stage;
        t.percent = Math.max(t.percent, Math.min(100, Math.max(0, percent)));
        if (message != null) {
            t.message = message;
        }
        t.updatedAt = System.currentTimeMillis();
    }

    /** 标记成功（可带结果摘要，供前端完成后直接展示，省一次请求）。 */
    public void succeed(String taskId, Map<String, Object> result) {
        Task t = tasks.get(taskId);
        if (t == null) {
            return;
        }
        t.percent = 100;
        t.stage = "完成";
        t.message = "编排完成";
        t.done = true;
        t.result = result;
        t.updatedAt = System.currentTimeMillis();
    }

    /** 标记失败。 */
    public void fail(String taskId, String error) {
        Task t = tasks.get(taskId);
        if (t == null) {
            return;
        }
        t.failed = true;
        t.stage = "失败";
        t.message = error == null ? "编排失败" : error;
        t.error = t.message;
        t.updatedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Optional<Snapshot> get(String taskId) {
        Task t = taskId == null ? null : tasks.get(taskId);
        return t == null ? Optional.empty() : Optional.of(snapshot(t));
    }

    /** 最近的任务（新的在前），供前端「历史任务」或排障使用。 */
    public List<Snapshot> recent() {
        List<Task> list = new ArrayList<>(tasks.values());
        list.sort(Comparator.comparingLong((Task t) -> t.startedAt).reversed());
        return list.stream().limit(MAX_TASKS).map(ScheduleProgressTracker::snapshot).toList();
    }

    private static Snapshot snapshot(Task t) {
        long now = t.done || t.failed ? t.updatedAt : System.currentTimeMillis();
        return new Snapshot(t.id, t.title, t.stage, t.percent, t.message,
                t.done, t.failed, t.startedAt, Math.max(0, now - t.startedAt),
                t.result, t.error);
    }

    /** 清理过期与超量任务。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(e -> {
            Task t = e.getValue();
            boolean finished = t.done || t.failed;
            return finished && now - t.updatedAt > TTL_MILLIS;
        });
        if (tasks.size() <= MAX_TASKS) {
            return;
        }
        List<Task> finished = tasks.values().stream()
                .filter(t -> t.done || t.failed)
                .sorted(Comparator.comparingLong((Task t) -> t.startedAt))
                .toList();
        for (Task t : finished) {
            if (tasks.size() <= MAX_TASKS) {
                break;
            }
            tasks.remove(t.id);
        }
    }

    /** 便于测试断言：当前在册任务数。 */
    public int size() {
        return tasks.size();
    }
}
