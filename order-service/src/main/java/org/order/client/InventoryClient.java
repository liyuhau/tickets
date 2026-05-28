package org.order.client;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.apache.dubbo.config.annotation.DubboReference;
import org.common.BusinessException;
import org.common.ResultCode;
import org.inventory.api.InventoryFacade;
import org.inventory.api.InventoryRpc;
import org.inventory.api.InventoryRpcException;
import org.inventory.api.StockDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预订中心 → 产品中心 的 Dubbo 调用客户端（含 Sentinel 熔断 / 降级）。
 *
 * <p>资源名 / version 抽到 {@link InventoryRpc} 常量；timeout / check 等
 * 通过 yaml dubbo.consumer.* 配置，本类不再硬编码。</p>
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    /** Sentinel 资源名（与控制台规则配置保持一致） */
    public static final String RES_GET    = "inventory.get";
    public static final String RES_DEDUCT = "inventory.deduct";

    @DubboReference(version = InventoryRpc.VERSION, check = false)
    private InventoryFacade inventoryFacade;

    @SentinelResource(value = RES_GET, blockHandler = "getBlocked")
    public StockDTO get(String productId) {
        try {
            return inventoryFacade.get(productId);
        } catch (InventoryRpcException e) {
            throw new BusinessException(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[InventoryClient] dubbo get failed: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.REMOTE_CALL_FAILED, "产品服务调用失败: " + e.getMessage());
        }
    }

    @SentinelResource(value = RES_DEDUCT, blockHandler = "deductBlocked")
    public StockDTO deduct(String productId, Integer qty) {
        try {
            return inventoryFacade.deduct(productId, qty);
        } catch (InventoryRpcException e) {
            throw new BusinessException(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[InventoryClient] dubbo deduct failed: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.REMOTE_CALL_FAILED, "产品服务调用失败: " + e.getMessage());
        }
    }

    // -------- Sentinel block handlers --------
    public StockDTO getBlocked(String productId, BlockException ex) {
        log.warn("[Sentinel] {} blocked, productId={}, rule={}", RES_GET, productId, ex.getClass().getSimpleName());
        throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "产品查询过于频繁，请稍后再试");
    }

    public StockDTO deductBlocked(String productId, Integer qty, BlockException ex) {
        log.warn("[Sentinel] {} blocked, productId={}, qty={}, rule={}", RES_DEDUCT, productId, qty, ex.getClass().getSimpleName());
        throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "下单流量已达上限，请稍后再试");
    }
}
