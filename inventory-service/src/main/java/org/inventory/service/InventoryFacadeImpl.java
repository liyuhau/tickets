package org.inventory.service;

import org.apache.dubbo.config.annotation.DubboService;
import org.common.ResultCode;
import org.inventory.api.InventoryFacade;
import org.inventory.api.InventoryRpc;
import org.inventory.api.InventoryRpcException;
import org.inventory.api.StockDTO;
import org.inventory.entity.ProductPO;
import org.inventory.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旅游产品库存领域服务（MyBatis 持久化版）。
 * <ul>
 *   <li>读：{@link ProductMapper#selectById}</li>
 *   <li>写：先 {@code SELECT ... FOR UPDATE} 行级悲观锁，再带 {@code version} 条件
 *       做乐观锁 UPDATE（双重防超卖）。</li>
 *   <li>抛 {@link InventoryRpcException} 自动回滚事务。</li>
 * </ul>
 */
@DubboService(interfaceClass = InventoryFacade.class, version = InventoryRpc.VERSION)
public class InventoryFacadeImpl implements InventoryFacade {

    private static final Logger log = LoggerFactory.getLogger(InventoryFacadeImpl.class);

    private static final int CODE_BAD_REQUEST        = ResultCode.BAD_REQUEST.getCode();
    private static final int CODE_INSUFFICIENT_STOCK = ResultCode.INSUFFICIENT_STOCK.getCode();
    private static final int CODE_PRODUCT_NOT_FOUND  = ResultCode.PRODUCT_NOT_FOUND.getCode();

    private final ProductMapper productMapper;

    public InventoryFacadeImpl(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public StockDTO get(String productId) {
        ProductPO p = productMapper.selectById(productId);
        if (p == null) {
            throw new InventoryRpcException(CODE_PRODUCT_NOT_FOUND, "产品不存在: " + productId);
        }
        return toDTO(p);
    }

    @Override
    @Transactional
    public StockDTO deduct(String productId, Integer qty) {
        return changeStock(productId, qty, -qty, true);
    }

    @Override
    @Transactional
    public StockDTO restock(String productId, Integer qty) {
        return changeStock(productId, qty, qty, false);
    }

    private StockDTO changeStock(String productId, Integer qty, int delta, boolean checkInsufficient) {
        if (qty == null || qty <= 0) {
            throw new InventoryRpcException(CODE_BAD_REQUEST, "qty 必须大于 0");
        }
        ProductPO p = productMapper.selectByIdForUpdate(productId);
        if (p == null) {
            throw new InventoryRpcException(CODE_PRODUCT_NOT_FOUND, "产品不存在: " + productId);
        }
        if (checkInsufficient && p.getStock() < qty) {
            throw new InventoryRpcException(CODE_INSUFFICIENT_STOCK,
                    "余量不足: " + p.getName() + ", 当前剩余 " + p.getStock());
        }
        int updated = productMapper.updateStock(productId, delta, p.getVersion());
        if (updated == 0) {
            // 悲观锁理论上已避免并发；走到这里多半是 stock + delta < 0
            throw new InventoryRpcException(CODE_INSUFFICIENT_STOCK,
                    "库存更新失败（并发冲突或余量不足）: " + productId);
        }
        // 重新读一次以返回最新数据
        p = productMapper.selectById(productId);
        log.info("[Inventory] {} {} x{} -> stock={}",
                (delta < 0 ? "deduct" : "restock"), productId, qty, p.getStock());
        return toDTO(p);
    }

    private StockDTO toDTO(ProductPO p) {
        return new StockDTO(p.getProductId(), p.getType(), p.getName(),
                p.getTravelDate(), p.getPriceCents(), p.getStock());
    }
}
