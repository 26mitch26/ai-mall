package com.ai.mall.agent.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能运维 Agent。
 *
 * <p>{@code @EnableScheduling} 是必需的：MonitorAgent 的检测统计上报与指标采集轮询
 * 都基于 {@code @Scheduled}，缺了它这些方法就是永不触发的死代码。
 */
@EnableScheduling
@SpringBootApplication
public class AgentOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentOpsApplication.class, args);
    }
}
