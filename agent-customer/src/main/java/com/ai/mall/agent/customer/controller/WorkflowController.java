package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.model.workflow.AfterSaleWorkflowState;
import com.ai.mall.agent.customer.service.security.MemberIdentityResolver;
import com.ai.mall.agent.customer.service.workflow.AfterSaleWorkflowService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.*;

/** Authenticated API for preparing, confirming, and resuming durable after-sale workflows. */
@RestController
@RequestMapping("/api/v1/workflows/after-sale")
@RequiredArgsConstructor
public class WorkflowController {
    private final AfterSaleWorkflowService workflowService;
    private final MemberIdentityResolver identityResolver;

    @PostMapping
    public AfterSaleWorkflowState prepare(@RequestBody PrepareRequest request,
                                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        ToolInvocationContext context = identityResolver.resolve(request.getSessionId(), authorization);
        return workflowService.prepare(context, request.getOrderSn(), request.getReason(), request.getDescription());
    }

    @PostMapping("/order-cancellation")
    public AfterSaleWorkflowState prepareCancellation(@RequestBody PrepareCancellationRequest request,
                                                       @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        ToolInvocationContext context = identityResolver.resolve(request.getSessionId(), authorization);
        return workflowService.prepareCancellation(context, request.getOrderSn());
    }

    @PostMapping("/order-cancellation/{taskId}/confirm")
    public AfterSaleWorkflowState confirmCancellation(@PathVariable String taskId, @RequestBody ConfirmRequest request,
                                                       @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                       @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        ToolInvocationContext context = identityResolver.resolve(sessionId, authorization);
        return workflowService.confirm(context, taskId, request.getExpectedVersion(), request.isApproved());
    }

    @GetMapping("/order-cancellation/{taskId}")
    public AfterSaleWorkflowState getCancellation(@PathVariable String taskId,
                                                   @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                   @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return workflowService.get(identityResolver.resolve(sessionId, authorization), taskId);
    }

    @PostMapping("/{taskId}/confirm")
    public AfterSaleWorkflowState confirm(@PathVariable String taskId, @RequestBody ConfirmRequest request,
                                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                          @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        ToolInvocationContext context = identityResolver.resolve(sessionId, authorization);
        return workflowService.confirm(context, taskId, request.getExpectedVersion(), request.isApproved());
    }

    @GetMapping("/{taskId}")
    public AfterSaleWorkflowState get(@PathVariable String taskId,
                                      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                      @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return workflowService.get(identityResolver.resolve(sessionId, authorization), taskId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> forbidden(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @Data
    public static class PrepareRequest {
        private String sessionId;
        private String orderSn;
        private String reason;
        private String description;
    }

    @Data
    public static class ConfirmRequest {
        private long expectedVersion;
        private boolean approved;
    }

    @Data
    public static class PrepareCancellationRequest {
        private String sessionId;
        private String orderSn;
    }
}
