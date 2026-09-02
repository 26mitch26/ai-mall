package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.AuditEvent;
import com.ai.mall.agent.customer.model.AuditEventType;
import com.ai.mall.agent.customer.model.FeedbackRequest;
import com.ai.mall.agent.customer.service.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 人工校对闭环
 * <p>
 * 用户对客服回答点赞/点踩并可给出"正确答案"，样本写入审计流水，
 * 作为后续统计幻觉率、优化提示词与知识库的 ground truth。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
@Tag(name = "客服反馈", description = "人工校对闭环：用户反馈与审计流水查询")
public class FeedbackController {

    private final AuditService auditService;

    @PostMapping
    @Operation(summary = "提交回答反馈", description = "对客服回答点赞/点踩，并可给出正确答案用于人工校对")
    public Map<String, Object> submit(@RequestBody FeedbackRequest request) {
        if (request == null || request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (request.getRating() == null || request.getRating().isBlank()) {
            throw new IllegalArgumentException("rating 不能为空");
        }

        // 评价取值白名单校验，避免脏数据进入校对样本
        String rating = request.getRating().trim().toLowerCase(Locale.ROOT);
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("rating 只能是 up 或 down");
        }

        StringBuilder detail = new StringBuilder("用户评价: ").append(rating);
        if (hasText(request.getCorrectedAnswer())) {
            detail.append(" | 用户给出的正确答案: ").append(request.getCorrectedAnswer());
        }
        if (hasText(request.getComment())) {
            detail.append(" | 补充说明: ").append(request.getComment());
        }

        auditService.record(AuditEvent.builder()
                .sessionId(request.getSessionId())
                .memberId(null)
                .type(AuditEventType.FEEDBACK.getCode())
                .detail(detail.toString())
                .build());

        log.info("收到用户反馈, sessionId={}, rating={}, 附带正确答案={}",
                request.getSessionId(), rating, hasText(request.getCorrectedAnswer()));

        return Map.of("success", true, "sessionId", request.getSessionId());
    }

    @GetMapping("/audit/{sessionId}")
    @Operation(summary = "查询会话审计流水", description = "供人工校对与问题追溯使用")
    public List<AuditEvent> audit(@PathVariable String sessionId,
                                  @RequestParam(defaultValue = "50") int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return auditService.listEvents(sessionId, limit);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
