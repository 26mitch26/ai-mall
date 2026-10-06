package com.ai.mall.agent.customer.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes={WorkflowController.class,TaskController.class})
public class WorkflowExceptionAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error","Invalid or expired workflow request"));
    }
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String,String>> forbidden(SecurityException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","Workflow access denied"));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Workflow state changed or could not be verified; refresh before confirming"));
    }
}
