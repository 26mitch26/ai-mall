package com.ai.mall.agent.customer.service.agent;

import java.util.Locale;

/**
 * 寒暄/问候类输入的确定性短路：这类消息没有信息需求，不该进入 RAG 检索——
 * 检索必然弱证据，进而触发"证据不足拒答转人工"，把"您好"答成"知识库无资料"。
 * 与 HumanSupportIntent 同层的显式意图处理，命中后直接回复欢迎语，不走检索与生成。
 */
public final class GreetingIntent {
    private GreetingIntent() {}

    public static final String ANSWER =
            "您好！我是智能客服小助手，可以帮您解答退换货、物流配送、支付发票、会员权益等政策问题，"
                    + "也可以帮您查询订单、办理售后。请问有什么可以帮您？";

    public static final String IDENTITY_ANSWER =
            "我是 AI-Mall 的智能客服助手，基于商城知识库与真实订单数据回答问题：政策类问题带来源引用，"
                    + "订单、售后等操作调用真实业务接口，不凭空编造。可以问我退换货、物流、发票、会员权益，"
                    + "也可以让我查订单、办售后。有什么可以帮您？";

    public static final String CHAT_ANSWER =
            "当然可以呀！很乐意陪你聊聊～不过我先自我介绍一下：我是商城的客服助手，最擅长的是"
                    + "解答退换货、物流、会员权益这些问题，也能帮你查订单、办售后。你想聊点什么？";

    /** 身份回复的个性化插槽（"你认识我吗"：登录答"认识"，游客引导登录） */
    public static final String IDENTITY_MEMBER_PREFIX = "认识呀，";
    public static final String IDENTITY_MEMBER_FALLBACK =
            "认识呀，您已登录会员账号，我可以直接帮您查订单、办售后。有什么可以帮您？";
    public static final String IDENTITY_GUEST_REPLY =
            "我还不知道您是谁呢～登录之后我就能帮您查订单、办售后啦。政策类问题不用登录也可以直接问我哦。";

    /** 身份回复是动态组装的（含个性化插槽），用前缀判定而不是全等比对 */
    public static boolean isIdentityReply(String answer) {
        return answer != null && (answer.startsWith(IDENTITY_MEMBER_PREFIX)
                || answer.startsWith(IDENTITY_GUEST_REPLY));
    }

    /**
     * 闲聊漏网的分层兜底（业界 best practice：按失败类型分层话术，不暴露"知识库检索失败"）。
     * 第一层：承认局限 + 能力菜单；同一会话第二次触发升级为第二层话术。
     */
    public static final String FALLBACK_GUIDANCE_FIRST =
            "这个问题超出了我的业务范围～我是商城的智能客服助手，可以帮您："
                    + "解答退换货、物流配送、支付发票、会员权益等政策问题；查询订单、办理售后。"
                    + "换个业务问题试试？比如\"退货政策是什么\"或\"查一下我的订单\"。";

    public static final String FALLBACK_GUIDANCE_REPEAT =
            "看来我还没能理解您的意思。我能帮您处理商城业务：政策咨询（如\"退货政策是什么\"）、"
                    + "订单查询与售后（如\"查一下我的订单\"）。其他需求当前演示环境未接入人工客服，"
                    + "请通过商城公布的客服渠道反馈。";

    /** 业务关键词守卫：命中说明用户有真实业务诉求，兜底走"诚实拒答"而非"闲聊引导" */
    public static final String BUSINESS_HINT =
            ".*(订单|退款|退货|换货|售后|发票|物流|快递|发货|运费|地址|支付|付款|账户|密码|登录|注册|会员|积分|优惠|折扣|优惠券|库存|商品|购物车|下单|取消|价格|便宜|余额|verify|order|refund|return|invoice|payment|coupon|product|cart|deliver).*";

    public static boolean isBusinessQuery(String query) {
        return query != null && query.toLowerCase(Locale.ROOT).matches(BUSINESS_HINT);
    }

    /** 寒暄/身份类闲聊的统一短路入口；命中返回固定回复，未命中返回 null。 */
    public static String shortCircuit(String query) {
        if (matches(query)) return ANSWER;
        if (matchesIdentity(query)) return IDENTITY_ANSWER;
        return null;
    }

    public static boolean matches(String query) {
        if (query == null) return false;
        String text = query.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！!？?~～.,、]", "");
        if (text.isEmpty() || text.length() > 12) return false;
        // 纯寒暄词组合（如"您好""在吗""谢谢""哈喽你好"）；
        // "你好我想退货"这类带实质诉求的短句不会命中（"我想退货"不在词表内）。
        return text.matches("^(您好|你好|您家好|hello|hi|嗨|哈喽|嘿|在吗|在么|在不|早上好|中午好|下午好|晚上好|晚安|谢谢|多谢|感谢|thanks|thankyou)+$")
                || text.matches("^(您好|你好|哈喽|嗨|嘿)[呀啊哈哟呦哇]?$");
    }

    /** 身份/能力询问：没有业务信息需求，同样不该进检索。 */
    public static boolean matchesIdentity(String query) {
        if (query == null) return false;
        String text = query.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！!？?~～.,、？]", "");
        if (text.isEmpty() || text.length() > 20) return false;
        return text.matches(".*(你是谁|你叫什么|你是什么|自我介绍|介绍下?你自己?|你是机器|你是ai|你是真人|是不是机器|你会什么|你能干[什么嘛吗]|你能做[什么嘛]|你都会[什么嘛]|whoareyou|whatareyou|whatisyou|introduceyourself|whatcanyoudo).*");
    }
}
