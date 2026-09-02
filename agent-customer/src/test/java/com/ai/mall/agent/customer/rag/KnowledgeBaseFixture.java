package com.ai.mall.agent.customer.rag;

import com.ai.mall.agent.customer.model.Document;

import java.util.List;

/**
 * RAG 召回率评测集（离线可复现）
 * <p>
 * 内容：12 篇智能客服高频知识文档（退货/运费/优惠券/物流/发票/会员/售后/支付/换货/客服时间/包装/分期）
 * + 24 条仿真咨询。gold 标注到"原始文档"级别：检索返回的 chunk 属于该文档即算命中。
 * <p>
 * query 覆盖三类真实形态：
 * - 语义型（向量检索优势）：同义表达，如「不想要了/退货」「几天到账/多久到账」
 * - 精确型（BM25 优势）：具体门槛与数字，如「九十九」「八折」
 * - 混合型：实体 + 意图组合
 * 因此能客观对比「纯向量」与「向量+BM25+RRF+重排」两套链路的召回差异。
 */
public final class KnowledgeBaseFixture {

    public record QueryGold(String query, String goldDocId) {
    }

    private KnowledgeBaseFixture() {
    }

    public static List<Document> documents() {
        return List.of(
                doc("refund_policy", """
                        订单签收后七日内，支持无理由退货退款。商品需保持完好，吊牌未剪，不影响二次销售。食品、贴身内衣、定制类商品不支持无理由退货。退款金额以实际支付金额为准，不含运费。符合退货条件的订单，在申请通过后一到三个工作日内退款到原支付账户。如果商品有质量问题，运费由我们承担，请您先拍照联系在线客服登记。
                        """),
                doc("shipping_fee", """
                        全场实付金额满九十九元包邮。不足九十九元时，普通地区运费八元，偏远地区（新疆、西藏、内蒙古部分地区）运费十五元。生鲜商品使用冷链专线，单独计费。大件家具家电采用物流专线配送，运费按体积重量计算，下单页会明确展示。包邮门槛以优惠后的实付金额计算，不包含运费与税费。
                        """),
                doc("coupon", """
                        优惠券分为满减券、折扣券和品类券。满减券有使用门槛，例如满两百减五十，结算时实付金额达到门槛即可使用。每笔订单最多使用一张优惠券，不可与平台满减活动叠加，但可与积分抵扣同时使用。优惠券有有效期，过期自动失效，不支持补发。折扣券部分商品不参与，具体以商品详情页标注为准。
                        """),
                doc("delivery_time", """
                        现货商品付款后四十八小时内发货，预售商品按页面承诺时间发货。同城订单采用半日达，最快四小时送达。省内一般一到两天，外省二到四天，偏远地区三到五天。发货后可在订单详情查看物流轨迹，物流信息每两小时更新一次。遇大促期间发货时效顺延，请您理解。
                        """),
                doc("invoice", """
                        支持开具增值税普通发票和增值税专用发票。电子发票在订单完成后一个工作日内发送到您的邮箱。纸质发票随货寄出或单独邮寄，由您在下单时选择。发票抬头可在提交订单前修改，订单支付后如需修改抬头，请在收货前联系客服处理。专票需要提供完整的开票信息，包括税号、注册地址、开户行及账号。
                        """),
                doc("membership", """
                        会员按成长值分为铜卡、银卡、金卡、黑卡四个等级。购物每消费一元获得一点成长值，评价商品额外获得两点。金卡会员享运费券每月两张，黑卡会员享专属客服与免费上门退换货。生日当月可领取八折生日券一张。会员积分可在积分商城兑换礼品或抵扣现金。
                        """),
                doc("after_sale", """
                        自签收之日起，电器类商品享有整机一年保修，主要部件三年保修。保修期内非人为损坏免费维修，人为损坏收取材料费与工时费。维修流程：先在订单详情提交售后申请，选择维修，寄回商品，我们检测后四十八小时内出具检测报告。维修完成后原路寄回，往返运费由我们承担。
                        """),
                doc("payment", """
                        支持支付宝、微信支付、银联云闪付、花呗分期与京东白条。花呗分期支持三期、六期、十二期，部分商品可免息分期。白条新用户首单立减十元。支付失败时将在十五分钟内自动释放库存，您可重新下单。企业客户支持对公转账，需联系商务开通。
                        """),
                doc("exchange", """
                        七日内无理由退货，十五日内出现功能性故障可免费换货。换货商品需保持无人为损坏，配件齐全。换货流程：申请换货→寄回商品→我们检测确认→发出新品。换货产生的往返运费由我们承担。由于款式或尺码问题换货，运费由买家承担。换货时效与新品发货时效一致。
                        """),
                doc("service_hours", """
                        在线客服工作时间：周一至周日，上午九点至晚上九点。夜间咨询可留言，我们将在次日第一时间回复。紧急问题可拨打二十四小时服务热线九五零幺零。投诉建议会在一个工作日内受理并在三个工作日内给出处理结果。大促期间咨询量较大，回复可能有延迟。
                        """),
                doc("gift_wrap", """
                        提供免费礼品包装服务，下单时选择需要礼品包装即可。礼品包装包含礼盒、丝带与祝福卡片，祝福内容可在备注中填写。贵重物品建议同时购买包装加固服务，费用三元。部分大件商品不支持礼品包装。包装不影响七天无理由退货。
                        """),
                doc("installment", """
                        单笔订单实付金额满一千元可申请分期付款。支持三期零费率、六期与十二期。分期费率：三期零费率，六期百分之三，十二期百分之六。分期手续费在每期账单中均摊。分期申请需信用良好，逾期会影响征信记录。分期商品同样享受完整售后服务。
                        """));
    }

    /**
     * 干扰文档：与正式文档主题相近、表述相似但细节不同，
     * 用于检验检索链路在高相似度知识库中的区分能力（防止"过得去因为太简单"）。
     */
    public static List<Document> distractorDocuments() {
        return List.of(
                doc("return_vs_exchange_guide", """
                        退货和换货是两个不同的售后动作。退货是把商品退回并退款，订单关闭；换货是退回旧品补发新品，订单继续。七天内两种都可以发起；超过七天但在十五天内，仅支持质量问题的换货。保证金类商品与定制商品不支持退换。发起售后前请您先在订单详情确认商品状态是否满足条件。
                        """),
                doc("shipping_insurance", """
                        运费险由保险公司承保，退货时用于补偿退回运费。实付金额满九十九元的商品赠送运费险，理赔限额为八元，超出部分自理。退货时您先垫付运费，商家确认收货后保险公司在七十二小时内赔付到支付账户。未投保订单的退回运费需要买家承担，除非是商品质量问题。
                        """),
                doc("points_cashback", """
                        会员积分可以抵现，每五十积分抵一元，单笔订单最多抵用实付金额的百分之十。积分抵现与优惠券可同时使用，但不能与满减活动叠加。积分在购物和评价时获得，钻石会员额外获得百分二十加成。积分有效期两年，过期作废，请您及时使用。
                        """),
                doc("sale_season_after_sale", """
                        大促期间产生的订单同样适用常规售后政策，受理时效会略有延长。大促订单的退货退款时效从签收日起计算，无理由退货保持七天。促销赠品需同主商品一并退回，否则影响退款金额。人工客服接待量增大，售后申请建议优先通过订单详情页自助提交，响应不超过二十四小时。
                        """));
    }

    /** 全量知识库：正式文档 + 干扰文档（用于评测，考验检索区分度） */
    public static List<Document> fullKnowledgeBase() {
        java.util.List<Document> all = new java.util.ArrayList<>(documents());
        all.addAll(distractorDocuments());
        return all;
    }

    public static List<QueryGold> queries() {
        return List.of(
                new QueryGold("我想退货，东西不想要了，怎么办", "refund_policy"),
                new QueryGold("退款多久能到账", "refund_policy"),
                new QueryGold("退款几天到账", "refund_policy"),
                new QueryGold("多少金额才能包邮", "shipping_fee"),
                new QueryGold("九十九块钱能包邮吗", "shipping_fee"),
                new QueryGold("新疆运费多少钱", "shipping_fee"),
                new QueryGold("优惠券能不能和满减一起用", "coupon"),
                new QueryGold("满减券怎么用，有门槛吗", "coupon"),
                new QueryGold("下单后几天发货", "delivery_time"),
                new QueryGold("同城多久能送到", "delivery_time"),
                new QueryGold("怎么开发票，电子发票什么时候发", "invoice"),
                new QueryGold("发票抬头能改吗", "invoice"),
                new QueryGold("会员分几个等级，金卡有什么好处", "membership"),
                new QueryGold("积分有什么用", "membership"),
                new QueryGold("手机保修多久，坏了怎么修", "after_sale"),
                new QueryGold("维修要花钱吗", "after_sale"),
                new QueryGold("能分期付款吗，手续费高不高", "installment"),
                new QueryGold("分期有什么要求，信用不好能办吗", "installment"),
                new QueryGold("白条有优惠吗", "payment"),
                new QueryGold("十五天内坏了能换吗", "exchange"),
                new QueryGold("换货运费谁出", "exchange"),
                new QueryGold("客服几点下班，晚上有人吗", "service_hours"),
                new QueryGold("投诉怎么处理，多久有结果", "service_hours"),
                new QueryGold("买礼物能帮忙包起来吗，要钱吗", "gift_wrap"));
    }

    private static Document doc(String id, String content) {
        return Document.builder()
                .id(id)
                .content(content.trim())
                .source("客服知识库/FAQ")
                .type("text")
                .build();
    }
}