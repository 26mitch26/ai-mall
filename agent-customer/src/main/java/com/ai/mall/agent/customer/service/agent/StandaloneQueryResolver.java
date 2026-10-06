package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.ChatMessage;
import java.util.List;
import java.util.regex.Pattern;

/** Cheap follow-up resolution. Keeps the original wording and never uses assistant text as business facts. */
public final class StandaloneQueryResolver {
    private static final Pattern FOLLOW_UP = Pattern.compile("^(那|那么|它|这个|刚才|还有|运费呢|多久呢|退款呢).*");
    private StandaloneQueryResolver() {}
    public static String resolve(String query, List<ChatMessage> history) {
        if (query == null || history == null || query.length() > 80 || !FOLLOW_UP.matcher(query.trim()).matches()) return query;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (!"user".equals(message.getRole()) || message.getContent() == null) continue;
            String previous = message.getContent().trim();
            if (previous.isBlank() || FOLLOW_UP.matcher(previous).matches()) continue;
            // Only the previous user question supplies the referent, not model-generated claims.
            return previous.substring(0, Math.min(200, previous.length())) + "；追问：" + query;
        }
        return query;
    }
}
