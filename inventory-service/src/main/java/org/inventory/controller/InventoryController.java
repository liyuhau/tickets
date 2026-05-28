package org.inventory.controller;

import org.common.BusinessException;
import org.common.R;
import org.inventory.api.InventoryFacade;
import org.inventory.api.InventoryRpcException;
import org.inventory.api.StockDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 旅游产品库存 REST 入口（保留供 API Gateway 直接访问）。
 *
 * <pre>
 *   GET  /inventory/{productId}
 *   POST /inventory/{productId}/deduct?qty=2     占用库存
 *   POST /inventory/{productId}/restock?qty=1    释放库存
 * </pre>
 * 内部直接复用 {@link InventoryFacade} 的本地 Bean，避免业务逻辑重复。
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryFacade inventoryFacade;

    @Autowired
    public InventoryController(InventoryFacade inventoryFacade) {
        this.inventoryFacade = inventoryFacade;
    }

    @GetMapping("/{productId}")
    public R<StockDTO> get(@PathVariable String productId) {
        return R.ok(invoke(() -> inventoryFacade.get(productId)));
    }

    @PostMapping("/{productId}/deduct")
    public R<StockDTO> deduct(@PathVariable String productId, @RequestParam Integer qty) {
        return R.ok(invoke(() -> inventoryFacade.deduct(productId, qty)));
    }

    @PostMapping("/{productId}/restock")
    public R<StockDTO> restock(@PathVariable String productId, @RequestParam Integer qty) {
        return R.ok(invoke(() -> inventoryFacade.restock(productId, qty)));
    }

    /** 将 RPC 业务异常转成本地 BusinessException，由 GlobalExceptionHandler 包装 */
    private <T> T invoke(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (InventoryRpcException e) {
            throw new BusinessException(e.getCode(), e.getMessage());
        }
    }
}
