package com.ai.mall.common.service.impl;

import com.ai.mall.common.service.RedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis操作Service实现类
 * Created by macro on 2020/3/3.
 */
public class RedisServiceImpl implements RedisService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void set(String key, Object value, long time) {
        redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
    }

    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public Boolean del(String key) {
        return redisTemplate.delete(key);
    }

    @Override
    public Long del(List<String> keys) {
        return redisTemplate.delete(keys);
    }

    @Override
    public Boolean expire(String key, long time) {
        return redisTemplate.expire(key, time, TimeUnit.SECONDS);
    }

    @Override
    public Long getExpire(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    @Override
    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    @Override
    public Long incr(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    @Override
    public Long decr(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, -delta);
    }

    @Override
    public Object hGet(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    @Override
    public Boolean hSet(String key, String hashKey, Object value, long time) {
        redisTemplate.opsForHash().put(key, hashKey, value);
        return expire(key, time);
    }

    @Override
    public void hSet(String key, String hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    @Override
    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    @Override
    public Boolean hSetAll(String key, Map<String, Object> map, long time) {
        redisTemplate.opsForHash().putAll(key, map);
        return expire(key, time);
    }

    @Override
    public void hSetAll(String key, Map<String, ?> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    @Override
    public void hDel(String key, Object... hashKey) {
        redisTemplate.opsForHash().delete(key, hashKey);
    }

    @Override
    public Boolean hHasKey(String key, String hashKey) {
        return redisTemplate.opsForHash().hasKey(key, hashKey);
    }

    @Override
    public Long hIncr(String key, String hashKey, Long delta) {
        return redisTemplate.opsForHash().increment(key, hashKey, delta);
    }

    @Override
    public Long hDecr(String key, String hashKey, Long delta) {
        return redisTemplate.opsForHash().increment(key, hashKey, -delta);
    }

    @Override
    public Set<Object> sMembers(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    @Override
    public Long sAdd(String key, Object... values) {
        return redisTemplate.opsForSet().add(key, values);
    }

    @Override
    public Long sAdd(String key, long time, Object... values) {
        Long count = redisTemplate.opsForSet().add(key, values);
        expire(key, time);
        return count;
    }

    @Override
    public Boolean sIsMember(String key, Object value) {
        return redisTemplate.opsForSet().isMember(key, value);
    }

    @Override
    public Long sSize(String key) {
        return redisTemplate.opsForSet().size(key);
    }

    @Override
    public Long sRemove(String key, Object... values) {
        return redisTemplate.opsForSet().remove(key, values);
    }

    @Override
    public List<Object> lRange(String key, long start, long end) {
        return redisTemplate.opsForList().range(key, start, end);
    }

    @Override
    public Long lSize(String key) {
        return redisTemplate.opsForList().size(key);
    }

    @Override
    public Object lIndex(String key, long index) {
        return redisTemplate.opsForList().index(key, index);
    }

    @Override
    public Long lPush(String key, Object value) {
        return redisTemplate.opsForList().rightPush(key, value);
    }

    @Override
    public Long lPush(String key, Object value, long time) {
        Long index = redisTemplate.opsForList().rightPush(key, value);
        expire(key, time);
        return index;
    }

    @Override
    public Long lPushAll(String key, Object... values) {
        return redisTemplate.opsForList().rightPushAll(key, values);
    }

    @Override
    public Long lPushAll(String key, Long time, Object... values) {
        Long count = redisTemplate.opsForList().rightPushAll(key, values);
        expire(key, time);
        return count;
    }

    @Override
    public Long lRemove(String key, long count, Object value) {
        return redisTemplate.opsForList().remove(key, count, value);
    }

    // ============ Lua 脚本原子扣库存 ============

    /**
     * 单个 SKU 原子扣库存 Lua 脚本：
     * KEYS[1] = stock key
     * ARGV[1] = 扣减数量
     *
     * 逻辑：当前库存 >= 扣减数量？扣减并返回剩余库存 : 返回 -1（不扣减）
     *
     * 为什么比 DECRBY 好：
     * DECRBY 先扣再判断，扣成负数要回滚（两次操作，中间有窗口）。
     * Lua 把检查和扣减写在一个脚本里，Redis 单线程保证原子执行，要么全成功要么全不做。
     */
    private static final String LUA_DECR_STOCK_SCRIPT =
            "local current = tonumber(redis.call('get', KEYS[1]) or '0') " +
            "local qty = tonumber(ARGV[1]) " +
            "if current >= qty then " +
            "  redis.call('decrby', KEYS[1], qty) " +
            "  return current - qty " +
            "else " +
            "  return -1 " +
            "end";

    @Override
    public Long luaDecrStock(String key, long quantity) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_DECR_STOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(key), quantity);
        return result;
    }

    /**
     * 批量 Lua 原子扣库存：多个 SKU 一起扣，任一不够则全部不扣。
     *
     * 用 Lua 脚本保证"全部够才全部扣，有一个不够就全部不扣"——
     * 普通的 DECRBY 无法做到这一点（扣了第一个发现第二个不够，要回滚第一个）。
     *
     * KEYS = [stock:sku1, stock:sku2, ...]
     * ARGV = [qty1, qty2, ...]
     */
    private static final String LUA_BATCH_DECR_STOCK_SCRIPT =
            "for i = 1, #KEYS do " +
            "  local current = tonumber(redis.call('get', KEYS[i]) or '0') " +
            "  local qty = tonumber(ARGV[i]) " +
            "  if current < qty then " +
            "    return 0 " +
            "  end " +
            "end " +
            "for i = 1, #KEYS do " +
            "  redis.call('decrby', KEYS[i], tonumber(ARGV[i])) " +
            "end " +
            "return 1";

    @Override
    public boolean luaBatchDecrStock(Map<String, Long> skuQuantities) {
        List<String> keys = new ArrayList<>(skuQuantities.keySet());
        List<Long> quantities = new ArrayList<>(skuQuantities.values());
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_BATCH_DECR_STOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, keys, quantities.toArray());
        return Long.valueOf(1L).equals(result);
    }
}
