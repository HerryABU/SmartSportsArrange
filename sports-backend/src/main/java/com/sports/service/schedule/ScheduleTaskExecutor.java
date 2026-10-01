package com.sports.service.schedule;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 赛程异步执行器——给「长时间编排」一个不阻塞 HTTP 线程的落脚点。
 *
 * <p>为什么不用 {@code @Async}：项目当前未开启 {@code @EnableAsync}，为一个功能全量打开异步代理
 * 会改变既有 Bean 的调用语义（自调用失效、代理额外开销、事务边界变化）。这里用一个**独立、
 * 显式、可控**的小线程池，只服务编排这一条链路，影响面最小。</p>
 *
 * <p>线程是守护线程：服务关闭时不会因为编排任务而挂住 JVM；{@link PreDestroy} 时主动中断。</p>
 */
@Slf4j
@Component
public class ScheduleTaskExecutor {

    /** 并发编排上限（编排本身是 CPU 密集型，池子开太大只会互相抢核）。 */
    private static final int POOL_SIZE = 2;

    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
        Thread t = new Thread(r, "schedule-async-" + seq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public void submit(Runnable task) {
        pool.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
        log.info("赛程异步执行器已关闭");
    }
}
