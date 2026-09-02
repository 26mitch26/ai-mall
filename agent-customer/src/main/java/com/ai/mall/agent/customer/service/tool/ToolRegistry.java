package com.ai.mall.agent.customer.service.tool;

import com.ai.mall.agent.customer.model.Tool;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import com.ai.mall.agent.customer.service.security.ToolAccessGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表与执行入口
 * <p>
 * 安全约束（本次加固）：
 * 1. 调用前鉴权：由 {@link ToolAccessGuard} 判定工具等级与用户身份，
 *    敏感工具未登录即拒绝（fail-closed），未登记的工具名一律拒绝。
 * 2. 数据隔离：订单查询/售后工单均绑定当前登录用户，
 *    把 memberId 作为查询条件透传给后端，并在本地对响应做一次归属校验。
 * 3. 服务间鉴权：调用后端接口时携带内部服务令牌与用户 JWT，
 *    令牌只从配置/环境变量读取，禁止硬编码。
 * 4. 返回净化：工具返回属于外部数据，进入提示词前必须过一遍 {@link InputSanitizer}，
 *    防止间接提示词注入（Indirect Prompt Injection）。
 * 5. 错误不外泄：异常只返回通用文案，不把堆栈与内部信息回传给模型/用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final ToolAccessGuard toolAccessGuard;
    private final InputSanitizer inputSanitizer;

    @Value("${service.mall-search.url:http://localhost:8081}")
    private String mallSearchUrl;

    @Value("${service.mall-portal.url:http://localhost:8085}")
    private String mallPortalUrl;

    /**
     * 内部服务调用令牌，仅从配置或环境变量注入，未配置时不发送该请求头。
     */
    @Value("${service.internal.token:}")
    private String internalServiceToken;

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** 响应中用于归属校验的字段名（统一小写比较） */
    private static final Set<String> OWNER_FIELD_NAMES =
            Set.of("memberid", "member_id", "userid", "user_id");

    @PostConstruct
    public void init() {
        registerTool(Tool.builder()
                .name("search_products")
                .description("搜索商品信息，返回商品列表")
                .parameters("{\"keyword\": \"搜索关键词\", \"category\": \"分类\", \"page\": \"页码\"}")
                .executor((params, context) -> searchProducts(params, context))
                .build());

        registerTool(Tool.builder()
                .name("get_order_info")
                .description("查询订单信息，返回订单详情")
                .parameters("{\"order_sn\": \"订单编号\"}")
                .executor((params, context) -> getOrderInfo(params, context))
                .build());

        registerTool(Tool.builder()
                .name("create_after_sale")
                .description("创建售后工单")
                .parameters("{\"order_sn\": \"订单编号\", \"reason\": \"原因\", \"description\": \"描述\"}")
                .executor((params, context) -> createAfterSale(params, context))
                .build());
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {}", tool.getName());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 无身份上下文执行工具（兼容入口）。敏感工具会在此被拒绝。
     */
    public String executeTool(String name, String parameters) {
        return executeTool(name, parameters, null);
    }

    /**
     * 执行工具（主入口）
     *
     * @param name       工具名
     * @param parameters 参数 JSON
     * @param context    调用上下文（含用户身份）
     * @return 净化后的工具返回内容；被拒绝或异常时返回结构化错误信息
     */
    public String executeTool(String name, String parameters, ToolInvocationContext context) {
        ToolAccessGuard.Decision decision = toolAccessGuard.authorize(name, context);
        if (!decision.isAllowed()) {
            log.warn("工具调用被拒绝: tool={}, reason={}", name, decision.getReason());
            return "{\"error\": \"" + decision.getReason() + "\", \"blocked\": true}";
        }

        Tool tool = tools.get(name);
        if (tool == null) {
            return "Tool not found: " + name;
        }

        try {
            String rawResult = tool.getExecutor().execute(parameters, context);
            // 外部数据进入提示词前统一净化，阻断间接提示词注入
            return inputSanitizer.sanitizeToolObservation(rawResult);
        } catch (Exception e) {
            // 不回显内部异常细节，避免信息外泄
            log.error("Error executing tool {}: {}", name, e.getMessage());
            return "{\"error\": \"工具执行失败，请稍后重试\"}";
        }
    }

    // ==================== 具体工具实现 ====================

    private String searchProducts(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String keyword = (String) paramMap.getOrDefault("keyword", "");
            Integer pageNum = paramMap.containsKey("page") ? Integer.parseInt(paramMap.get("page").toString()) : 1;
            Integer pageSize = paramMap.containsKey("pageSize") ? Integer.parseInt(paramMap.get("pageSize").toString()) : 5;

            String url = mallSearchUrl + "/esProduct/search/simple?keyword={keyword}&pageNum={pageNum}&pageSize={pageSize}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("keyword", keyword);
            uriVariables.put("pageNum", pageNum - 1);
            uriVariables.put("pageSize", pageSize);

            return getWithAuth(url, uriVariables, context);
        } catch (Exception e) {
            log.error("搜索商品失败: {}", e.getMessage());
            return "{\"error\": \"搜索商品失败\"}";
        }
    }

    private String getOrderInfo(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = (String) paramMap.get("order_sn");

            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            // 数据隔离：将当前用户作为查询条件透传，由后端做归属过滤
            String url = mallPortalUrl + "/order/detail/{orderId}?memberId={memberId}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("orderId", orderSn);
            uriVariables.put("memberId", context.getMemberId());

            String response = getWithAuth(url, uriVariables, context);

            // 纵深防御：响应中若携带归属字段，本地再校验一次，防止后端接口漏鉴权造成越权
            if (!isOwnedByCurrentUser(response, context.getMemberId())) {
                log.warn("订单归属校验失败，疑似越权访问, orderSn={}, memberId={}", orderSn, context.getMemberId());
                return "{\"error\": \"无权访问该订单\", \"blocked\": true}";
            }
            return response;
        } catch (Exception e) {
            log.error("查询订单信息失败: {}", e.getMessage());
            return "{\"error\": \"查询订单信息失败\"}";
        }
    }

    private String createAfterSale(String params, ToolInvocationContext context) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = (String) paramMap.get("order_sn");

            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            String url = mallPortalUrl + "/returnApply/create";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("orderId", orderSn);
            requestBody.put("reason", paramMap.get("reason"));
            requestBody.put("description", paramMap.get("description"));
            // 数据隔离：写操作强制绑定当前用户，防止为他人创建工单
            requestBody.put("memberId", context.getMemberId());
            requestBody.put("status", 0);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, buildHeaders(context));
            String response = restTemplate.postForObject(url, entity, String.class);
            log.info("Create after sale completed for memberId={}", context.getMemberId());
            return response;
        } catch (Exception e) {
            log.error("创建售后工单失败: {}", e.getMessage());
            return "{\"error\": \"创建售后工单失败\"}";
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 带鉴权头的 GET 请求
     */
    private String getWithAuth(String url, Map<String, Object> uriVariables, ToolInvocationContext context) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(context));
        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, entity, String.class, uriVariables);
        return response.getBody();
    }

    /**
     * 构造请求头：内部服务令牌 + 用户 JWT（均为可选，取不到就不发送）
     */
    private HttpHeaders buildHeaders(ToolInvocationContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (internalServiceToken != null && !internalServiceToken.isBlank()) {
            headers.set(INTERNAL_TOKEN_HEADER, internalServiceToken);
        }
        if (context != null && context.getUserToken() != null && !context.getUserToken().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + context.getUserToken());
        }
        return headers;
    }

    /**
     * 归属校验：判断后端返回的数据是否属于当前用户。
     * <p>
     * 响应未携带归属字段时视为后端已按 memberId 过滤，放行；
     * 响应无法解析时按 fail-closed 拒绝，避免把未知内容当作合法数据返回给模型。
     */
    private boolean isOwnedByCurrentUser(String responseJson, String memberId) {
        if (responseJson == null || responseJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String owner = findOwnerId(root);
            if (owner == null) {
                log.debug("响应未包含归属字段，视为后端已完成过滤");
                return true;
            }
            return memberId.equals(owner);
        } catch (Exception e) {
            log.warn("订单响应解析失败，按拒绝处理");
            return false;
        }
    }

    /**
     * 在 JSON 中递归查找归属用户字段
     */
    private String findOwnerId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = entry.getValue();
                if (OWNER_FIELD_NAMES.contains(key) && value.isTextual()) {
                    return value.asText();
                }
            }
            Iterator<JsonNode> children = node.iterator();
            while (children.hasNext()) {
                String found = findOwnerId(children.next());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findOwnerId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
