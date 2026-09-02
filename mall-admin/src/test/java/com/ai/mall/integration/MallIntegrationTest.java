package com.ai.mall.integration;

import com.ai.mall.dao.PmsMemberPriceDao;
import com.ai.mall.dao.PmsProductDao;
import com.ai.mall.dto.PmsProductResult;
import com.ai.mall.model.PmsMemberPrice;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 依赖 Docker（Testcontainers 启动 MySQL/Redis），默认构建不执行（见 surefire excludedGroups） */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class MallIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("mall_test")
            .withUsername("root")
            .withPassword("root")
            .withInitScript("integration-test-init.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private PmsMemberPriceDao memberPriceDao;

    @Autowired
    private PmsProductDao productDao;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Test
    void contextLoads() {
        assertNotNull(memberPriceDao);
        assertNotNull(productDao);
        assertNotNull(redisTemplate);
    }

    @Test
    void testProductDaoGetUpdateInfo() {
        PmsProductResult productResult = productDao.getUpdateInfo(1L);
        assertNotNull(productResult);
        assertEquals("测试手机", productResult.getName());
    }

    @Test
    @Transactional
    void testMemberPriceDaoInsertList() {
        List<PmsMemberPrice> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PmsMemberPrice memberPrice = new PmsMemberPrice();
            memberPrice.setProductId(1L);
            memberPrice.setMemberLevelId((long) (i + 1));
            memberPrice.setMemberPrice(new BigDecimal("10"));
            list.add(memberPrice);
        }
        int count = memberPriceDao.insertList(list);
        assertEquals(3, count);
    }

    @Test
    void testProductDaoReturnsNullForNonexistentProduct() {
        PmsProductResult productResult = productDao.getUpdateInfo(999L);
        assertNull(productResult);
    }

    @Test
    void testRedisBasicOperations() {
        String key = "test:integration:key";
        String value = "hello-testcontainers";

        redisTemplate.opsForValue().set(key, value, 10, TimeUnit.SECONDS);
        String result = (String) redisTemplate.opsForValue().get(key);

        assertEquals(value, result);
    }

    @Test
    void testRedisExpiration() throws InterruptedException {
        String key = "test:integration:expire";
        redisTemplate.opsForValue().set(key, "temp", 1, TimeUnit.SECONDS);
        // Poll until expired, max 3 seconds
        long deadline = System.currentTimeMillis() + 3000;
        String result = "temp";
        while (System.currentTimeMillis() < deadline && "temp".equals(result)) {
            Thread.sleep(100);
            result = (String) redisTemplate.opsForValue().get(key);
        }
        assertNull(result);
    }

    @Test
    void testRedisDeleteOperation() {
        String key = "test:integration:delete";
        redisTemplate.opsForValue().set(key, "value");
        redisTemplate.delete(key);
        String result = (String) redisTemplate.opsForValue().get(key);
        assertNull(result);
    }
}