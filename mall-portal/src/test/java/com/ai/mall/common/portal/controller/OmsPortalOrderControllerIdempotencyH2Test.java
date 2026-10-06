package com.ai.mall.common.portal.controller;
import com.ai.mall.portal.controller.OmsPortalOrderController;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.model.UmsMember;
import com.ai.mall.portal.domain.OrderParam;
import com.ai.mall.portal.service.OmsPortalOrderService;
import com.ai.mall.portal.service.UmsMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the actual JDBC idempotency SQL and shared transaction boundary on H2 MySQL mode. */
class OmsPortalOrderControllerIdempotencyH2Test {
    private JdbcTemplate jdbc;
    private OmsPortalOrderController controller;
    private OmsPortalOrderService orderService;
    private UmsMemberService memberService;
    private TransactionTemplate tx;
    private long memberId = 42L;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:idem" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        this.jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE agent_operation_idempotency (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_type VARCHAR(32) NOT NULL, owner_id VARCHAR(64) NOT NULL, operation_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, result_json CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(owner_type, owner_id, operation_key))");
        jdbc.execute("CREATE TABLE business_orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL, cart_id BIGINT NOT NULL)");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        orderService = mock(OmsPortalOrderService.class);
        memberService = mock(UmsMemberService.class);
        when(memberService.getCurrentMember()).thenAnswer(invocation -> {
            UmsMember member = new UmsMember(); member.setId(memberId); return member;
        });
        controller = new OmsPortalOrderController();
        ReflectionTestUtils.setField(controller, "portalOrderService", orderService);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(controller, "memberService", memberService);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    @Test
    void replayParameterConflictUserIsolationAndRollbackUseRealSql() {
        OrderParam request = request("stable-op", 11L);
        AtomicInteger creations = new AtomicInteger();
        when(orderService.generateOrder(any())).thenAnswer(invocation -> {
            OrderParam param = invocation.getArgument(0);
            jdbc.update("INSERT INTO business_orders(owner_id, cart_id) VALUES (?, ?)", memberId, param.getCartIds().get(0));
            creations.incrementAndGet();
            return Map.of("orderId", 91, "orderSn", "SN-91");
        });

        CommonResult first = tx.execute(status -> controller.generateOrder(request));
        CommonResult replay = tx.execute(status -> controller.generateOrder(request));
        assertEquals(new ObjectMapper().valueToTree(first.getData()).toString(), new ObjectMapper().valueToTree(replay.getData()).toString());
        assertEquals(1, creations.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_orders", Integer.class));

        OrderParam changed = request("stable-op", 12L);
        assertThrows(ResponseStatusException.class, () -> tx.execute(status -> controller.generateOrder(changed)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_orders", Integer.class));

        memberId = 43L;
        CommonResult otherMember = tx.execute(status -> controller.generateOrder(request));
        assertEquals(new ObjectMapper().valueToTree(first.getData()).toString(), new ObjectMapper().valueToTree(otherMember.getData()).toString());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM business_orders", Integer.class));

        OrderParam rollback = request("rollback-op", 13L);
        doAnswer(invocation -> {
            jdbc.update("INSERT INTO business_orders(owner_id, cart_id) VALUES (?, ?)", memberId, 13L);
            throw new IllegalStateException("simulated failure after business write");
        }).when(orderService).generateOrder(any());
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> controller.generateOrder(rollback)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM business_orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_operation_idempotency WHERE operation_key='rollback-op'", Integer.class));
    }

    @Test
    void concurrentSameKeyCreatesOnlyOneBusinessOrder() throws Exception {
        OrderParam request = request("concurrent-op", 19L);
        AtomicInteger creations = new AtomicInteger();
        when(orderService.generateOrder(any())).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO business_orders(owner_id, cart_id) VALUES (?, ?)", memberId, 19L);
            creations.incrementAndGet();
            Thread.sleep(120);
            return Map.of("orderId", 101L, "orderSn", "SN-101");
        });
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return tx.execute(status -> controller.generateOrder(request)); });
            var b = pool.submit(() -> { start.await(); return tx.execute(status -> controller.generateOrder(request)); });
            start.countDown();
            assertEquals(new ObjectMapper().valueToTree(a.get(10, TimeUnit.SECONDS).getData()).toString(),
                    new ObjectMapper().valueToTree(b.get(10, TimeUnit.SECONDS).getData()).toString());
        } finally { pool.shutdownNow(); }
        assertEquals(1, creations.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_orders", Integer.class));
    }

    private OrderParam request(String token, long cartId) {
        OrderParam request = new OrderParam(); request.setIdempotencyToken(token);
        request.setMemberReceiveAddressId(3L); request.setCartIds(List.of(cartId)); return request;
    }
}
