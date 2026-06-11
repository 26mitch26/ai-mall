package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.ChatMessage;
import com.ai.mall.agent.customer.model.Tool;
import com.ai.mall.agent.customer.service.memory.MemoryService;
import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import com.ai.mall.agent.customer.service.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgent {

    private final OpenAiChatModel mimoChatModel;
    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final RagService ragService;
    private final ObjectMapper objectMapper;
    private final InputSanitizer inputSanitizer;

    private static final int MAX_ITERATIONS = 5;

    public String think(String sessionId, String query) {
        log.info("ReAct Agent thinking for session: {}, query: {}", sessionId, query);

        // Sanitize input to prevent prompt injection
        String sanitizedQuery = inputSanitizer.sanitize(query);
        if (inputSanitizer.isValidLength(sanitizedQuery)) {
            query = sanitizedQuery;
        }

        List<ChatMessage> history = memoryService.getShortTermMemory(sessionId);

        StringBuilder context = new StringBuilder();
        for (ChatMessage msg : history) {
            context.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }

        String toolDescriptions = getToolDescriptions();

        String systemPrompt = String.format("""
                你是一个智能客服助手，使用ReAct（思考-行动-观察）模式来回答问题。

                可用工具：
                %s

                请按照以下格式回答：
                Thought: [你的思考过程]
                Action: [工具名称]
                Action Input: [工具参数JSON]
                Observation: [工具返回结果]
                ... (可以重复上述步骤)
                Final Answer: [最终答案]

                如果不需要使用工具，直接给出Final Answer。
                """, toolDescriptions);

        String userMessage = context + "\n用户: " + query;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("ReAct iteration: {}", i + 1);

            String response = mimoChatModel.call(systemPrompt + "\n" + userMessage);
            log.info("Agent response: {}", response);

            if (response.contains("Final Answer:")) {
                String answer = response.substring(response.indexOf("Final Answer:") + 13).trim();
                memoryService.addMessage(sessionId, memoryService.createMessage(sessionId, "user", query));
                memoryService.addMessage(sessionId, memoryService.createMessage(sessionId, "assistant", answer));
                return answer;
            }

            if (response.contains("Action:")) {
                String action = extractAction(response);
                String actionInput = extractActionInput(response);

                if (action != null && !action.isEmpty()) {
                    String observation = toolRegistry.executeTool(action, actionInput);
                    userMessage += "\n" + response + "\nObservation: " + observation;
                }
            }
        }

        String fallbackAnswer = ragService.generateAnswer(query, ragService.retrieve(query, 3));
        memoryService.addMessage(sessionId, memoryService.createMessage(sessionId, "user", query));
        memoryService.addMessage(sessionId, memoryService.createMessage(sessionId, "assistant", fallbackAnswer));
        return fallbackAnswer;
    }

    private String getToolDescriptions() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : toolRegistry.getAllTools()) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            sb.append("  参数: ").append(tool.getParameters()).append("\n");
        }
        return sb.toString();
    }

    private String extractAction(String response) {
        try {
            int actionStart = response.indexOf("Action:") + 7;
            int actionEnd = response.indexOf("\n", actionStart);
            if (actionEnd == -1) actionEnd = response.length();
            return response.substring(actionStart, actionEnd).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractActionInput(String response) {
        try {
            int inputStart = response.indexOf("Action Input:") + 13;
            int inputEnd = response.indexOf("\n", inputStart);
            if (inputEnd == -1) inputEnd = response.length();
            return response.substring(inputStart, inputEnd).trim();
        } catch (Exception e) {
            return "{}";
        }
    }
}
