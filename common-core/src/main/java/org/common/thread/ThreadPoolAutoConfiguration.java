package org.common.thread;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 企业级统一线程池自动装配。
 * <p>
 * 任何依赖 common-core 的微服务启动后自动注册三个线程池：
 * <ul>
 *   <li><b>bizThreadPool</b>    —— 核心业务（订单创建、CDC同步、ES写入等并行场景）</li>
 *   <li><b>asyncThreadPool</b>  —— @Async 默认执行器（事件发布、日志上报、邮件通知等）</li>
 *   <li><b>schedThreadPool</b>  —— @Scheduled 定时任务（对账、补偿、缓存刷新等）</li>
 * </ul>
 *
 * <pre>
 * ┌────────────────────────────────────────────────────────────────┐
 * │                    ThreadPoolAutoConfiguration                │
 * │                                                                │
 * │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐  │
 * │  │ bizThreadPool│  │asyncThreadPool│  │  schedThreadPool   │  │
 * │  │  core=8      │  │  core=4       │  │  core=4            │  │
 * │  │  max=32      │  │  max=16       │  │  ScheduledExecutor │  │
 * │  │  queue=256   │  │  queue=512    │  │                    │  │
 * │  └──────┬───────┘  └──────┬───────┘  └────────┬───────────┘  │
 * │         │                 │                    │              │
 * │         └─────────────────┼────────────────────┘              │
 * │                           │                                    │
 * │                  ThreadPoolRegistry（全局注册表）                │
 * │                           │                                    │
 * │                  ThreadPoolMonitorController                   │
 * │                   GET /diag/thread-pools                       │
 * └────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <b>参数可通过 Nacos / application.yaml 外部化：</b>
 * <pre>
 * thread-pool:
 *   biz:
 *     core-size: 8
 *     max-size: 32
 *     queue-capacity: 256
 * </pre>
 *
 * <b>拒绝策略：</b>CallerRunsPolicy（调用线程自己执行），保证任务不丢失。
 * <br><b>线程名规范：</b>{prefix}{序号}，便于日志排查和 {@link org.common.diag.ThreadDiagnosticController} 分组。
 */
@Slf4j
@AutoConfiguration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(ThreadPoolProperties.class)
@Import(ThreadPoolMonitorController.class)
public class ThreadPoolAutoConfiguration implements AsyncConfigurer {


    private final ThreadPoolProperties props;

    public ThreadPoolAutoConfiguration(ThreadPoolProperties props) {
        this.props = props;
    }

    /* ==================== 全局注册表 ==================== */

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolRegistry threadPoolRegistry() {
        return new ThreadPoolRegistry();
    }

    /* ==================== ① 核心业务线程池 ==================== */

    /**
     * 核心业务线程池 —— 用于 Controller/Service 层显式提交的并行任务。
     * <p>使用方式：
     * <pre>
     *   &#64;Autowired &#64;Qualifier("bizThreadPool")
     *   private ThreadPoolTaskExecutor bizPool;
     *
     *   bizPool.submit(() -&gt; syncToEs(data));
     * </pre>
     */
    @Bean(name = "bizThreadPool", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor bizThreadPool(ThreadPoolRegistry registry) {
        ThreadPoolProperties.PoolDef def = props.getBiz();
        ThreadPoolTaskExecutor executor = createExecutor(def, new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        registry.register("bizThreadPool", executor);
        log.info("[ThreadPool] bizThreadPool initialized — core={}, max={}, queue={}",
                def.getCoreSize(), def.getMaxSize(), def.getQueueCapacity());
        return executor;
    }

    /* ==================== ② @Async 异步线程池 ==================== */

    /**
     * @Async 默认执行器 —— 所有 @Async 方法若未指定 value，走此池。
     * <p>使用方式：
     * <pre>
     *   &#64;Async
     *   public void sendNotification(OrderEvent event) { ... }
     *
     *   // 或显式指定
     *   &#64;Async("asyncThreadPool")
     *   public CompletableFuture&lt;Void&gt; uploadLog(...) { ... }
     * </pre>
     */
    @Bean(name = "asyncThreadPool", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor asyncThreadPool(ThreadPoolRegistry registry) {
        ThreadPoolProperties.PoolDef def = props.getAsync();
        ThreadPoolTaskExecutor executor = createExecutor(def, new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        registry.register("asyncThreadPool", executor);
        log.info("[ThreadPool] asyncThreadPool initialized — core={}, max={}, queue={}",
                def.getCoreSize(), def.getMaxSize(), def.getQueueCapacity());
        return executor;
    }

    /** AsyncConfigurer：让 @Async 默认走 asyncThreadPool */
    @Override
    public Executor getAsyncExecutor() {
        // 不能直接调 asyncThreadPool()，需通过 ApplicationContext 获取
        // 但 Spring 会在完成注册后再调此方法，此时 Bean 已就绪
        return asyncThreadPool(threadPoolRegistry());
    }

    /** AsyncConfigurer：@Async 方法抛异常时的兜底日志 */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("[AsyncPool] 异步任务异常 method={}: {}", method.getName(), ex.getMessage(), ex);
    }

    /* ==================== ③ @Scheduled 定时任务线程池 ==================== */

    /**
     * 定时任务线程池 —— 替代 Spring 默认的单线程调度器。
     * <p>避免一个 @Scheduled 任务阻塞导致所有定时任务延迟。</p>
     */
    @Bean(name = "schedThreadPool", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler schedThreadPool(ThreadPoolRegistry registry) {
        ThreadPoolProperties.ScheduleDef def = props.getSchedule();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(def.getCoreSize());
        scheduler.setThreadNamePrefix(def.getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(t ->
                log.error("[SchedPool] 定时任务异常: {}", t.getMessage(), t));
        scheduler.initialize();
        registry.register("schedThreadPool", scheduler);
        log.info("[ThreadPool] schedThreadPool initialized — core={}", def.getCoreSize());
        return scheduler;
    }

    /* ==================== 内部工具 ==================== */

    /**
     * 统一创建 ThreadPoolTaskExecutor 的方法，确保所有池的配置风格一致。
     */
    private ThreadPoolTaskExecutor createExecutor(ThreadPoolProperties.PoolDef def,
                                                  RejectedExecutionHandler rejectHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(def.getCoreSize());
        executor.setMaxPoolSize(def.getMaxSize());
        executor.setQueueCapacity(def.getQueueCapacity());
        executor.setKeepAliveSeconds(def.getKeepAliveSeconds());
        executor.setThreadNamePrefix(def.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(rejectHandler);
        // 优雅关闭：等待队列中的任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
