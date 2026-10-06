package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.TaskRequest;
import com.ai.mall.agent.customer.model.TaskResponse;
import com.ai.mall.agent.customer.service.agent.TaskOrchestratorService;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.security.MemberIdentityResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DST + DPM 任务型对话接口。 */
@RestController
@RequestMapping("/api/v1/task")
@RequiredArgsConstructor
@Tag(name = "任务型对话", description = "DST 槽位跟踪与 DPM 策略执行")
public class TaskController {

    private final TaskOrchestratorService taskOrchestratorService;
    private final MemberIdentityResolver identityResolver;

    @PostMapping("/execute")
    @Operation(summary = "执行任务型对话", description = "识别任务、提取槽位并调用真实业务工具")
    public TaskResponse execute(@RequestBody TaskRequest request,
                                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        ToolInvocationContext context = identityResolver.resolve(request == null ? null : request.getSessionId(), authorization);
        return taskOrchestratorService.execute(request, context);
    }
}
