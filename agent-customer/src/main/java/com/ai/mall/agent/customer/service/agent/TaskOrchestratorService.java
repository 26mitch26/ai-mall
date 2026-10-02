package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.ChatMessage;
import com.ai.mall.agent.customer.model.TaskRequest;
import com.ai.mall.agent.customer.model.TaskResponse;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.memory.MemoryService;
import com.ai.mall.agent.customer.service.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务型对话编排器：DST 维护会话槽位，DPM 决定下一步动作，工具层负责真实 API 调用。
 */
@Service
@RequiredArgsConstructor
public class TaskOrchestratorService {

    private static final Pattern ORDER_PATTERN = Pattern.compile("(?i)(?:订单号|订单编号|order[_ -]?sn)?\\s*([A-Z0-9][A-Z0-9-]{5,})");

    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public TaskResponse execute(TaskRequest request) {
        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? UUID.randomUUID().toString() : request.getSessionId();
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        List<ChatMessage> history = memoryService.getShortTermMemory(sessionId);
        String task = detectTask(message, history);
        Map<String, Object> slots = extractSlots(message, task, history);
        List<String> missing = missingSlots(task, slots);
        Map<String, Object> state = new HashMap<>();
        state.put("intent", task);
        state.put("slots", slots);
        state.put("filledSlots", slots.keySet());
        state.put("missingSlots", missing);
        state.put("turn", history.size() / 2 + 1);

        String action;
        String nextPrompt;
        String apiCall = null;
        String result = null;
        boolean completed = false;

        if ("unknown".equals(task)) {
            action = "CLARIFY_INTENT";
            nextPrompt = "你想让我搜索商品、查询订单，还是创建售后工单？";
        } else if (!missing.isEmpty()) {
            action = "ASK_SLOT";
            nextPrompt = "还需要补充：" + String.join("、", missing);
        } else {
            action = "CALL_API";
            apiCall = apiFor(task);
            result = toolRegistry.executeTool(task, toJson(task, slots), ToolInvocationContext.builder()
                    .sessionId(sessionId)
                    .memberId(request.getUserId())
                    .userToken(request.getUserToken())
                    .build());
            completed = result != null
                    && !result.contains("\"blocked\": true")
                    && !result.contains("\"error\"")
                    && !result.contains("\"code\":401")
                    && !result.contains("\"code\":500");
            nextPrompt = completed ? "任务已完成，可以继续追问或发起下一项任务。" : "接口未执行成功，请检查登录状态或补充参数。";
        }

        memoryService.addMessage(sessionId, memoryService.createMessage(sessionId, "user", message));
        memoryService.addMessage(sessionId, memoryService.createMessage(sessionId, "assistant",
                "TASK=" + task + "；DPM=" + action + "；" + nextPrompt));

        return TaskResponse.builder()
                .sessionId(sessionId)
                .task(task)
                .taskName(taskName(task))
                .dpmAction(action)
                .nextPrompt(nextPrompt)
                .completed(completed)
                .dstState(state)
                .apiCall(apiCall)
                .result(result)
                .build();
    }

    private String detectTask(String message, List<ChatMessage> history) {
        if (containsAny(message, "搜索商品", "找商品", "推荐商品", "搜一下", "商品搜索")) return "search_products";
        if (containsAny(message, "查订单", "订单状态", "物流状态", "订单信息", "查单")) return "get_order_info";
        if (containsAny(message, "创建售后", "售后工单", "退货工单", "退款工单")) return "create_after_sale";
        for (int i = history.size() - 1; i >= 0; i--) {
            String previous = history.get(i).getContent() == null ? "" : history.get(i).getContent();
            if (containsAny(previous, "搜索商品", "找商品", "推荐商品", "搜一下", "商品搜索")) return "search_products";
            if (containsAny(previous, "查订单", "订单状态", "物流状态", "订单信息", "查单")) return "get_order_info";
            if (containsAny(previous, "创建售后", "售后工单", "退货工单", "退款工单")) return "create_after_sale";
        }
        return "unknown";
    }

    private Map<String, Object> extractSlots(String message, String task, List<ChatMessage> history) {
        Map<String, Object> slots = new HashMap<>();
        if ("search_products".equals(task)) {
            String keyword = message.replaceAll(".*?(?:搜索商品|找商品|推荐商品|搜一下|商品搜索)\\s*", "").trim();
            slots.put("keyword", keyword.isBlank() ? "手机" : keyword);
            slots.put("page", 1);
        }
        if ("get_order_info".equals(task) || "create_after_sale".equals(task)) {
            Matcher matcher = ORDER_PATTERN.matcher(message);
            if (matcher.find()) slots.put("order_sn", matcher.group(1));
            for (int i = history.size() - 1; i >= 0 && !slots.containsKey("order_sn"); i--) {
                Matcher previous = ORDER_PATTERN.matcher(history.get(i).getContent() == null ? "" : history.get(i).getContent());
                if (previous.find()) slots.put("order_sn", previous.group(1));
            }
        }
        if ("create_after_sale".equals(task)) {
            slots.put("reason", containsAny(message, "质量", "损坏", "故障") ? "质量问题" : "用户申请售后");
            slots.put("description", message);
        }
        return slots;
    }

    private List<String> missingSlots(String task, Map<String, Object> slots) {
        if ("get_order_info".equals(task) || "create_after_sale".equals(task)) {
            return slots.containsKey("order_sn") ? List.of() : List.of("订单号");
        }
        return List.of();
    }

    private String toJson(String task, Map<String, Object> slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String apiFor(String task) {
        return switch (task) {
            case "search_products" -> "GET /product/search → mall-portal API";
            case "get_order_info" -> "POST tool://get_order_info → mall-portal API";
            case "create_after_sale" -> "POST tool://create_after_sale → mall-portal API";
            default -> "-";
        };
    }

    private String taskName(String task) {
        return switch (task) {
            case "search_products" -> "商品搜索";
            case "get_order_info" -> "订单查询";
            case "create_after_sale" -> "售后工单";
            default -> "待识别任务";
        };
    }

    private boolean containsAny(String message, String... words) {
        for (String word : words) if (message.contains(word)) return true;
        return false;
    }
}
