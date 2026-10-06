package com.ai.mall.agent.customer.service.agent;
import com.ai.mall.agent.customer.model.Document;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded query-focused extraction; not semantic entailment. */
public final class PolicyAnswerComposer {
    private PolicyAnswerComposer() {}
    public record Result(String answer,List<Document> sources) {}
    private record Facet(List<String> topics,List<String> focus,boolean procedure) {}
    private record Paragraph(String text,double score,int order) {}
    private record Selection(Document source,List<Paragraph> paragraphs,double score) {}
    public static Result compose(String query,List<Document> documents) {
        if(documents==null || documents.isEmpty()) return null;
        List<Facet> facets=facets(query);
        if(facets.isEmpty()) return null;
        Map<String,Document> originals=new LinkedHashMap<>();
        Map<String,LinkedHashSet<String>> excerpts=new LinkedHashMap<>();
        for(Facet facet:facets) {
            Selection best=null;
            for(Document document:documents) {
                if(!document.isEvidenceVerified() || document.getContent()==null) continue;
                List<Paragraph> ranked=new ArrayList<>(); int order=0;
                for(String line:com.ai.mall.agent.customer.service.rag.PolicySectionChunker.evidenceUnits(document.getContent())) {
                    String text=line.trim(); int position=order++;
                    if(text.isBlank() || text.startsWith("#")) continue;
                    long topics=facet.topics.stream().filter(text::contains).count();
                    long focused=facet.focus.stream().filter(text::contains).count();
                    if(topics==0 || (!facet.focus.isEmpty() && focused==0)) continue;
                    if(text.length()>700) continue; // Do not quote a prefix that silently drops a later exception.
                    double score=topics+focused*4;
                    if(facet.procedure && text.matches("^(?:[^：:]{0,12})?(?:流程|操作路径|申请)[：:].*")) score+=6;
                    ranked.add(new Paragraph(text,score,position));
                }
                ranked.sort(Comparator.comparingDouble(Paragraph::score).reversed().thenComparingInt(Paragraph::order));
                if(ranked.isEmpty()) continue;
                var selected=new ArrayList<>(ranked.subList(0,Math.min(2,ranked.size())));
                double score=ranked.get(0).score+Math.min(1,Math.max(0,document.getScore()))*.1;
                var candidate=new Selection(document,selected,score);
                if(best==null || candidate.score>best.score) best=candidate;
            }
            if(best==null) return null; // Never silently omit a requested facet.
            String key=String.valueOf(best.source.getSource())+":"+String.valueOf(best.source.getVersion());
            Document previous=originals.get(key);
            if(previous==null) originals.put(key,best.source);
            else if(!previous.getContent().equals(best.source.getContent()))
                originals.put(key,previous.toBuilder().content(previous.getContent()+"\n"+best.source.getContent()).build());
            var snippets=excerpts.computeIfAbsent(key,k->new LinkedHashSet<>());
            best.paragraphs.stream().sorted(Comparator.comparingInt(Paragraph::order)).forEach(p->snippets.add(p.text));
        }
        if(originals.size()>3) return null;
        StringBuilder answer=new StringBuilder("根据商城已公布的政策，相关原文如下：\n");
        List<Document> used=new ArrayList<>();
        for(String key:originals.keySet()) {
            String snippet=String.join("\n",excerpts.get(key));
            String block="\n【政策依据 "+(used.size()+1)+"】\n"+snippet+"\n";
            if(answer.length()+block.length()>1800) return null;
            answer.append(block);
            used.add(originals.get(key).toBuilder().evidenceExcerpt(snippet).build());
        }
        return used.isEmpty()?null:new Result(answer.toString().trim(),List.copyOf(used));
    }
    private static List<Facet> facets(String query) {
        if(query==null) return List.of(); int follow=query.lastIndexOf("追问：");
        String q=follow<0?query:query.substring(follow+3);
        List<Facet> result=new ArrayList<>();
        if(has(q,"发票","开票")) add(result,q,List.of("发票","开票"),List.of("发票","开票"),List.of());
        if(has(q,"支付","付款","扣款")) {
            List<String> focus=has(q,"方式","方法")?List.of("支付方式","微信","支付宝"):
                has(q,"问题","失败","重复","扣款")?List.of("失败","扣款","问题"):List.of();
            add(result,q,List.of("支付","付款","扣款"),List.of("支付","付款"),focus);
        }
        if(has(q,"退款","退货")) add(result,q,List.of("退款","退货"),List.of("退款","退货"),
                has(q,"多久","到账","时效")?List.of("退款时效","退款到账","工作日","到账时间"):List.of());
        if(has(q,"运费","邮费","包邮")) result.add(new Facet(List.of("运费","包邮"),
            has(q,"质量","退货","换货")?List.of("退货运费","退回运费","质量问题","换货"):
            has(q,"门槛","包邮","满")?List.of("门槛","包邮","免运费"):List.of(),false));
        if(has(q,"保修","维修","人为")) result.add(new Facet(List.of("保修","维修","人为"),has(q,"人为")?List.of("人为"):List.of(),false));
        if(has(q,"积分","会员","成长值")) result.add(new Facet(List.of("积分","会员","成长值"),List.of(),false));
        if(has(q,"优惠券","促销")) result.add(new Facet(List.of("优惠券","促销"),List.of(),false));
        if(has(q,"配送","送到","收到","能到","到货","同城","省内","外省","发货")) result.add(new Facet(List.of("配送","送达","物流","发货","出库"),
            has(q,"同城")?List.of("同城"):has(q,"发货")?List.of("发货","出库"):List.of("配送","送达"),false));
        return result;
    }
    private static void add(List<Facet> facets,String q,List<String> topics,List<String> names,List<String> focus) {
        String topic="(?:"+String.join("|",names)+")";
        boolean procedure=Pattern.compile("(?:如何|怎么|怎样).{0,10}"+topic+"|"+topic+".{0,6}(?:流程|操作|步骤|如何|怎么)").matcher(q).find();
        facets.add(new Facet(topics,procedure?List.of("流程","操作","提交","选择订单","订单详情","会员中心"):focus,procedure));
    }
    private static boolean has(String q,String... terms) {return Arrays.stream(terms).anyMatch(q::contains);}
}
