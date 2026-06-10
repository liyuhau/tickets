package org.common.thread;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一线程池参数（可通过 Nacos 动态下发 / application.yaml 覆盖）。
 *
 * <pre>
 * thread-pool:
 *   biz:                           # 核心业务线程池
 *     core-size: 8
 *     max-size: 32
 *     queue-capacity: 256
 *     keep-alive-seconds: 60
 *     thread-name-prefix: biz-pool-
 *   async:                         # @Async 异步线程池
 *     core-size: 4
 *     max-size: 16
 *     queue-capacity: 512
 *     keep-alive-seconds: 120
 *     thread-name-prefix: async-pool-
 *   schedule:                      # @Scheduled 定时任务线程池
 *     core-size: 4
 *     thread-name-prefix: sched-pool-
 * </pre>
 */
@ConfigurationProperties(prefix = "thread-pool")
public class ThreadPoolProperties {

    /** 核心业务线程池（订单创建、库存扣减、CDC 同步等重要并行任务） */
    private PoolDef biz = new PoolDef(8, 32, 256, 60, "biz-pool-");

    /** 异步线程池（@Async、事件发布、日志上报等轻量异步任务） */
    private PoolDef async = new PoolDef(4, 16, 512, 120, "async-pool-");

    /** 定时任务线程池（@Scheduled、对账、补偿等周期性任务） */
    private ScheduleDef schedule = new ScheduleDef(4, "sched-pool-");

    public PoolDef getBiz()   { return biz; }
    public void setBiz(PoolDef biz) { this.biz = biz; }

    public PoolDef getAsync() { return async; }
    public void setAsync(PoolDef async) { this.async = async; }

    public ScheduleDef getSchedule() { return schedule; }
    public void setSchedule(ScheduleDef schedule) { this.schedule = schedule; }

    /* ==================== 内部模型 ==================== */

    /**
     * 通用线程池参数定义
     */
    public static class PoolDef {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private int keepAliveSeconds;
        private String threadNamePrefix;

        public PoolDef() {}

        public PoolDef(int coreSize, int maxSize, int queueCapacity,
                       int keepAliveSeconds, String threadNamePrefix) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
            this.keepAliveSeconds = keepAliveSeconds;
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCoreSize()          { return coreSize; }
        public void setCoreSize(int v)    { this.coreSize = v; }
        public int getMaxSize()           { return maxSize; }
        public void setMaxSize(int v)     { this.maxSize = v; }
        public int getQueueCapacity()     { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
        public int getKeepAliveSeconds()  { return keepAliveSeconds; }
        public void setKeepAliveSeconds(int v) { this.keepAliveSeconds = v; }
        public String getThreadNamePrefix() { return threadNamePrefix; }
        public void setThreadNamePrefix(String v) { this.threadNamePrefix = v; }
    }

    /**
     * 定时任务线程池参数定义（仅需 coreSize，无最大值 / 队列概念）
     */
    public static class ScheduleDef {
        private int coreSize;
        private String threadNamePrefix;

        public ScheduleDef() {}

        public ScheduleDef(int coreSize, String threadNamePrefix) {
            this.coreSize = coreSize;
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCoreSize()          { return coreSize; }
        public void setCoreSize(int v)    { this.coreSize = v; }
        public String getThreadNamePrefix() { return threadNamePrefix; }
        public void setThreadNamePrefix(String v) { this.threadNamePrefix = v; }
    }
}
