package org.inventory.api;

/**
 * 旅游产品库存对外 Dubbo 接口（RPC 契约）。
 * <p>同业渠道（携程/同程/飞猪/海外 B2B 平台等）通过本接口查询和占用产品库存。</p>
 * <p>调用失败时抛 {@link InventoryRpcException}，调用方应捕获并转换为各自体系的业务异常。</p>
 */
public interface InventoryFacade {

    /** 查询单个产品余量 */
    StockDTO get(String productId);

    /**
     * 占用库存（预订时调用，等价于电商的"扣库存"）。
     *
     * @param productId 产品编码
     * @param qty       同业渠道一次申请的份数（机票=乘机人数，酒店=房间数）
     * @return 扣减后的最新库存
     * @throws InventoryRpcException 产品不存在 / 余量不足 / 参数错误
     */
    StockDTO deduct(String productId, Integer qty);

    /** 释放库存（取消预订时调用） */
    StockDTO restock(String productId, Integer qty);
}
