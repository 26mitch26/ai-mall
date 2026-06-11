package com.ai.mall.common.service.impl;

import com.ai.mall.common.service.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisServiceImplTest {

    private RedisService redisService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private SetOperations<String, Object> setOperations;
    @Mock
    private ListOperations<String, Object> listOperations;

    @BeforeEach
    void setUp() {
        RedisServiceImpl impl = new RedisServiceImpl();
        // Use reflection to inject mock
        try {
            var field = RedisServiceImpl.class.getDeclaredField("redisTemplate");
            field.setAccessible(true);
            field.set(impl, redisTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        redisService = impl;

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    // --- String operations ---

    @Test
    void testSetWithExpiry() {
        redisService.set("key1", "value1", 100);
        verify(valueOperations).set("key1", "value1", 100, TimeUnit.SECONDS);
    }

    @Test
    void testSetWithoutExpiry() {
        redisService.set("key2", "value2");
        verify(valueOperations).set("key2", "value2");
    }

    @Test
    void testGet() {
        when(valueOperations.get("key1")).thenReturn("value1");
        Object result = redisService.get("key1");
        assertEquals("value1", result);
    }

    @Test
    void testGet_NonExistent() {
        when(valueOperations.get("missing")).thenReturn(null);
        Object result = redisService.get("missing");
        assertNull(result);
    }

    @Test
    void testDelete() {
        when(redisTemplate.delete("key1")).thenReturn(true);
        Boolean result = redisService.del("key1");
        assertTrue(result);
    }

    @Test
    void testDeleteList() {
        List<String> keys = Arrays.asList("key1", "key2");
        when(redisTemplate.delete(keys)).thenReturn(2L);
        Long result = redisService.del(keys);
        assertEquals(2L, result);
    }

    @Test
    void testExpire() {
        when(redisTemplate.expire("key1", 100L, TimeUnit.SECONDS)).thenReturn(true);
        Boolean result = redisService.expire("key1", 100);
        assertTrue(result);
    }

    @Test
    void testHasKey_Exists() {
        when(redisTemplate.hasKey("key1")).thenReturn(true);
        assertTrue(redisService.hasKey("key1"));
    }

    @Test
    void testHasKey_NotExists() {
        when(redisTemplate.hasKey("missing")).thenReturn(false);
        assertFalse(redisService.hasKey("missing"));
    }

    @Test
    void testIncrement() {
        when(valueOperations.increment("counter", 1)).thenReturn(5L);
        Long result = redisService.incr("counter", 1);
        assertEquals(5L, result);
    }

    @Test
    void testDecrement() {
        when(valueOperations.increment("counter", -1)).thenReturn(3L);
        Long result = redisService.decr("counter", 1);
        assertEquals(3L, result);
    }

    // --- Hash operations ---

    @SuppressWarnings("unchecked")
    @Test
    void testHGet() {
        when(hashOperations.get("hash", "field1")).thenReturn("value1");
        Object result = redisService.hGet("hash", "field1");
        assertEquals("value1", result);
    }

    @Test
    void testHSetWithExpiry() {
        when(redisTemplate.expire("hash", 100L, TimeUnit.SECONDS)).thenReturn(true);
        Boolean result = redisService.hSet("hash", "field1", "value1", 100);
        assertTrue(result);
        verify(hashOperations).put("hash", "field1", "value1");
    }

    @Test
    void testHSetWithoutExpiry() {
        redisService.hSet("hash", "field1", "value1");
        verify(hashOperations).put("hash", "field1", "value1");
    }

    @Test
    void testHGetAll() {
        Map<Object, Object> expected = Map.of("field1", "value1");
        when(hashOperations.entries("hash")).thenReturn(expected);
        Map<Object, Object> result = redisService.hGetAll("hash");
        assertEquals(expected, result);
    }

    @Test
    void testHDel() {
        redisService.hDel("hash", "field1", "field2");
        verify(hashOperations).delete("hash", "field1", "field2");
    }

    @Test
    void testHHasKey() {
        when(hashOperations.hasKey("hash", "field1")).thenReturn(true);
        assertTrue(redisService.hHasKey("hash", "field1"));
    }

    // --- Set operations ---

    @Test
    void testSAdd() {
        when(setOperations.add("set1", "a", "b")).thenReturn(2L);
        Long result = redisService.sAdd("set1", "a", "b");
        assertEquals(2L, result);
    }

    @Test
    void testSAddWithExpiry() {
        when(setOperations.add("set1", "a")).thenReturn(1L);
        when(redisTemplate.expire("set1", 100L, TimeUnit.SECONDS)).thenReturn(true);
        Long result = redisService.sAdd("set1", 100L, "a");
        assertEquals(1L, result);
    }

    @Test
    void testSMembers() {
        Set<Object> expected = Set.of("a", "b");
        when(setOperations.members("set1")).thenReturn(expected);
        Set<Object> result = redisService.sMembers("set1");
        assertEquals(expected, result);
    }

    @Test
    void testSIsMember() {
        when(setOperations.isMember("set1", "a")).thenReturn(true);
        assertTrue(redisService.sIsMember("set1", "a"));
    }

    // --- List operations ---

    @Test
    void testLPush() {
        when(listOperations.rightPush("list1", "item1")).thenReturn(1L);
        Long result = redisService.lPush("list1", "item1");
        assertEquals(1L, result);
    }

    @Test
    void testLPushWithExpiry() {
        when(listOperations.rightPush("list1", "item1")).thenReturn(1L);
        when(redisTemplate.expire("list1", 100L, TimeUnit.SECONDS)).thenReturn(true);
        Long result = redisService.lPush("list1", "item1", 100L);
        assertEquals(1L, result);
    }

    @Test
    void testLRange() {
        List<Object> expected = List.of("a", "b");
        when(listOperations.range("list1", 0, -1)).thenReturn(expected);
        List<Object> result = redisService.lRange("list1", 0, -1);
        assertEquals(expected, result);
    }

    @Test
    void testLRemove() {
        when(listOperations.remove("list1", 1, "item1")).thenReturn(1L);
        Long result = redisService.lRemove("list1", 1, "item1");
        assertEquals(1L, result);
    }
}