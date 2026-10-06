package com.ai.mall.common.portal.service.impl;
import com.ai.mall.portal.service.impl.OmsPortalOrderReturnApplyServiceImpl;

import com.ai.mall.mapper.OmsOrderItemMapper;
import com.ai.mall.mapper.OmsOrderMapper;
import com.ai.mall.mapper.OmsOrderReturnApplyMapper;
import com.ai.mall.model.*;
import com.ai.mall.portal.domain.OmsOrderReturnApplyParam;
import com.ai.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OmsPortalOrderReturnApplyServiceImplTest {
    private OmsPortalOrderReturnApplyServiceImpl service;
    private OmsOrderReturnApplyMapper returnMapper;
    private OmsOrderMapper orderMapper;
    private OmsOrderItemMapper itemMapper;
    private UmsMemberService memberService;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        service = new OmsPortalOrderReturnApplyServiceImpl();
        returnMapper = mock(OmsOrderReturnApplyMapper.class);
        orderMapper = mock(OmsOrderMapper.class);
        itemMapper = mock(OmsOrderItemMapper.class);
        memberService = mock(UmsMemberService.class);
        jdbc = mock(JdbcTemplate.class);
        UmsMember member = new UmsMember(); member.setId(42L); member.setUsername("buyer");
        lenient().when(memberService.getCurrentMember()).thenReturn(member);
        OmsOrder order = new OmsOrder(); order.setId(9L); order.setMemberId(42L);
        order.setOrderSn("SN-9"); order.setPayAmount(new BigDecimal("70.50"));
        lenient().when(orderMapper.selectByPrimaryKey(9L)).thenReturn(order);
        OmsOrderItem item = new OmsOrderItem(); item.setOrderId(9L); item.setProductId(12L);
        item.setProductName("Headphones"); item.setProductQuantity(2);
        item.setProductPrice(new BigDecimal("50.00")); item.setRealAmount(new BigDecimal("35.25"));
        lenient().when(itemMapper.selectByExample(any(OmsOrderItemExample.class))).thenReturn(List.of(item));
        lenient().when(jdbc.queryForMap(contains("FOR UPDATE"), any(), any())).thenReturn(Map.of("id", 9L));
        lenient().when(jdbc.queryForObject(contains("SUM(return_amount)"), eq(BigDecimal.class), any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(jdbc.queryForObject(contains("SUM(return_amount)"), eq(BigDecimal.class), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(returnMapper.insert(any(OmsOrderReturnApply.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "returnApplyMapper", returnMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "orderItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "memberService", memberService);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
    }

    @Test
    void usesPersistedDiscountedLineAmountAndCapsRefundAtPaidOrderAmount() {
        OmsOrderReturnApply result = service.createAndReturn(request());

        assertEquals(new BigDecimal("70.50"), result.getReturnAmount());
        assertEquals(new BigDecimal("35.25"), result.getProductRealPrice());
        assertEquals(2, result.getProductCount());
        assertEquals("buyer", result.getMemberUsername());
        verify(returnMapper).insert(any(OmsOrderReturnApply.class));
    }

    @Test
    void rejectsWhenActiveApplicationsLeaveLessThanTheLineRefund() {
        when(jdbc.queryForObject(contains("SUM(return_amount)"), eq(BigDecimal.class), any(), any()))
                .thenReturn(new BigDecimal("20.01"));

        assertThrows(IllegalArgumentException.class, () -> service.createAndReturn(request()));
        verify(returnMapper, never()).insert(any(OmsOrderReturnApply.class));
    }

    @Test
    void rejectsDuplicateRefundForSameProductEvenWhenOtherOrderBalanceRemains() {
        OmsOrder order = new OmsOrder(); order.setId(9L); order.setMemberId(42L);
        order.setOrderSn("SN-9"); order.setPayAmount(new BigDecimal("200.00"));
        when(orderMapper.selectByPrimaryKey(9L)).thenReturn(order);
        when(jdbc.queryForObject(contains("SUM(return_amount)"), eq(BigDecimal.class), any(), any()))
                .thenReturn(new BigDecimal("70.50"));

        assertThrows(IllegalArgumentException.class, () -> service.createAndReturn(request()));
        verify(returnMapper, never()).insert(any(OmsOrderReturnApply.class));
    }

    @Test
    void capsRefundAtTheOrderPaidAmountEvenWhenLineSnapshotIsHigher() {
        OmsOrder order = new OmsOrder(); order.setId(9L); order.setMemberId(42L);
        order.setOrderSn("SN-9"); order.setPayAmount(new BigDecimal("70.00"));
        when(orderMapper.selectByPrimaryKey(9L)).thenReturn(order);

        assertThrows(IllegalArgumentException.class, () -> service.createAndReturn(request()));
        verify(returnMapper, never()).insert(any(OmsOrderReturnApply.class));
    }

    @Test
    void rejectsOrderOwnedByAnotherMemberBeforeCreatingApplication() {
        OmsOrder foreignOrder = new OmsOrder(); foreignOrder.setId(9L); foreignOrder.setMemberId(99L);
        foreignOrder.setOrderSn("SN-9"); foreignOrder.setPayAmount(new BigDecimal("70.50"));
        when(orderMapper.selectByPrimaryKey(9L)).thenReturn(foreignOrder);

        assertThrows(SecurityException.class, () -> service.createAndReturn(request()));
        verify(jdbc, never()).queryForMap(anyString(), any(), any());
        verify(returnMapper, never()).insert(any(OmsOrderReturnApply.class));
    }

    private OmsOrderReturnApplyParam request() {
        OmsOrderReturnApplyParam param = new OmsOrderReturnApplyParam();
        param.setOrderId(9L); param.setOrderSn("SN-9"); param.setProductId(12L);
        param.setReason("质量问题"); param.setDescription("测试描述");
        return param;
    }
}
