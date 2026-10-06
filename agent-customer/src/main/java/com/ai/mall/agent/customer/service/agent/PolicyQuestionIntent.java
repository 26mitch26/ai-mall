package com.ai.mall.agent.customer.service.agent;

/** Public policy questions do not require a tool planning loop. Business actions keep their earlier routes. */
public final class PolicyQuestionIntent {
    private PolicyQuestionIntent() {}
    public static boolean matches(String text) {
        if (text == null || text.contains("AM-DEMO-") || text.matches(".*\\d{9,}.*")) return false;
        if (text.contains("我的订单") || text.contains("我的退款") || text.contains("退款进度")) return false;
        if (text.matches(".*(?:如何|怎么|怎样).{0,10}(?:退款|退货).*")) return true;
        return text.matches(".*(?:退款政策|退货政策|退货条件|无理由退货|退款要多久|退款多久|退款时效|支付方式|付款方式|支付问题|付款问题|支付失败|配送方式|配送时效|同城|省内|外省|(?:多久|几天).{0,6}(?:送到|收到|到货|能到)|运费|邮费|保修|维修|人为损坏|发票|开票|会员权益|积分规则|优惠券规则).*" );
    }
}
