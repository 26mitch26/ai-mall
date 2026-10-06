package com.ai.mall.common.portal.controller;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.model.OmsOrderReturnApply;
import com.ai.mall.model.UmsMember;
import com.ai.mall.portal.domain.OmsOrderReturnApplyParam;
import com.ai.mall.portal.service.OmsPortalOrderReturnApplyService;
import com.ai.mall.portal.service.UmsMemberService;
import com.ai.mall.portal.controller.OmsPortalOrderReturnApplyController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OmsPortalReturnApplyIdempotencyH2Test {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OmsPortalOrderReturnApplyController controller;
    private OmsPortalOrderReturnApplyService service;
    private UmsMemberService memberService;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:returnidem" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE agent_operation_idempotency (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_type VARCHAR(32) NOT NULL, owner_id VARCHAR(64) NOT NULL, operation_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, result_json CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(owner_type, owner_id, operation_key))");
        jdbc.execute("CREATE TABLE return_applications (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, reason VARCHAR(128))");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = mock(OmsPortalOrderReturnApplyService.class);
        memberService = mock(UmsMemberService.class);
        UmsMember member = new UmsMember(); member.setId(42L);
        when(memberService.getCurrentMember()).thenReturn(member);
        controller = new OmsPortalOrderReturnApplyController();
        ReflectionTestUtils.setField(controller, "returnApplyService", service);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(controller, "memberService", memberService);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    @Test
    void afterSaleRetryReturnsStoredResultAndChangedArgumentsConflict() {
        OmsOrderReturnApplyParam request = request("质量问题");
        AtomicInteger creates = new AtomicInteger();
        when(service.createAndReturn(any())).thenAnswer(invocation -> {
            OmsOrderReturnApplyParam param = invocation.getArgument(0);
            jdbc.update("INSERT INTO return_applications(order_id, reason) VALUES (?, ?)", param.getOrderId(), param.getReason());
            creates.incrementAndGet();
            OmsOrderReturnApply application = new OmsOrderReturnApply(); application.setId(72L); application.setOrderSn("SN-72"); return application;
        });
        CommonResult first = tx.execute(status -> controller.create(request, "after-sale-op"));
        CommonResult replay = tx.execute(status -> controller.create(request, "after-sale-op"));
        assertEquals(new ObjectMapper().valueToTree(first.getData()).toString(), new ObjectMapper().valueToTree(replay.getData()).toString());
        assertEquals(1, creates.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM return_applications", Integer.class));

        OmsOrderReturnApplyParam changed = request("不再需要");
        assertThrows(ResponseStatusException.class, () -> tx.execute(status -> controller.create(changed, "after-sale-op")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM return_applications", Integer.class));
        CommonResult<Map<String, Object>> lookup = controller.lookupOperation("after-sale-op");
        assertEquals("COMPLETED", lookup.getData().get("status"));
        assertEquals(new ObjectMapper().valueToTree(first.getData()).toString(), new ObjectMapper().valueToTree(lookup.getData().get("result")).toString());
    }

    private OmsOrderReturnApplyParam request(String reason) {
        OmsOrderReturnApplyParam param = new OmsOrderReturnApplyParam();
        param.setOrderId(18L); param.setOrderSn("SN-72"); param.setProductId(24L);
        param.setProductPrice(new BigDecimal("10.00")); param.setReason(reason); return param;
    }
}
