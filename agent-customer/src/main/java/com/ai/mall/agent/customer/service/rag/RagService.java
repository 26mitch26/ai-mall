package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.security.InputSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final OpenAiChatModel mimoChatModel;
    private final InputSanitizer inputSanitizer;

    public List<Document> retrieve(String query, int topK) {
        log.info("Retrieving documents for query: {}", query);

        // TODO: 实现真实的向量检索（Milvus）
        // 这里先返回模拟数据
        List<Document> documents = new ArrayList<>();

        documents.add(Document.builder()
                .id("doc1")
                .content("我们支持7天无理由退货，15天换货服务。请保留好商品吊牌和包装。")
                .source("售后政策")
                .type("policy")
                .build());

        documents.add(Document.builder()
                .id("doc2")
                .content("订单支付后24小时内发货，支持顺丰、京东物流。")
                .source("物流政策")
                .type("policy")
                .build());

        return documents;
    }

    public String generateAnswer(String query, List<Document> documents) {
        // Sanitize query to prevent prompt injection
        String sanitizedQuery = inputSanitizer.sanitize(query);

        StringBuilder context = new StringBuilder();
        for (Document doc : documents) {
            context.append(doc.getContent()).append("\n");
        }

        String prompt = String.format("""
                你是一个专业的客服助手。根据以下参考资料回答用户问题。

                参考资料：
                %s

                用户问题：%s

                请用简洁友好的语气回答，如果资料中没有相关信息，请说明。
                """, context.toString(), sanitizedQuery);

        try {
            return mimoChatModel.call(prompt);
        } catch (Exception e) {
            log.error("Error generating answer: {}", e.getMessage());
            return "抱歉，暂时无法回答您的问题，请稍后再试。";
        }
    }
}
