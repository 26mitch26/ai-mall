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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TraceFilterTest {

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
        when(request.getHeader("X-Request-Id")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/test");

        traceFilter.doFilterInternal(request, response, filterChain);

        String traceId = MDC.get("traceId");
        assertNotNull(traceId);
        assertFalse(traceId.contains("-"));
        assertEquals(32, traceId.length()); // UUID without dashes = 32 chars
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testUseTraceId_FromHeader() throws Exception {
        when(request.getHeader("X-Request-Id")).thenReturn("external-trace-id-123");
        when(request.getRequestURI()).thenReturn("/api/test");

        traceFilter.doFilterInternal(request, response, filterChain);

        assertEquals("external-trace-id-123", MDC.get("traceId"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testUseTraceId_WithBlankHeader() throws Exception {
        when(request.getHeader("X-Request-Id")).thenReturn("   ");
        when(request.getRequestURI()).thenReturn("/api/test");

        traceFilter.doFilterInternal(request, response, filterChain);

        String traceId = MDC.get("traceId");
        assertNotNull(traceId);
        assertFalse(traceId.contains("-"));
        assertEquals(32, traceId.length());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testMdcCleaned_AfterFilter() throws Exception {
        when(request.getHeader("X-Request-Id")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/test");

        traceFilter.doFilterInternal(request, response, filterChain);

        // MDC should be cleared after filter
        assertNull(MDC.get("traceId"));
    }

    @Test
    void testMdcCleaned_WhenFilterThrows() throws Exception {
        when(request.getHeader("X-Request-Id")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/test");
        doThrow(new RuntimeException("filter error")).when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class, () ->
                traceFilter.doFilterInternal(request, response, filterChain));

        // MDC should still be cleaned even on exception
        assertNull(MDC.get("traceId"));
    }
}