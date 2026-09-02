package com.ai.mall.agent.customer.controller;

import com.ai.mall.agent.customer.model.ChatRequest;
import com.ai.mall.agent.customer.model.ChatResponse;
import com.ai.mall.agent.customer.service.agent.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "智能客服", description = "智能客服对话接口")
public class ChatController {

    private final ChatService chatService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping
    @Operation(summary = "普通对话", description = "发送消息并获取回复")
    public ChatResponse chat(@RequestBody ChatRequest request,
                             @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        attachUserToken(request, authorization);
        log.info("Chat request: {}", request.getMessage());
        return chatService.chat(request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话", description = "发送消息并流式获取回复")
    public SseEmitter chatStream(@RequestBody ChatRequest request,
                                 @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        attachUserToken(request, authorization);
        SseEmitter emitter = new SseEmitter(60000L);

        executor.execute(() -> {
            try {
                ChatResponse response = chatService.chat(request);

                String[] words = response.getAnswer().split("");
                StringBuilder currentAnswer = new StringBuilder();

                for (String word : words) {
                    currentAnswer.append(word);
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(currentAnswer.toString()));
                    Thread.sleep(50);
                }

                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(response));

                emitter.complete();
            } catch (IOException | InterruptedException e) {
                log.error("Error streaming response: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @DeleteMapping("/session/{sessionId}")
    @Operation(summary = "清除会话", description = "清除指定会话的历史记录")
    public void clearSession(@PathVariable String sessionId) {
        chatService.clearSession(sessionId);
    }

    /**
     * 请求体未携带令牌时，从 Authorization 头补充。
     * 令牌只在内存中随调用链传递，供敏感工具透传给后端做身份校验。
     */
    private void attachUserToken(ChatRequest request, String authorization) {
        if (request == null || authorization == null || authorization.isBlank()) {
            return;
        }
        if (request.getUserToken() != null && !request.getUserToken().isBlank()) {
            return;
        }
        request.setUserToken(authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : authorization);
    }
}
