package com.ai.mall.agent.test.service.mcp;

/** 测试任务排队超出上限时抛出，避免 MCP 端点被当作压测入口。 */
public class McpRunRejectedException extends RuntimeException {

    public McpRunRejectedException(String message) {
        super(message);
    }
}
