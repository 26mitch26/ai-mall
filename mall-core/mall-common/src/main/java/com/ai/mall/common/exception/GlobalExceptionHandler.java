package com.ai.mall.common.exception;

import com.ai.mall.common.api.CommonResult;
import com.ai.mall.common.api.IErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局异常处理类
 * Created by macro on 2020/2/27.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义API异常
     */
    @ExceptionHandler(ApiException.class)
    public CommonResult<?> handleApiException(ApiException e, HttpServletRequest request) {
        LOGGER.warn("API异常,请求地址：{},错误信息：{}", request.getRequestURI(), e.getMessage());
        IErrorCode errorCode = e.getErrorCode();
        if (errorCode != null) {
            return CommonResult.failed(errorCode);
        }
        return CommonResult.failed(e.getMessage());
    }

    /**
     * 处理参数校验异常（@Validated）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String message = fieldErrors.stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        LOGGER.warn("参数校验异常：{}", message);
        return CommonResult.validateFailed(message);
    }

    /**
     * 处理参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    public CommonResult<?> handleBindException(BindException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String message = fieldErrors.stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        LOGGER.warn("参数绑定异常：{}", message);
        return CommonResult.validateFailed(message);
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public CommonResult<?> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        LOGGER.warn("请求方法不支持：{}", e.getMessage());
        return CommonResult.failed("请求方法不支持");
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public CommonResult<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        LOGGER.warn("缺少请求参数：{}", e.getMessage());
        return CommonResult.validateFailed("缺少请求参数");
    }

    /**
     * 处理约束校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public CommonResult<?> handleConstraintViolationException(ConstraintViolationException e) {
        Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
        String message = violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        LOGGER.warn("约束校验异常：{}", message);
        return CommonResult.validateFailed(message);
    }

    /**
     * 处理认证异常
     */
    @ExceptionHandler(AuthenticationException.class)
    public CommonResult<?> handleAuthenticationException(AuthenticationException e) {
        LOGGER.warn("认证失败：{}", e.getMessage());
        return CommonResult.unauthorized(null);
    }

    /**
     * 处理授权异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public CommonResult<?> handleAccessDeniedException(AccessDeniedException e) {
        LOGGER.warn("访问被拒绝：{}", e.getMessage());
        return CommonResult.forbidden(null);
    }

    /**
     * 处理404异常
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public CommonResult<?> handleNoHandlerFoundException(NoHandlerFoundException e, HttpServletRequest request) {
        LOGGER.warn("请求地址不存在：{}", request.getRequestURI());
        return CommonResult.validateFailed("请求地址不存在");
    }

    /**
     * 处理静态资源/未知路径异常
     * Spring Boot 3.x 默认对未匹配任何 handler 的请求抛出 NoResourceFoundException（NoHandlerFoundException 不会再触发），
     * 修复前该异常落入兜底 Exception 分支：以 ERROR 级别记录 + 返回业务码 500，
     * 会把"接口路径写错/路由缺失"伪装成"服务器内部错误"（实测排查网关路由时因此误判）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public CommonResult<?> handleNoResourceFoundException(NoResourceFoundException e, HttpServletRequest request) {
        LOGGER.warn("请求地址不存在：{}", request.getRequestURI());
        return CommonResult.validateFailed("请求地址不存在");
    }

    /**
     * 处理类型不匹配异常
     */
    @ExceptionHandler(TypeMismatchException.class)
    public CommonResult<?> handleTypeMismatchException(TypeMismatchException e) {
        LOGGER.warn("参数类型不匹配：{}", e.getMessage());
        return CommonResult.validateFailed("参数类型不匹配");
    }

    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    public CommonResult<?> handleException(Exception e, HttpServletRequest request) {
        LOGGER.error("服务器内部异常,请求地址：{}", request.getRequestURI(), e);
        return CommonResult.failed("服务器内部错误");
    }
}
