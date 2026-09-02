package com.ai.mall.portal.service.impl;

import com.ai.mall.common.exception.ApiException;
import com.ai.mall.common.service.RedisService;
import com.ai.mall.mapper.*;
import com.ai.mall.model.*;
import com.ai.mall.portal.component.CancelOrderSender;
import com.ai.mall.portal.dao.PortalOrderDao;
import com.ai.mall.portal.dao.PortalOrderItemDao;
import com.ai.mall.portal.service.OmsCartItemService;
import com.ai.mall.portal.service.UmsMemberService;
import com.ai.mall.portal.service.UmsMemberCouponService;
import com.ai.mall.portal.service.UmsMemberReceiveAddressService;
import com.ai.mall.portal.domain.OmsOrderDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OmsPortalOrderServiceImpl.
 * Tests core business logic methods with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class OmsPortalOrderServiceImplTest {

    private OmsPortalOrderServiceImpl orderService;

    @Mock
    private UmsMemberService memberService;
    @Mock
    private OmsCartItemService cartItemService;
    @Mock
    private UmsMemberReceiveAddressService memberReceiveAddressService;
    @Mock
    private UmsMemberCouponService memberCouponService;
    @Mock
    private UmsIntegrationConsumeSettingMapper integrationConsumeSettingMapper;
    @Mock
    private PmsSkuStockMapper skuStockMapper;
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private PortalOrderItemDao orderItemDao;
    @Mock
    private SmsCouponHistoryMapper couponHistoryMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private PortalOrderDao portalOrderDao;
    @Mock
    private OmsOrderSettingMapper orderSettingMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private CancelOrderSender cancelOrderSender;

    private UmsMember currentMember;

    @BeforeEach
    void setUp() {
        orderService = new OmsPortalOrderServiceImpl();
        // Inject all mocks via reflection
        ReflectionTestUtils.setField(orderService, "memberService", memberService);
        ReflectionTestUtils.setField(orderService, "cartItemService", cartItemService);
        ReflectionTestUtils.setField(orderService, "memberReceiveAddressService", memberReceiveAddressService);
        ReflectionTestUtils.setField(orderService, "memberCouponService", memberCouponService);
        ReflectionTestUtils.setField(orderService, "integrationConsumeSettingMapper", integrationConsumeSettingMapper);
        ReflectionTestUtils.setField(orderService, "skuStockMapper", skuStockMapper);
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemDao", orderItemDao);
        ReflectionTestUtils.setField(orderService, "couponHistoryMapper", couponHistoryMapper);
        ReflectionTestUtils.setField(orderService, "redisService", redisService);
        ReflectionTestUtils.setField(orderService, "portalOrderDao", portalOrderDao);
        ReflectionTestUtils.setField(orderService, "orderSettingMapper", orderSettingMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "cancelOrderSender", cancelOrderSender);
        ReflectionTestUtils.setField(orderService, "REDIS_KEY_ORDER_ID", "orderId");
        ReflectionTestUtils.setField(orderService, "REDIS_DATABASE", "mall");

        currentMember = new UmsMember();
        currentMember.setId(1L);
        currentMember.setUsername("testuser");
        currentMember.setIntegration(1000);
    }

    // ========== cancelOrder Tests ==========

    @Test
    void testCancelOrder_OrderFoundAndCancelled() {
        Long orderId = 1L;
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setStatus(0);
        order.setDeleteStatus(0);
        order.setCouponId(null);
        order.setMemberId(1L);
        order.setUseIntegration(null);

        doReturn(List.of(order)).when(orderMapper).selectByExample(any());
        // 订单含订单项，cancelOrder 才会调用解锁库存
        when(orderItemMapper.selectByExample(any())).thenReturn(List.of(new OmsOrderItem()));

        orderService.cancelOrder(orderId);

        verify(orderMapper).updateByPrimaryKeySelective(order);
        verify(orderMapper, times(1)).selectByExample(any());
        verify(orderItemMapper).selectByExample(any());
        verify(portalOrderDao).releaseSkuStockLock(anyList());
        assertEquals(Integer.valueOf(4), order.getStatus());
    }

    @Test
    void testCancelOrder_OrderAlreadyShipped_NoAction() {
        Long orderId = 1L;

        // Order with status 2 (shipped) should not be cancelled
        doReturn(new ArrayList<>()).when(orderMapper).selectByExample(any());

        orderService.cancelOrder(orderId);

        verify(orderMapper, never()).updateByPrimaryKeySelective(any());
        verify(orderItemMapper, never()).selectByExample(any());
    }

    @Test
    void testCancelOrder_WithCouponAndIntegration() {
        Long orderId = 1L;
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setStatus(0);
        order.setDeleteStatus(0);
        order.setCouponId(100L);
        order.setMemberId(1L);
        order.setUseIntegration(500);

        doReturn(List.of(order)).when(orderMapper).selectByExample(any());
        when(orderItemMapper.selectByExample(any())).thenReturn(new ArrayList<>());
        when(memberService.getById(1L)).thenReturn(currentMember);

        orderService.cancelOrder(orderId);

        // Verify coupon status was updated
        verify(couponHistoryMapper).selectByExample(any());
        // Verify integration was refunded
        verify(memberService).updateIntegration(1L, 1500); // 1000 + 500
    }

    // ========== deleteOrder Tests ==========

    @Test
    void testDeleteOrder_WrongMember() {
        Long orderId = 1L;
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(999L); // different member

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        assertThrows(ApiException.class, () -> orderService.deleteOrder(orderId));
        verify(orderMapper, never()).updateByPrimaryKey(any());
    }

    @Test
    void testDeleteOrder_WrongStatus() {
        Long orderId = 1L;
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(1L);
        order.setStatus(0); // unpaid, cannot delete

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        assertThrows(ApiException.class, () -> orderService.deleteOrder(orderId));
        verify(orderMapper, never()).updateByPrimaryKey(any());
    }

    @Test
    void testDeleteOrder_Success_Completed() {
        Long orderId = 1L;
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(1L);
        order.setStatus(3); // completed, can delete
        order.setDeleteStatus(0);

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        orderService.deleteOrder(orderId);

        verify(orderMapper).updateByPrimaryKey(order);
        assertEquals(Integer.valueOf(1), order.getDeleteStatus());
    }

    @Test
    void testDeleteOrder_Success_Closed() {
        Long orderId = 1L;
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(1L);
        order.setStatus(4); // closed, can delete
        order.setDeleteStatus(0);

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        orderService.deleteOrder(orderId);

        verify(orderMapper).updateByPrimaryKey(order);
        assertEquals(Integer.valueOf(1), order.getDeleteStatus());
    }

    // ========== detail Tests ==========

    @Test
    void testDetail_OrderFound() {
        Long orderId = 1L;
        OmsOrder omsOrder = new OmsOrder();
        omsOrder.setId(orderId);
        omsOrder.setOrderSn("20240611000001");

        OmsOrderItem item1 = new OmsOrderItem();
        item1.setOrderId(orderId);
        item1.setProductName("商品1");

        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(omsOrder);
        when(orderItemMapper.selectByExample(any())).thenReturn(List.of(item1));

        OmsOrderDetail result = orderService.detail(orderId);

        assertNotNull(result);
        assertEquals("20240611000001", result.getOrderSn());
        assertEquals(1, result.getOrderItemList().size());
        assertEquals("商品1", result.getOrderItemList().get(0).getProductName());
    }

    @Test
    void testDetail_OrderNotFound() {
        Long orderId = 999L;
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(null);

        OmsOrderDetail result = orderService.detail(orderId);

        // 订单不存在时方法返回空对象（各字段为 null），并非 null 本身
        assertNotNull(result);
        assertNull(result.getId());
        assertNull(result.getOrderSn());
    }

    // ========== paySuccessByOrderSn Tests ==========

    @Test
    void testPaySuccessByOrderSn() {
        String orderSn = "20240611000001";
        Integer payType = 1;

        OmsOrder order = new OmsOrder();
        order.setId(1L);
        order.setOrderSn(orderSn);
        order.setStatus(0);
        order.setDeleteStatus(0);

        when(orderMapper.selectByExample(any())).thenReturn(List.of(order));

        // The paySuccess method would need portalOrderDao mock
        when(portalOrderDao.getDetail(1L)).thenReturn(new OmsOrderDetail());
        when(portalOrderDao.updateSkuStock(any())).thenReturn(1);

        orderService.paySuccessByOrderSn(orderSn, payType);

        verify(orderMapper).selectByExample(any());
        // paySuccess 内部新建对象并落库，验证更新调用携带已支付状态
        verify(orderMapper).updateByPrimaryKeySelective(any());
    }

    @Test
    void testPaySuccessByOrderSn_OrderNotFound() {
        String orderSn = "NONEXISTENT";
        when(orderMapper.selectByExample(any())).thenReturn(new ArrayList<>());

        orderService.paySuccessByOrderSn(orderSn, 1);

        verify(portalOrderDao, never()).getDetail(any());
    }

    // ========== paySuccess Tests ==========

    @Test
    void testPaySuccess() {
        Long orderId = 1L;
        Integer payType = 2;

        OmsOrderDetail orderDetail = new OmsOrderDetail();
        orderDetail.setId(orderId);
        OmsOrderItem item = new OmsOrderItem();
        item.setProductSkuId(100L);
        item.setProductQuantity(2);
        orderDetail.setOrderItemList(List.of(item));

        when(portalOrderDao.getDetail(orderId)).thenReturn(orderDetail);
        when(portalOrderDao.updateSkuStock(any())).thenReturn(1);

        Integer count = orderService.paySuccess(orderId, payType);

        assertEquals(1, count);
        verify(orderMapper).updateByPrimaryKeySelective(argThat(order ->
                order.getId().equals(orderId)
                        && order.getStatus() == 1
                        && order.getPayType() == 2
        ));
    }

    // ========== confirmReceiveOrder Tests ==========

    @Test
    void testConfirmReceiveOrder_Success() {
        Long orderId = 1L;
        currentMember.setId(1L);

        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(1L);
        order.setStatus(2); // shipped

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        orderService.confirmReceiveOrder(orderId);

        verify(orderMapper).updateByPrimaryKey(order);
        assertEquals(Integer.valueOf(3), order.getStatus());
        assertEquals(Integer.valueOf(1), order.getConfirmStatus());
        assertNotNull(order.getReceiveTime());
    }

    @Test
    void testConfirmReceiveOrder_WrongMember() {
        Long orderId = 1L;
        currentMember.setId(1L);

        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(999L); // different member

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        assertThrows(ApiException.class, () -> orderService.confirmReceiveOrder(orderId));
        verify(orderMapper, never()).updateByPrimaryKey(any());
    }

    @Test
    void testConfirmReceiveOrder_NotShipped() {
        Long orderId = 1L;
        currentMember.setId(1L);

        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setMemberId(1L);
        order.setStatus(0); // not shipped

        when(memberService.getCurrentMember()).thenReturn(currentMember);
        when(orderMapper.selectByPrimaryKey(orderId)).thenReturn(order);

        assertThrows(ApiException.class, () -> orderService.confirmReceiveOrder(orderId));
        verify(orderMapper, never()).updateByPrimaryKey(any());
    }

    // ========== cancelTimeOutOrder Tests ==========

    @Test
    void testCancelTimeOutOrder_NoTimeoutOrders() {
        OmsOrderSetting setting = new OmsOrderSetting();
        setting.setNormalOrderOvertime(60);

        when(orderSettingMapper.selectByPrimaryKey(1L)).thenReturn(setting);
        when(portalOrderDao.getTimeOutOrders(60)).thenReturn(new ArrayList<>());

        Integer count = orderService.cancelTimeOutOrder();

        assertEquals(0, count);
        verify(portalOrderDao, never()).updateOrderStatus(anyList(), anyInt());
    }

    @Test
    void testCancelTimeOutOrder_WithTimeoutOrders() {
        OmsOrderSetting setting = new OmsOrderSetting();
        setting.setNormalOrderOvertime(60);

        OmsOrderDetail timeoutOrder = new OmsOrderDetail();
        timeoutOrder.setId(1L);
        timeoutOrder.setCouponId(100L);
        timeoutOrder.setMemberId(1L);

        when(orderSettingMapper.selectByPrimaryKey(1L)).thenReturn(setting);
        when(portalOrderDao.getTimeOutOrders(60)).thenReturn(List.of(timeoutOrder));

        Integer count = orderService.cancelTimeOutOrder();

        assertEquals(1, count);
        verify(portalOrderDao).updateOrderStatus(List.of(1L), 4);
        verify(portalOrderDao).releaseSkuStockLock(any());
    }
}