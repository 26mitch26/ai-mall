package com.ai.mall.agent.customer.service.agent;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.evidence.EvidenceVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed, synthetic component regressions. Inputs are already retrieved; not a retrieval or semantic benchmark. */
class PolicyContextBenchmarkTest {
    private Document doc(String source,String content) {
        return Document.builder().id(source).source(source).content(content).version("fixture-v1")
                .contentHash("fixture-source-hash").knowledgeVersion("fixture-snapshot").evidenceVerified(true).build();
    }
    @Test void recordComponentBenchmark() throws Exception {
        List<Map<String,Object>> cases=new ArrayList<>();
        var shipping=doc("shipping.md","# 配送与运费\n配送时效：同城支持半日达，最快四小时送达。\n运费门槛：满99元免运费，未满99元普通地区运费8元。");
        var pay=doc("payment.md","# 支付常见问题\n支付方式：微信支付、支付宝；付款后订单状态更新为待发货。\n支付失败：确认余额后重试。");
        var refund=doc("refund.md","# 退货退款政策\n退款金额按原支付账户退回。\n退货操作路径：进入会员中心，选择订单，提交原因与说明。");
        var invoice=doc("invoice.md","# 发票服务\n电子发票在订单完成后发送到下单邮箱。");
        var result=PolicyAnswerComposer.compose("有哪些配送方式？",List.of(pay,shipping));
        add(cases,"shipping-no-payment-noise",result!=null&&!result.answer().contains("微信"));
        result=PolicyAnswerComposer.compose("支持哪些支付方式？",List.of(refund,pay));
        add(cases,"payment-no-refund-noise",result!=null&&!result.answer().contains("退款金额"));
        result=PolicyAnswerComposer.compose("退款操作和发票规则",List.of(refund,invoice));
        add(cases,"two-topics-covered",result!=null&&result.answer().contains("选择订单")&&result.answer().contains("下单邮箱"));
        var many=doc("refund-long.md","# 退货退款\n退款资格：七日内支持退货。\n退款金额：按实付金额退款。\n退款时间：审核后退回。\n退货操作路径：进入会员中心选择订单，提交退货原因。");
        result=PolicyAnswerComposer.compose("退款流程是什么？",List.of(many));
        add(cases,"procedure-not-buried",result!=null&&result.answer().contains("选择订单"));
        add(cases,"long-tail-refund-routed",PolicyQuestionIntent.matches("我不知道如何才能拿到退款"));
        result=PolicyAnswerComposer.compose("开票流程",List.of(doc("invoice-preview.md","# 发票\n发票种类：普通发票。\n开票流程：进入订单详情申请重发。")));
        String excerpt=result==null?"":result.sources().get(0).getContent();
        if(result!=null) try { excerpt=(String)Document.class.getMethod("getEvidenceExcerpt").invoke(result.sources().get(0)); } catch(NoSuchMethodException ignored) { }
        add(cases,"source-preview-matches-quote",excerpt!=null&&excerpt.contains("订单详情")&&!excerpt.contains("发票种类"));
        var verifier=new EvidenceVerifier();
        add(cases,"numeric-threshold-not-fee",verifier.verify("运费99元。",List.of(shipping)).unsupportedNumericClaims()==1);
        add(cases,"region-not-crossed",verifier.verify("偏远地区运费8元。",List.of(doc("fee.md","普通地区运费8元，偏远地区运费15元。"))).unsupportedNumericClaims()==1);
        add(cases,"negation-not-inverted",verifier.verify("可抵扣20%。",List.of(doc("limit.md","不可抵扣20%。"))).unsupportedNumericClaims()==1);
        add(cases,"exact-numeric-quote-supported",verifier.verify("普通地区运费8元。",List.of(doc("fee.md","普通地区运费8元。"))).unsupportedNumericClaims()==0);
        add(cases,"personal-refund-not-public",!PolicyQuestionIntent.matches("我的退款进度怎么查询？"));
        add(cases,"unverified-source-refused",PolicyAnswerComposer.compose("支付方式",List.of(Document.builder().content("微信支付").build()))==null);
        Path path=Path.of("target/policy-context-benchmark.json");
        Files.createDirectories(path.getParent());
        long passed=cases.stream().filter(c->Boolean.TRUE.equals(c.get("passed"))).count();
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("scope","12 synthetic component regressions; no model calls or full-RAG accuracy claims","passed",passed,"total",cases.size(),"cases",cases));
        System.out.println("Policy context regressions: "+passed+"/"+cases.size());
        assertEquals(cases.size(),passed, "A known policy evidence regression returned");
    }
    private void add(List<Map<String,Object>> cases,String id,boolean passed) {cases.add(Map.of("id",id,"passed",passed));}
}
