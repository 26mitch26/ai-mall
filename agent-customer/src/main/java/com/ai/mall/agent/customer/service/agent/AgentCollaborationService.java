package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.AgentCollaboration;
import com.ai.mall.agent.customer.model.CollaborationPlan;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** 客服领域的分流编排器，记录接待、专家与汇总 Agent 的协作过程。 */
@Service
public class AgentCollaborationService {

    public CollaborationPlan plan(String message) {
        String query = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String active = route(query);
        List<AgentCollaboration> agents = List.of(
                node("router", "接待分流", "识别意图并分派服务角色", "completed", true),
                node("order", "订单服务", "查询订单、退换货与物流", active.equals("order") ? "completed" : "standby", active.equals("order")),
                node("technical", "技术支持", "定位故障并给出处理方案", active.equals("technical") ? "completed" : "standby", active.equals("technical")),
                node("complaint", "投诉处理", "安抚情绪、记录反馈并闭环", active.equals("complaint") ? "completed" : "standby", active.equals("complaint")),
                node("coordinator", "回复生成", "融合知识库与检索结果后回复", "completed", true)
        );
        String activeName = agents.stream()
                .filter(AgentCollaboration::isActive)
                .map(AgentCollaboration::getName)
                .filter(name -> !"接待分流".equals(name) && !"回复生成".equals(name))
                .findFirst()
                .orElse("接待分流");
        return CollaborationPlan.builder()
                .routeSummary("接待分流 → " + activeName + " → 回复生成")
                .activeAgent(active)
                .agents(agents)
                .build();
    }

    private String route(String query) {
        if (containsAny(query, "订单", "物流", "快递", "运单", "查单", "发货")) return "order";
        if (containsAny(query, "故障", "报错", "坏了", "维修", "安装", "无法使用", "技术")) return "technical";
        if (containsAny(query, "投诉", "不满", "举报", "客服态度", "维权")) return "complaint";
        return "order";
    }

    private boolean containsAny(String query, String... keywords) {
        for (String keyword : keywords) if (query.contains(keyword)) return true;
        return false;
    }

    private AgentCollaboration node(String id, String name, String description, String status, boolean active) {
        return AgentCollaboration.builder()
                .id(id)
                .name(name)
                .role(id.equals("router") ? "接待" : id.equals("coordinator") ? "编排" : "专家")
                .description(description)
                .status(status)
                .active(active)
                .build();
    }
}
