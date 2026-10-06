package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/** Sensitive policy facts are quoted from verified current evidence, never synthesized as business state. */
public final class PolicyAnswerComposer {
    private PolicyAnswerComposer() {}
    public record Result(String answer, List<Document> sources) {}

    public static Result compose(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return null;
        List<String> terms = terms(query);
        StringBuilder answer = new StringBuilder("根据商城已公布的政策，相关原文如下：\n");
        List<Document> used = new ArrayList<>();
        var seen = new LinkedHashSet<String>();
        for (Document document : documents) {
            if (!document.isEvidenceVerified() || document.getContent() == null) continue;
            List<String> excerpts = new ArrayList<>();
            for (String paragraph : document.getContent().split("\\R+")) {
                String text = paragraph.trim();
                if (text.isEmpty() || text.startsWith("#") || terms.stream().noneMatch(text::contains)) continue;
                if (text.length() > 700) {
                    int end = text.lastIndexOf('。', 699);
                    if (end < 0) continue;
                    text = text.substring(0, end + 1);
                }
                if (seen.add(text)) excerpts.add(text);
                if (excerpts.size() >= 2) break;
            }
            if (excerpts.isEmpty()) continue;
            String block = "\n【政策依据 " + (used.size() + 1) + "】\n" + String.join("\n", excerpts) + "\n";
            if (answer.length() + block.length() > 1800) break;
            answer.append(block);
            used.add(document);
        }
        if (used.isEmpty()) return null;
        return new Result(answer.toString().trim(), List.copyOf(used));
    }

    private static List<String> terms(String query) {
        if (query == null) return List.of();
        if (query.contains("人为")) return List.of("人为");
        if (query.contains("保修") || query.contains("维修")) return List.of("保修", "维修");
        if (query.contains("同城")) return List.of("同城");
        if (query.contains("发票") || query.contains("开票")) return List.of("发票", "开票");
        if (query.contains("支付") || query.contains("付款") || query.contains("扣款")) return List.of("支付", "付款", "扣款");
        if (query.contains("运费") || query.contains("邮费")) return List.of("运费", "包邮");
        if (query.contains("退款") || query.contains("退货")) return List.of("退款", "退货");
        if (query.contains("积分") || query.contains("会员")) return List.of("积分", "会员");
        if (query.contains("优惠券")) return List.of("优惠券");
        return List.of("配送", "发货", "物流");
    }
}
