package org.common.diag;

import lombok.extern.slf4j.Slf4j;
import org.common.R;
import org.springframework.web.bind.annotation.*;

import java.lang.management.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线程诊断 Controller —— 分析当前 JVM 所有线程的状态。
 */
@Slf4j
@RestController
@RequestMapping("/diag/threads")
public class ThreadDiagnosticController {

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();

    /* ==================== ① 全景概览 ==================== */

    @GetMapping
    public R<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();

        // ── JVM 基本线程指标 ──
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("currentThreadCount", TMX.getThreadCount());
        jvm.put("peakThreadCount", TMX.getPeakThreadCount());
        jvm.put("daemonThreadCount", TMX.getDaemonThreadCount());
        jvm.put("nonDaemonThreadCount", TMX.getThreadCount() - TMX.getDaemonThreadCount());
        jvm.put("totalStartedThreadCount", TMX.getTotalStartedThreadCount());
        result.put("jvm", jvm);

        // ── 按 Thread.State 分组统计 ──
        ThreadInfo[] allThreads = TMX.dumpAllThreads(false, false);
        Map<String, Long> stateDist = Arrays.stream(allThreads)
                .collect(Collectors.groupingBy(
                        t -> t.getThreadState().name(), Collectors.counting()));
        result.put("stateDistribution", stateDist);

        // ── 按线程池前缀分组统计 ──
        result.put("poolSummary", buildPoolSummary(allThreads));

        // ── 死锁 ──
        result.put("deadlocks", detectDeadlocks());

        // ── 服务器时间 ──
        result.put("serverTime", LocalDateTime.now().toString());

        return R.ok(result);
    }

    /* ==================== ② 完整线程 Dump ==================== */

    /**
     * @param keyword 按线程名过滤（模糊匹配，忽略大小写），空则返回全部
     * @param state   按状态过滤（RUNNABLE / WAITING / BLOCKED / TIMED_WAITING）
     * @param depth   堆栈深度，默认 32
     */
    @GetMapping("/dump")
    public R<List<Map<String, Object>>> dump(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "32") int depth) {

        ThreadInfo[] allThreads = TMX.dumpAllThreads(true, true);
        List<Map<String, Object>> list = new ArrayList<>();

        for (ThreadInfo ti : allThreads) {
            if (keyword != null && !keyword.isBlank()
                    && !ti.getThreadName().toLowerCase().contains(keyword.toLowerCase())) {
                continue;
            }
            if (state != null && !state.isBlank()
                    && !ti.getThreadState().name().equalsIgnoreCase(state)) {
                continue;
            }
            list.add(threadInfoToMap(ti, depth));
        }
        // RUNNABLE 排前面，方便关注"正在干活"的线程
        list.sort(Comparator.comparing(m -> String.valueOf(m.get("state"))));
        return R.ok(list);
    }

    /* ==================== ③ 线程池分组详情 ==================== */

    @GetMapping("/pools")
    public R<Map<String, Object>> pools() {
        ThreadInfo[] allThreads = TMX.dumpAllThreads(false, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalThreads", allThreads.length);
        result.put("pools", buildPoolDetail(allThreads));
        return R.ok(result);
    }

    /* ==================== ④ 死锁检测 ==================== */

    @GetMapping("/deadlocks")
    public R<Object> deadlocks() {
        return R.ok(detectDeadlocks());
    }

    /* ==================== ⑤ Top-N CPU 线程 ==================== */

    /**
     * 按线程累计 CPU 时间降序排序，返回 Top-N。
     *
     * @param n 返回条数，默认 20
     */
    @GetMapping("/top")
    public R<List<Map<String, Object>>> top(@RequestParam(defaultValue = "20") int n) {
        if (!TMX.isThreadCpuTimeSupported()) {
            return R.fail(1500, "当前 JVM 不支持 ThreadCpuTime");
        }
        if (!TMX.isThreadCpuTimeEnabled()) {
            TMX.setThreadCpuTimeEnabled(true);
        }

        ThreadInfo[] allThreads = TMX.dumpAllThreads(false, false);
        List<Map<String, Object>> list = new ArrayList<>();

        for (ThreadInfo ti : allThreads) {
            long cpuNanos = TMX.getThreadCpuTime(ti.getThreadId());
            long userNanos = TMX.getThreadUserTime(ti.getThreadId());
            if (cpuNanos < 0) continue; // 线程已终止

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("threadId", ti.getThreadId());
            entry.put("threadName", ti.getThreadName());
            entry.put("state", ti.getThreadState().name());
            entry.put("daemon", isDaemon(ti.getThreadId()));
            entry.put("cpuTimeMs", cpuNanos / 1_000_000);
            entry.put("userTimeMs", userNanos / 1_000_000);
            entry.put("blockedCount", ti.getBlockedCount());
            entry.put("blockedTimeMs", ti.getBlockedTime());
            entry.put("waitedCount", ti.getWaitedCount());
            entry.put("waitedTimeMs", ti.getWaitedTime());
            list.add(entry);
        }

        list.sort((a, b) -> Long.compare(
                (long) b.get("cpuTimeMs"), (long) a.get("cpuTimeMs")));
        return R.ok(list.stream().limit(n).collect(Collectors.toList()));
    }

    /* ==================== 内部工具方法 ==================== */

    /**
     * 线程池分组规则：根据线程名前缀归类到已知的池。
     * <p>覆盖本系统中常见的线程池：
     * <ul>
     *   <li>Tomcat:        http-nio-*、tomcat-*</li>
     *   <li>Dubbo:         DubboServerHandler-*、dubbo-*、NettyServerWorker-*</li>
     *   <li>Kafka:         kafka-*、org.springframework.kafka-*</li>
     *   <li>HikariCP:      HikariPool-*</li>
     *   <li>Nacos:         com.alibaba.nacos.*</li>
     *   <li>Sentinel:      sentinel-*</li>
     *   <li>Netty:         nioEventLoop*、epollEventLoop*</li>
     *   <li>Spring:        scheduling-*、task-*、boundedElastic-*</li>
     *   <li>Lettuce/Redis: lettuce-*</li>
     *   <li>ZooKeeper:     ZkClient-*、main-SendThread、main-EventThread</li>
     *   <li>GC/JVM:        GC、Reference、Finalizer、Signal、Attach</li>
     *   <li>Other:         未匹配到以上分类的</li>
     * </ul>
     */
    private static final List<PoolRule> POOL_RULES = List.of(
            new PoolRule("Tomcat",          "http-nio-", "tomcat-"),
            new PoolRule("Dubbo",           "DubboServerHandler", "dubbo-", "NettyServerWorker", "DubboClientHandler", "Dubbo-"),
            new PoolRule("Kafka",           "kafka-", "org.springframework.kafka"),
            new PoolRule("HikariCP",        "HikariPool-"),
            new PoolRule("Nacos",           "com.alibaba.nacos", "nacos"),
            new PoolRule("Sentinel",        "sentinel-"),
            new PoolRule("Netty",           "nioEventLoop", "epollEventLoop", "reactor-"),
            new PoolRule("Biz-Pool",        "biz-pool-"),
            new PoolRule("Async-Pool",      "async-pool-"),
            new PoolRule("Sched-Pool",      "sched-pool-"),
            new PoolRule("Spring-Scheduler","scheduling-", "task-", "boundedElastic-"),
            new PoolRule("Lettuce-Redis",   "lettuce-", "redis-"),
            new PoolRule("ZooKeeper",       "ZkClient", "SendThread", "EventThread", "curator-"),
            new PoolRule("GC-JVM",          "GC ", "Reference Handler", "Finalizer", "Signal Dispatcher",
                    "Attach Listener", "Common-Cleaner", "Notification Thread",
                    "DestroyJavaVM", "VM ", "C1 ", "C2 ")
    );

    private record PoolRule(String poolName, String... prefixes) {
        boolean matches(String threadName) {
            for (String p : prefixes) {
                if (threadName.contains(p)) return true;
            }
            return false;
        }
    }

    private String classifyThread(String threadName) {
        for (PoolRule rule : POOL_RULES) {
            if (rule.matches(threadName)) return rule.poolName();
        }
        return "Other";
    }

    /** 线程池分组统计：每个池的线程数 + 状态分布 */
    private Map<String, Object> buildPoolSummary(ThreadInfo[] allThreads) {
        // poolName → stateCounter
        Map<String, Map<String, Long>> grouped = new TreeMap<>();
        for (ThreadInfo ti : allThreads) {
            String pool = classifyThread(ti.getThreadName());
            grouped.computeIfAbsent(pool, k -> new TreeMap<>())
                    .merge(ti.getThreadState().name(), 1L, Long::sum);
        }
        // 转换成可读格式
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            Map<String, Object> poolInfo = new LinkedHashMap<>();
            long total = entry.getValue().values().stream().mapToLong(Long::longValue).sum();
            poolInfo.put("count", total);
            poolInfo.put("states", entry.getValue());
            result.put(entry.getKey(), poolInfo);
        }
        return result;
    }

    /** 线程池分组详情：包含每个池中所有线程名 */
    private Map<String, Object> buildPoolDetail(ThreadInfo[] allThreads) {
        Map<String, List<Map<String, Object>>> grouped = new TreeMap<>();
        for (ThreadInfo ti : allThreads) {
            String pool = classifyThread(ti.getThreadName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("threadId", ti.getThreadId());
            item.put("threadName", ti.getThreadName());
            item.put("state", ti.getThreadState().name());
            item.put("daemon", isDaemon(ti.getThreadId()));
            grouped.computeIfAbsent(pool, k -> new ArrayList<>()).add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            Map<String, Object> poolInfo = new LinkedHashMap<>();
            poolInfo.put("count", entry.getValue().size());
            poolInfo.put("threads", entry.getValue());
            result.put(entry.getKey(), poolInfo);
        }
        return result;
    }

    /** 死锁检测 */
    private Object detectDeadlocks() {
        long[] deadlockedIds = TMX.findDeadlockedThreads();
        if (deadlockedIds == null || deadlockedIds.length == 0) {
            return Map.of("detected", false, "message", "未检测到死锁");
        }

        ThreadInfo[] deadlocked = TMX.getThreadInfo(deadlockedIds, true, true);
        List<Map<String, Object>> details = new ArrayList<>();
        for (ThreadInfo ti : deadlocked) {
            if (ti == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("threadId", ti.getThreadId());
            item.put("threadName", ti.getThreadName());
            item.put("state", ti.getThreadState().name());
            item.put("lockName", ti.getLockName());
            item.put("lockOwnerId", ti.getLockOwnerId());
            item.put("lockOwnerName", ti.getLockOwnerName());
            item.put("stackTrace", formatStackTrace(ti.getStackTrace()));
            details.add(item);
        }
        return Map.of("detected", true, "count", deadlockedIds.length, "threads", details);
    }

    /** 单个 ThreadInfo → Map */
    private Map<String, Object> threadInfoToMap(ThreadInfo ti, int maxDepth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threadId", ti.getThreadId());
        m.put("threadName", ti.getThreadName());
        m.put("pool", classifyThread(ti.getThreadName()));
        m.put("state", ti.getThreadState().name());
        m.put("daemon", isDaemon(ti.getThreadId()));
        m.put("priority", threadPriority(ti.getThreadId()));
        m.put("blockedCount", ti.getBlockedCount());
        m.put("waitedCount", ti.getWaitedCount());

        if (ti.getLockName() != null) {
            m.put("lockName", ti.getLockName());
            m.put("lockOwnerId", ti.getLockOwnerId());
            m.put("lockOwnerName", ti.getLockOwnerName());
        }

        // 锁监视器
        MonitorInfo[] monitors = ti.getLockedMonitors();
        if (monitors.length > 0) {
            List<String> monList = new ArrayList<>();
            for (MonitorInfo mi : monitors) {
                monList.add("locked <" + mi.getClassName() + "> at "
                        + mi.getLockedStackFrame());
            }
            m.put("lockedMonitors", monList);
        }

        // 锁同步器
        LockInfo[] syncs = ti.getLockedSynchronizers();
        if (syncs.length > 0) {
            List<String> syncList = new ArrayList<>();
            for (LockInfo li : syncs) {
                syncList.add(li.getClassName() + "@" + Integer.toHexString(li.getIdentityHashCode()));
            }
            m.put("lockedSynchronizers", syncList);
        }

        // 堆栈（限制深度）
        StackTraceElement[] fullStack = ti.getStackTrace();
        int limit = Math.min(fullStack.length, maxDepth);
        StackTraceElement[] trimmed = Arrays.copyOf(fullStack, limit);
        m.put("stackTrace", formatStackTrace(trimmed));
        if (fullStack.length > maxDepth) {
            m.put("stackTraceOmitted", fullStack.length - maxDepth);
        }
        return m;
    }

    /** StackTraceElement[] → List<String> */
    private List<String> formatStackTrace(StackTraceElement[] stackTrace) {
        List<String> lines = new ArrayList<>(stackTrace.length);
        for (StackTraceElement ste : stackTrace) {
            lines.add("at " + ste.toString());
        }
        return lines;
    }

    /** 通过 Thread.getAllStackTraces() 取到活跃线程判断 daemon / priority */
    private boolean isDaemon(long threadId) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getId() == threadId) return t.isDaemon();
        }
        return false;
    }

    private int threadPriority(long threadId) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getId() == threadId) return t.getPriority();
        }
        return -1;
    }

    @GetMapping("/threadsbak")
    public Map<String, Object> getThreadDumpbak(@RequestParam(required = false) String keyword) {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

        Map<String, Object> result = new LinkedHashMap<>();
        int total = 0;
        int matched = 0;

        StringBuilder details = new StringBuilder();

        for (ThreadInfo info : threadInfos) {
            total++;
            String threadName = info.getThreadName();
            if (keyword != null && !threadName.contains(keyword)) {
                continue;
            }
            matched++;
            details.append("──────────────────────────────────────\n");
            details.append("Thread: ").append(threadName).append("\n");
            details.append("State:  ").append(info.getThreadState()).append("\n");
            if (info.getLockName() != null) {
                details.append("Blocked on: ").append(info.getLockName()).append("\n");
            }
            if (info.getLockOwnerName() != null) {
                details.append("Lock owner: ").append(info.getLockOwnerName()).append("\n");
            }
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 15); i++) {
                details.append("    at ").append(stack[i]).append("\n");
            }
            if (stack.length > 15) {
                details.append("    ... ").append(stack.length - 15).append(" more\n");
            }
            details.append("\n");
        }

        result.put("totalThreads", total);
        result.put("matchedThreads", matched);
        result.put("keyword", keyword == null ? "(all)" : keyword);
        result.put("details", details.toString());

        log.info("[ThreadDiagnostic] keyword={}, totalThreads={}, matchedThreads={}", keyword, total, matched);
        return result;
    }

    /**
     * GET /diagnostic/deadlocks
     */
    @GetMapping("/deadlocksbak")
    public Map<String, Object> detectDeadlocksbak() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = threadMXBean.findDeadlockedThreads();

        Map<String, Object> result = new LinkedHashMap<>();
        if (deadlockedIds == null) {
            result.put("deadlockDetected", false);
            result.put("message", "No deadlocks detected");
            return result;
        }

        result.put("deadlockDetected", true);
        result.put("deadlockedThreadCount", deadlockedIds.length);

        ThreadInfo[] deadlocked = threadMXBean.getThreadInfo(deadlockedIds, true, true);
        StringBuilder details = new StringBuilder();
        for (ThreadInfo info : deadlocked) {
            details.append("Thread: ").append(info.getThreadName())
                    .append(" | State: ").append(info.getThreadState())
                    .append(" | Blocked on: ").append(info.getLockName())
                    .append(" | Lock owner: ").append(info.getLockOwnerName())
                    .append("\n");
            for (StackTraceElement ste : info.getStackTrace()) {
                details.append("    at ").append(ste).append("\n");
            }
            details.append("\n");
        }
        result.put("details", details.toString());
        return result;
    }
}
