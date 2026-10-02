package com.ai.mall.portal.component;

import com.ai.mall.common.service.RedisService;
import com.ai.mall.mapper.PmsSkuStockMapper;
import com.ai.mall.model.PmsSkuStock;
import com.ai.mall.model.PmsSkuStockExample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存预热：应用启动时从 DB 加载所有 SKU 可用库存到 Redis。
 *
 * 为什么需要预热：
 * Redis 库存 key 是下单时才扣减的，如果 Redis 重启或 key 过期，
 * Redis 里没有库存数据，下单时 Lua 脚本查到 0 直接拒绝——即使 DB 里有库存。
 * 预热保证 Redis 和 DB 的库存数据在启动时同步。
 *
 * 运行时机：CommandLineRunner，在 Spring 容器初始化完成后执行。
 */
@Slf4j
@Component
public class StockWarmupRunner implements CommandLineRunner {

    private static final String STOCK_KEY_PREFIX = "stock:available:";

    @Autowired
    private RedisService redisService;

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    @Override
    public void run(String... args) {
        log.info("开始库存预热...");
        try {
            PmsSkuStockExample example = new PmsSkuStockExample();
            List<PmsSkuStock> allStocks = skuStockMapper.selectByExample(example);
            int count = 0;
            for (PmsSkuStock stock : allStocks) {
                int available = stock.getStock() - stock.getLockStock();
                if (available > 0) {
                    String key = STOCK_KEY_PREFIX + stock.getId();
                    redisService.set(key, (long) available);
                    count++;
                }
            }
            log.info("库存预热完成，共加载 {} 个 SKU 到 Redis", count);
        } catch (Exception e) {
            log.warn("库存预热失败（Redis 不可用），降级为运行时从 DB 读取: {}", e.getMessage());
        }
    }
}