package com.ai.mall.agent.customer.service.tool;

import com.ai.mall.agent.customer.model.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private RestTemplate restTemplate;

    @Value("${service.mall-search.url:http://localhost:8081}")
    private String mallSearchUrl;

    @Value("${service.mall-portal.url:http://localhost:8085}")
    private String mallPortalUrl;

    @PostConstruct
    public void init() {
        registerTool(Tool.builder()
                .name("search_products")
                .description("搜索商品信息，返回商品列表")
                .parameters("{\"keyword\": \"搜索关键词\", \"category\": \"分类\", \"page\": \"页码\"}")
                .executor(params -> searchProducts(params))
                .build());

        registerTool(Tool.builder()
                .name("get_order_info")
                .description("查询订单信息，返回订单详情")
                .parameters("{\"order_sn\": \"订单编号\"}")
                .executor(params -> getOrderInfo(params))
                .build());

        registerTool(Tool.builder()
                .name("create_after_sale")
                .description("创建售后工单")
                .parameters("{\"order_sn\": \"订单编号\", \"reason\": \"原因\", \"description\": \"描述\"}")
                .executor(params -> createAfterSale(params))
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

    public String executeTool(String name, String parameters) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Tool not found: " + name;
        }
        try {
            return tool.getExecutor().execute(parameters);
        } catch (Exception e) {
            log.error("Error executing tool {}: {}", name, e.getMessage());
            return "Error executing tool: " + e.getMessage();
        }
    }

    private String searchProducts(String params) {
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

            String response = restTemplate.getForObject(url, String.class, uriVariables);
            log.info("Search products response: {}", response);
            return response;
        } catch (Exception e) {
            log.error("搜索商品失败: {}", e.getMessage());
            return "{\"error\": \"搜索商品失败: " + e.getMessage() + "\"}";
        }
    }

    private String getOrderInfo(String params) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = (String) paramMap.get("order_sn");

            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            String url = mallPortalUrl + "/order/detail/{orderId}";
            Map<String, Object> uriVariables = new HashMap<>();
            uriVariables.put("orderId", orderSn);

            String response = restTemplate.getForObject(url, String.class, uriVariables);
            log.info("Get order info response: {}", response);
            return response;
        } catch (Exception e) {
            log.error("查询订单信息失败: {}", e.getMessage());
            return "{\"error\": \"查询订单信息失败: " + e.getMessage() + "\"}";
        }
    }

    private String createAfterSale(String params) {
        try {
            Map<String, Object> paramMap = objectMapper.readValue(params, new TypeReference<>() {});
            String orderSn = (String) paramMap.get("order_sn");
            String reason = (String) paramMap.get("reason");
            String description = (String) paramMap.get("description");

            if (orderSn == null || orderSn.isEmpty()) {
                return "{\"error\": \"订单编号不能为空\"}";
            }

            String url = mallPortalUrl + "/returnApply/create";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("orderId", orderSn);
            requestBody.put("reason", reason);
            requestBody.put("description", description);
            requestBody.put("status", 0);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(url, entity, String.class);
            log.info("Create after sale response: {}", response);
            return response;
        } catch (Exception e) {
            log.error("创建售后工单失败: {}", e.getMessage());
            return "{\"error\": \"创建售后工单失败: " + e.getMessage() + "\"}";
        }
    }
}
