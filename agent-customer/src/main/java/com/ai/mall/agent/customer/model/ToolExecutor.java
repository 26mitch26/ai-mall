package com.ai.mall.agent.customer.model;

@FunctionalInterface
public interface ToolExecutor {
    String execute(String parameters);
}
