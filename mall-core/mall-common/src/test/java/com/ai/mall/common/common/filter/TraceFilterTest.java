package com.ai.mall.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TraceFilter 单元测试
 * <p>
 * 注意：MDC 的 traceId 只在过滤器链执行期间存在，
 * doFilterInternal 返回前会在 finally 中清理（这是过滤器的正确语义）。
 * 因此断言必须放在 filterChain.doFilter 的回调中执行，而非方法返回之后。
 */
@ExtendWith(MockitoExtension.class)
class TraceFilterTest {

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String TRACE_ID = "traceId";

    private TraceFilter traceFilter;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        traceFilter = new TraceFilter();
        // 确保MDC清洁
        MDC.clear();
    }

    @Test
    void testGenerateTraceId_WhenNoHeader() throws Exception {
        when(request.getHeader(HEADER_REQUEST_ID)).thenReturn(null);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            String traceId = MDC.get(TRACE_ID);
            assertNotNull(traceId, "过滤器链执行期间应存在 traceId");
            assertEquals(32, traceId.length(), "UUID 去除连字符应为 32 位");
            assertFalse(traceId.contains("-"));
            chainCalled.set(true);
            return null;
        }).when(filterChain).doFilter(request, response);

        traceFilter.doFilterInternal(request, response, filterChain);

        assertTrue(chainCalled.get());
        assertNull(MDC.get(TRACE_ID), "过滤器结束后 MDC 应被清理");
    }

    @Test
    void testUseTraceId_FromHeader() throws Exception {
        when(request.getHeader(HEADER_REQUEST_ID)).thenReturn("external-trace-id-123");

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            assertEquals("external-trace-id-123", MDC.get(TRACE_ID),
                    "应透传外部请求头中的 traceId");
            chainCalled.set(true);
            return null;
        }).when(filterChain).doFilter(request, response);

        traceFilter.doFilterInternal(request, response, filterChain);

        assertTrue(chainCalled.get());
        assertNull(MDC.get(TRACE_ID));
    }

    @Test
    void testUseTraceId_WithBlankHeader() throws Exception {
        when(request.getHeader(HEADER_REQUEST_ID)).thenReturn("   ");

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            String traceId = MDC.get(TRACE_ID);
            assertNotNull(traceId, "空白请求头应回退为自动生成");
            assertEquals(32, traceId.length());
            chainCalled.set(true);
            return null;
        }).when(filterChain).doFilter(request, response);

        traceFilter.doFilterInternal(request, response, filterChain);

        assertTrue(chainCalled.get());
        assertNull(MDC.get(TRACE_ID));
    }

    @Test
    void testMdcCleaned_AfterFilter() throws Exception {
        when(request.getHeader(HEADER_REQUEST_ID)).thenReturn(null);

        traceFilter.doFilterInternal(request, response, filterChain);

        // 过滤器返回后，MDC 应已被清理
        assertNull(MDC.get(TRACE_ID));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testMdcCleaned_WhenFilterThrows() throws Exception {
        when(request.getHeader(HEADER_REQUEST_ID)).thenReturn(null);
        doThrow(new RuntimeException("filter error")).when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class, () ->
                traceFilter.doFilterInternal(request, response, filterChain));

        // 即使异常，MDC 也应被清理，避免上下文串扰
        assertNull(MDC.get(TRACE_ID));
    }
}
