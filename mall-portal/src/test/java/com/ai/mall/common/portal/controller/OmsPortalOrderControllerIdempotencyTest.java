package com.ai.mall.common.portal.controller;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.portal.controller.OmsPortalOrderController;
import com.ai.mall.model.UmsMember;
import com.ai.mall.portal.domain.OrderParam;
import com.ai.mall.portal.service.OmsPortalOrderService;
import com.ai.mall.portal.service.UmsMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OmsPortalOrderControllerIdempotencyTest {
    private OmsPortalOrderController controller;
    private OmsPortalOrderService orderService;
    private JdbcTemplate jdbc;
    private UmsMemberService memberService;

    @BeforeEach
    void setUp() {
        controller = new OmsPortalOrderController();
        orderService = mock(OmsPortalOrderService.class);
        jdbc = mock(JdbcTemplate.class);
        memberService = mock(UmsMemberService.class);
        UmsMember member = new UmsMember(); member.setId(42L);
        when(memberService.getCurrentMember()).thenReturn(member);
        ReflectionTestUtils.setField(controller, "portalOrderService", orderService);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(controller, "memberService", memberService);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    @Test
    void retryReturnsCommittedResultWithoutCreatingAnotherOrder() {
        OrderParam request = request("stable-op-1", 12L);
        Map<String, Object> result = Map.of("order", Map.of("id", 7, "orderSn", "SN-7"));
        when(jdbc.queryForList(contains("SELECT request_hash, status, result_json"), anyString(), anyString()))
                .thenReturn(List.of(Map.of("request_hash", hash(request), "status", "PROCESSING", "result_json", "")))
                .thenReturn(List.of(Map.of("request_hash", hash(request), "status", "COMPLETED",
                        "result_json", "{\"order\":{\"id\":7,\"orderSn\":\"SN-7\"}}")));
        when(orderService.generateOrder(request)).thenReturn(result);

        CommonResult first = controller.generateOrder(request);
        CommonResult second = controller.generateOrder(request);

        assertEquals(result, first.getData());
        assertEquals(result, second.getData());
        verify(orderService, times(1)).generateOrder(request);
    }

    @Test
    void sameIdempotencyKeyWithChangedParametersIsRejected() {
        OrderParam original = request("stable-op-2", 12L);
        OrderParam changed = request("stable-op-2", 13L);
        when(jdbc.queryForList(contains("SELECT request_hash, status, result_json"), anyString(), anyString()))
                .thenReturn(List.of(Map.of("request_hash", hash(original), "status", "COMPLETED", "result_json", "{}")));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.generateOrder(changed));
        verify(orderService, never()).generateOrder(any());
    }

    private OrderParam request(String token, long cartId) {
        OrderParam request = new OrderParam();
        request.setIdempotencyToken(token);
        request.setMemberReceiveAddressId(3L);
        request.setCartIds(List.of(cartId));
        return request;
    }

    private String hash(OrderParam request) {
        try {
            var method = OmsPortalOrderController.class.getDeclaredMethod("requestHash", OrderParam.class);
            method.setAccessible(true);
            return (String) method.invoke(controller, request);
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
