package org.common.thread;

import org.common.R;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程池运行时监控 + 动态调参 REST 端点。
 * <p>
 * 放在 common-core 中，所有微服务自动拥有以下端点：
 * <pre>
 *   GET    /diag/thread-pools              所有托管线程池运行时快照
 *   GET    /diag/thread-pools/{name}       指定线程池快照
 *   PUT    /diag/thread-pools/{name}       动态调整 coreSize / maxSize
 * </pre>
 *
 * <b>示例：</b>
 * <pre>
 *   # 查看所有池状态
 *   curl http://localhost:8082/diag/thread-pools
 *
 *   # 运行时调大业务线程池
 *   curl -X PUT "http://localhost:8082/diag/thread-pools/bizThreadPool?coreSize=16&amp;maxSize=64"
 * </pre>
 *
 * <b>⚠ 生产环境建议通过网关权限或 IP 白名单限制访问。</b>
 */
@RestController
@RequestMapping("/diag/thread-pools")
public class ThreadPoolMonitorController {

    private final ThreadPoolRegistry registry;

    public ThreadPoolMonitorController(ThreadPoolRegistry registry) {
        this.registry = registry;
    }

    /* ==================== 查询 ==================== */

    /**
     * 所有托管线程池的运行时指标
     */
    @GetMapping
    public R<List<Map<String, Object>>> listAll() {
        return R.ok(registry.snapshot());
    }

    /**
     * 指定线程池的运行时指标
     */
    @GetMapping("/{name}")
    public R<Map<String, Object>> getOne(@PathVariable String name) {
        Map<String, Object> s = registry.snapshotOf(name);
        if (s == null) {
            return R.fail(1404, "线程池不存在: " + name);
        }
        return R.ok(s);
    }

    /* ==================== 动态调参 ==================== */

    /**
     * 运行时调整线程池核心参数（热生效，不重启）。
     * <p>
     * 仅支持 ThreadPoolTaskExecutor 类型的池（bizThreadPool / asyncThreadPool）。
     * <br>调参后立即返回调整后的运行时指标。
     * </p>
     *
     * @param name     线程池名称（bizThreadPool / asyncThreadPool）
     * @param coreSize 新核心线程数（可选）
     * @param maxSize  新最大线程数（可选）
     */
    @PutMapping("/{name}")
    public R<Map<String, Object>> resize(
            @PathVariable String name,
            @RequestParam(required = false) Integer coreSize,
            @RequestParam(required = false) Integer maxSize) {

        if (coreSize == null && maxSize == null) {
            return R.fail(1400, "至少指定 coreSize 或 maxSize");
        }
        if (coreSize != null && coreSize < 1) {
            return R.fail(1400, "coreSize 必须 ≥ 1");
        }
        if (maxSize != null && maxSize < 1) {
            return R.fail(1400, "maxSize 必须 ≥ 1");
        }
        if (coreSize != null && maxSize != null && coreSize > maxSize) {
            return R.fail(1400, "coreSize 不能大于 maxSize");
        }

        Map<String, Object> result = registry.resize(name, coreSize, maxSize);
        if (result == null) {
            return R.fail(1404, "线程池不存在或非 Executor 类型: " + name);
        }
        return R.ok(result);
    }

    /* ==================== 概览 ==================== */

    /**
     * 简要摘要：每个池一行（名称 + 活跃/核心/最大 + 队列使用）
     */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> pools = registry.snapshot();
        result.put("totalManagedPools", pools.size());

        for (Map<String, Object> pool : pools) {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("active/core/max",
                    pool.get("activeCount") + " / " + pool.get("corePoolSize") + " / "
                            + pool.getOrDefault("maxPoolSize", "-"));
            brief.put("queueUsage", pool.getOrDefault("queueUsagePercent", "N/A"));
            brief.put("completed", pool.get("completedTaskCount"));
            result.put(String.valueOf(pool.get("name")), brief);
        }
        return R.ok(result);
    }
}
