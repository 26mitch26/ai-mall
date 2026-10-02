package com.ai.mall.common.exception;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.common.api.IErrorCode;
import com.ai.mall.common.api.ResultCode;
import com.ai.mall.common.exception.GlobalExceptionHandler;
import com.ai.mall.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        lenient().when(request.getRequestURI()).thenReturn("/api/test");
    }

    // ========== ApiException Tests ==========

    @Test
    void testHandleApiException() {
        ApiException exception = new ApiException(new IErrorCode() {
            @Override
            public long getCode() {
                return 601;
            }

            @Override
            public String getMessage() {
                return "自定义业务错误";
            }
        });
        CommonResult<?> result = handler.handleApiException(exception, request);
        assertEquals(601, result.getCode());
        assertEquals("自定义业务错误", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void testHandleApiExceptionWithMessage() {
        ApiException exception = new ApiException("用户名已存在");
        CommonResult<?> result = handler.handleApiException(exception, request);
        assertEquals(ResultCode.FAILED.getCode(), result.getCode());
        assertEquals("用户名已存在", result.getMessage());
        assertNull(result.getData());
    }

    // ========== Validation Tests ==========

    @Test
    void testHandleValidationException() throws Exception {
        MethodParameter parameter = mock(MethodParameter.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError1 = new FieldError("userDTO", "username", "用户名不能为空");
        FieldError fieldError2 = new FieldError("userDTO", "password", "密码长度不能小于6");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError1, fieldError2));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        CommonResult<?> result = handler.handleMethodArgumentNotValidException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("username: 用户名不能为空"));
        assertTrue(result.getMessage().contains("password: 密码长度不能小于6"));
        assertNull(result.getData());
    }

    @Test
    void testHandleValidationException_SingleField() throws Exception {
        MethodParameter parameter = mock(MethodParameter.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("userDTO", "email", "邮箱格式不正确");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        CommonResult<?> result = handler.handleMethodArgumentNotValidException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertEquals("email: 邮箱格式不正确", result.getMessage());
    }

    @Test
    void testHandleBindException() {
        BindException exception = mock(BindException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError1 = new FieldError("obj", "field1", "字段1错误");
        FieldError fieldError2 = new FieldError("obj", "field2", "字段2错误");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError1, fieldError2));
        when(exception.getBindingResult()).thenReturn(bindingResult);

        CommonResult<?> result = handler.handleBindException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("field1: 字段1错误"));
        assertTrue(result.getMessage().contains("field2: 字段2错误"));
    }

    // ========== HTTP Method Tests ==========

    @Test
    void testHandleMethodNotSupported() {
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException(HttpMethod.POST.name());
        CommonResult<?> result = handler.handleHttpRequestMethodNotSupportedException(exception);
        assertEquals(ResultCode.FAILED.getCode(), result.getCode());
        assertEquals("请求方法不支持", result.getMessage());
        assertNull(result.getData());
    }

    // ========== Parameter Tests ==========

    @Test
    void testHandleMissingParameter() {
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("id", "Long");
        CommonResult<?> result = handler.handleMissingServletRequestParameterException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertEquals("缺少请求参数", result.getMessage());
        assertNull(result.getData());
    }

    // ========== Constraint Violation Tests ==========

    @Test
    void testHandleConstraintViolation() {
        ConstraintViolation<?> violation1 = mock(ConstraintViolation.class);
        ConstraintViolation<?> violation2 = mock(ConstraintViolation.class);
        when(violation1.getMessage()).thenReturn("名称不能为空");
        when(violation2.getMessage()).thenReturn("长度必须大于3");
        ConstraintViolationException exception =
                new ConstraintViolationException("校验失败", Set.of(violation1, violation2));

        CommonResult<?> result = handler.handleConstraintViolationException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("名称不能为空"));
        assertTrue(result.getMessage().contains("长度必须大于3"));
    }

    @Test
    void testHandleConstraintViolation_Single() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("参数不合法");
        ConstraintViolationException exception =
                new ConstraintViolationException("校验失败", Set.of(violation));

        CommonResult<?> result = handler.handleConstraintViolationException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertEquals("参数不合法", result.getMessage());
    }

    // ========== Security Tests ==========

    @Test
    void testHandleAccessDenied() {
        AccessDeniedException exception = new AccessDeniedException("权限不足");
        CommonResult<?> result = handler.handleAccessDeniedException(exception);
        assertEquals(ResultCode.FORBIDDEN.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void testHandleAuthentication() {
        AuthenticationException exception = mock(AuthenticationException.class);
        when(exception.getMessage()).thenReturn("认证失败");
        CommonResult<?> result = handler.handleAuthenticationException(exception);
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), result.getCode());
        assertNull(result.getData());
    }

    // ========== 404 Handler Tests ==========

    @Test
    void testHandleNoHandlerFound() {
        NoHandlerFoundException exception = mock(NoHandlerFoundException.class);
        CommonResult<?> result = handler.handleNoHandlerFoundException(exception, request);
        // 404 语义：修复前误用 failed()（业务码 500），把"路径不存在"伪装成服务器错误
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertEquals("请求地址不存在", result.getMessage());
    }

    @Test
    void testHandleNoResourceFound() {
        // Spring Boot 3.x 对未匹配路径抛 NoResourceFoundException（NoHandlerFoundException 不再触发），
        // 修复前落入兜底分支返回 500；此处回归验证 404 语义
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "product/categoryTree");
        CommonResult<?> result = handler.handleNoResourceFoundException(exception, request);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertEquals("请求地址不存在", result.getMessage());
    }

    // ========== Type Mismatch Tests ==========

    @Test
    void testHandleTypeMismatch() {
        TypeMismatchException exception = mock(TypeMismatchException.class);
        when(exception.getMessage()).thenReturn("Failed to convert value of type");
        CommonResult<?> result = handler.handleTypeMismatchException(exception);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), result.getCode());
        assertEquals("参数类型不匹配", result.getMessage());
    }

    // ========== Catch-all Tests ==========

    @Test
    void testHandleException() {
        Exception exception = new RuntimeException("数据库连接失败");
        CommonResult<?> result = handler.handleException(exception, request);
        assertEquals(ResultCode.FAILED.getCode(), result.getCode());
        assertEquals("服务器内部错误", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void testHandleException_NullPointer() {
        Exception exception = new NullPointerException();
        CommonResult<?> result = handler.handleException(exception, request);
        assertEquals(ResultCode.FAILED.getCode(), result.getCode());
        assertEquals("服务器内部错误", result.getMessage());
    }
}