package org.common.thread;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程池全局注册表 —— 统一管理所有业务自建的线程池。
 * <p>
 * 功能：
 * <ul>
 *   <li>注册 / 注销 线程池实例</li>
 *   <li>按名称查询运行时指标（活跃线程数、队列深度、完成任务数等）</li>
 *   <li>运行时动态调整线程池参数（coreSize / maxSize）</li>
 *   <li>供 ThreadPoolMonitorController 暴露 REST 接口</li>
 * </ul>
 *
 * <pre>
 *   ┌──────────────────────────────┐
 *   │      ThreadPoolRegistry      │   ← 单例
 *   │  ┌─────────┬────────┬──────┐ │
 *   │  │ bizPool │asyncPool│schedPool│ │
 *   │  └─────────┴────────┴──────┘ │
 *   └─────────────┬────────────────┘
 *                 │ snapshot()
 *                 ▼
 *         MonitorController
 * </pre>
 */
@Slf4j
public class ThreadPoolRegistry {


    /** name → ThreadPoolTaskExecutor */
    private final Map<String, ThreadPoolTaskExecutor> executors = new ConcurrentHashMap<>();
    /** name → ThreadPoolTaskScheduler */
    private final Map<String, ThreadPoolTaskScheduler> schedulers = new ConcurrentHashMap<>();

    /* ==================== 注册 / 注销 ==================== */

    public void register(String name, ThreadPoolTaskExecutor executor) {
        executors.put(name, executor);
        log.info("[ThreadPool] registered executor: {} (core={}, max={}, queue={})",
                name, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
    }

    public void register(String name, ThreadPoolTaskScheduler scheduler) {
        schedulers.put(name, scheduler);
        log.info("[ThreadPool] registered scheduler: {} (poolSize={})",
                name, scheduler.getPoolSize());
    }

    public void unregister(String name) {
        executors.remove(name);
        schedulers.remove(name);
    }

    /* ==================== 查询 ==================== */

    /**
     * 获取所有线程池运行时快照
     */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();

        executors.forEach((name, exec) -> {
            var pool = exec.getThreadPoolExecutor();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", "ThreadPoolTaskExecutor");
            m.put("corePoolSize", pool.getCorePoolSize());
            m.put("maxPoolSize", pool.getMaximumPoolSize());
            m.put("activeCount", pool.getActiveCount());
            m.put("poolSize", pool.getPoolSize());
            m.put("largestPoolSize", pool.getLargestPoolSize());
            m.put("queueCapacity", exec.getQueueCapacity());
            m.put("queueSize", pool.getQueue().size());
            m.put("queueRemainingCapacity", pool.getQueue().remainingCapacity());
            m.put("completedTaskCount", pool.getCompletedTaskCount());
            m.put("taskCount", pool.getTaskCount());
            m.put("keepAliveSeconds", pool.getKeepAliveTime(java.util.concurrent.TimeUnit.SECONDS));
            m.put("isShutdown", pool.isShutdown());
            m.put("isTerminated", pool.isTerminated());
            // 队列使用率（告警依据）
            int cap = exec.getQueueCapacity();
            int used = pool.getQueue().size();
            m.put("queueUsagePercent", cap > 0 ? String.format("%.1f%%", used * 100.0 / cap) : "N/A");
            list.add(m);
        });

        schedulers.forEach((name, sched) -> {
            var pool = sched.getScheduledThreadPoolExecutor();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", "ThreadPoolTaskScheduler");
            m.put("corePoolSize", pool.getCorePoolSize());
            m.put("activeCount", pool.getActiveCount());
            m.put("poolSize", pool.getPoolSize());
            m.put("queueSize", pool.getQueue().size());
            m.put("completedTaskCount", pool.getCompletedTaskCount());
            m.put("taskCount", pool.getTaskCount());
            m.put("isShutdown", pool.isShutdown());
            list.add(m);
        });

        return list;
    }

    /**
     * 获取指定线程池的运行时快照
     */
    public Map<String, Object> snapshotOf(String name) {
        return snapshot().stream()
                .filter(m -> name.equals(m.get("name")))
                .findFirst().orElse(null);
    }

    /* ==================== 动态调参 ==================== */

    /**
     * 运行时动态调整 Executor 的 corePoolSize / maxPoolSize。
     *
     * @return 调整后的快照；池不存在返回 null
     */
    public Map<String, Object> resize(String name, Integer coreSize, Integer maxSize) {
        ThreadPoolTaskExecutor exec = executors.get(name);
        if (exec == null) return null;

        var pool = exec.getThreadPoolExecutor();
        int oldCore = pool.getCorePoolSize();
        int oldMax  = pool.getMaximumPoolSize();

        // 必须先调大 max 再调小 core，否则可能 core > max 抛异常
        if (maxSize != null && maxSize > oldMax) {
            pool.setMaximumPoolSize(maxSize);
        }
        if (coreSize != null) {
            pool.setCorePoolSize(coreSize);
        }
        if (maxSize != null && maxSize <= oldMax) {
            pool.setMaximumPoolSize(maxSize);
        }

        log.info("[ThreadPool] resized {} : core {} → {}, max {} → {}",
                name, oldCore, pool.getCorePoolSize(), oldMax, pool.getMaximumPoolSize());
        return snapshotOf(name);
    }

    /* ==================== Getter ==================== */

    public Map<String, ThreadPoolTaskExecutor> getExecutors()   { return Collections.unmodifiableMap(executors); }
    public Map<String, ThreadPoolTaskScheduler> getSchedulers() { return Collections.unmodifiableMap(schedulers); }
}
