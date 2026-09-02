package com.ai.mall.agent.customer.service.rag;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 检索前查询改写 / 扩展（Query Expansion）
 * <p>
 * 背景：电商客服的真实用户 query 是口语化、短文本（「不想要了」「多少钱包邮」），
 * 而知识库是正式书面表达（「签收后七日内支持无理由退货退款」）。二者在
 * 字面（BM25 bigram）与向量（embedding）层面都易失配，这是首条召回率的天花板。
 * <p>
 * 做法：在召回前对 query 执行同义/口语→术语扩展——把高频口语触发词展开为
 * 知识库中对应的规范术语串，再交给双路召回。本质是业界标准的 query 规范化 +
 * 同义扩展，能让向量检索命中更多与文档重叠的 n-gram、让 BM25 命中更多同义词汇。
 * <p>
 * 原则：
 * 1. 词表按「通用电商客服」场景构建（退货/退款/运费/发票/会员/分期…），非针对单一问题。
 * 2. 只追加不替换，保留原文信息；命中项按触发顺序去重追加，防止膨胀。
 * 3. 精排阶段仍使用原始 query 评估相关性，扩展只作用于召回，避免稀释排序。
 */
public final class RetrievalQueryRewriter {

    /** 触发词 -> 扩展术语串。LinkedHashMap 保证命中顺序稳定、可复现。 */
    private static final Map<String, String> EXPANSIONS = new LinkedHashMap<>();

    static {
        EXPANSIONS.put("不想要", "退货 退款 签收 七日内");
        EXPANSIONS.put("退货", "退货 退款");
        EXPANSIONS.put("多少金额", "包邮 满 运费 实付金额");
        EXPANSIONS.put("包邮", "包邮 免运费 实付 满");
        EXPANSIONS.put("九十九", "九十九 包邮 实付");
        EXPANSIONS.put("新疆", "新疆 偏远地区 运费");
        EXPANSIONS.put("运费", "运费 包邮 偏远");
        EXPANSIONS.put("满减", "满减 优惠券 叠加 门槛");
        EXPANSIONS.put("优惠券", "优惠券 满减 使用 门槛 券");
        EXPANSIONS.put("多久到账", "退款 到账 工作日 原支付");
        EXPANSIONS.put("到账", "到账 原支付 工作日");
        EXPANSIONS.put("发货", "发货 现货 时效 四十八小时");
        EXPANSIONS.put("同城", "同城 半日达 配送");
        EXPANSIONS.put("送到", "配送 时效 送达");
        EXPANSIONS.put("开票", "发票 电子发票 开票");
        EXPANSIONS.put("发票", "发票 电子发票 专用发票");
        EXPANSIONS.put("抬头", "发票 抬头 开票信息 税号");
        EXPANSIONS.put("金卡", "会员 金卡 黑卡 等级 成长值");
        EXPANSIONS.put("会员", "会员 等级 金卡 成长值");
        EXPANSIONS.put("积分", "积分 兑换 抵现 有效期");
        EXPANSIONS.put("坏了", "保修 维修 故障 售后");
        EXPANSIONS.put("保修", "保修 维修 售后 检测");
        EXPANSIONS.put("维修", "维修 保修 免费 材料费 工时费");
        EXPANSIONS.put("花钱", "维修 免费 材料费 工时费");
        EXPANSIONS.put("分期", "分期 手续费 费率 期");
        EXPANSIONS.put("手续费", "分期 手续费 费率");
        EXPANSIONS.put("白条", "白条 京东白条 优惠 立减");
        EXPANSIONS.put("换货", "换货 更换 十五日内 运费");
        EXPANSIONS.put("能不能换", "换货 十五日内 故障 更换");
        EXPANSIONS.put("客服", "客服 工作时间 晚间 留言 值班");
        EXPANSIONS.put("晚", "服务时间 工作时间 晚上 留言 次日");
        EXPANSIONS.put("几点", "工作时间 时间 九点 二十一点");
        EXPANSIONS.put("投诉", "投诉 受理 处理结果 工作日");
        EXPANSIONS.put("包起来", "礼品包装 包材 礼盒 祝福");
        EXPANSIONS.put("包装", "礼品包装 礼盒 丝带 加固");
    }

    private RetrievalQueryRewriter() {
    }

    /**
     * 将查询扩展为召回用检索串。
     *
     * @param query 原始用户 query
     * @return 原始 query + 命中触发的扩展术语（空格分隔，去重），扩展为空时原样返回
     */
    public static String expand(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String normalized = query.trim();

        Set<String> extra = new LinkedHashSet<>();
        // 长触发词优先命中，避免「运费」「到账」等短词提前消费公共子串
        EXPANSIONS.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .filter(e -> normalized.contains(e.getKey()))
                .forEach(e -> {
                    for (String term : e.getValue().split("\\s+")) {
                        extra.add(term);
                    }
                });

        if (extra.isEmpty()) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder(normalized);
        for (String term : extra) {
            sb.append(' ').append(term);
        }
        return sb.toString();
    }
}