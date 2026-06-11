package com.ai.mall.agent.customer.service.tool;

import com.ai.mall.agent.customer.model.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

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
        // TODO: 调用 mall-core 的商品搜索 API
        return "{\"products\": [{\"id\": 1, \"name\": \"测试商品\", \"price\": 99.99}]}";
    }

    private String getOrderInfo(String params) {
        // TODO: 调用 mall-core 的订单查询 API
        return "{\"order\": {\"order_sn\": \"123456\", \"status\": \"已发货\", \"amount\": 199.00}}";
    }

    private String createAfterSale(String params) {
        // TODO: 调用 mall-core 的售后工单 API
        return "{\"ticket_id\": \"AS20260601001\", \"status\": \"已创建\"}";
    }
}
