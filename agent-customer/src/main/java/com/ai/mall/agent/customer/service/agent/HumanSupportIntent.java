package com.ai.mall.agent.customer.service.agent;

import java.util.Locale;

/** Explicit customer control takes precedence over generation. No CRM integration is claimed. */
public final class HumanSupportIntent {
    private HumanSupportIntent() {}

    public static final String GUIDANCE = "当前演示环境尚未接入人工客服，未转接会话，也未创建工单。"
            + "请通过商城公布的客服渠道联系人工客服，并准备订单编号和问题说明。";

    public static boolean matches(String query) {
        if (query == null) return false;
        String text = query.toLowerCase(Locale.ROOT).trim();
        if (text.matches(".*(?:不要|不用|不想|无需).{0,6}(?:人工|真人).*")) return false;
        if (text.matches(".*(?:do not|don't|no need).{0,15}(?:human|live agent).*")) return false;
        return text.matches(".*(?:转人工|真人客服|(?:联系|找|转接|接通|呼叫).{0,6}人工|人工客服(?:在哪|怎么联系)).*")
                || text.matches(".*(?:human agent|live agent|human support|speak (?:with|to) (?:a |an )?(?:person|someone|human)).*");
    }
}
