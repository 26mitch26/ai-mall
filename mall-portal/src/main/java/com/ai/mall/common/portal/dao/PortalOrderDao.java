package com.ai.mall.portal.dao;

import com.ai.mall.model.OmsOrderItem;
import com.ai.mall.portal.domain.OmsOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 前台订单管理自定义Dao
 * Created by macro on 2018/9/4.
 */
public interface PortalOrderDao {
    /**
     * 获取订单及下单商品详情
     */
    OmsOrderDetail getDetail(@Param("orderId") Long orderId);

    /**
     * 修改 pms_sku_stock表的锁定库存及真实库存
     */
    int updateSkuStock(@Param("itemList") List<OmsOrderItem> orderItemList);

    /**
     * 获取超时订单
     * @param minute 超时时间（分）
     */
    List<OmsOrderDetail> getTimeOutOrders(@Param("minute") Integer minute);

    /**
     * 批量修改订单状态
     */
    int updateOrderStatus(@Param("ids") List<Long> ids,@Param("status") Integer status);

    /**
     * 解除取消订单的库存锁定
     */
    int releaseSkuStockLock(@Param("itemList") List<OmsOrderItem> orderItemList);

    /**
     * 查询用户对某商品的已购数量（待付款+待发货+已发货均算已购）
     */
    long countPurchasedQuantity(@Param("memberId") Long memberId, @Param("productId") Long productId);

    /**
     * 乐观锁扣库存：stock = stock - quantity WHERE id = skuId AND stock >= quantity。
     * 返回影响行数：1=扣成功，0=库存不足（乐观锁冲突）。
     *
     * 为什么比 SELECT + UPDATE 安全：
     * SELECT + UPDATE 两个请求并发时，都读到 stock=1，都写 stock=0，超卖。
     * 乐观锁用 WHERE stock >= quantity 保证：如果扣减前库存已经不够了，SQL 影响 0 行，不扣。
     */
    int deductStockOptimistic(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /**
     * 预热 Redis 库存：从 DB 加载所有 SKU 的可用库存（stock - lockStock）到 Redis。
     */
    List<com.ai.mall.model.PmsSkuStock> getAllSkuStocks();

}
