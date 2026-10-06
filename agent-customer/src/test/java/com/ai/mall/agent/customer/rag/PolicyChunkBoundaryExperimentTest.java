package com.ai.mall.agent.customer.rag;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic structural boundary experiment. No embeddings, model calls or semantic accuracy claim. */
class PolicyChunkBoundaryExperimentTest {
    record Pair(String rule,String exception) {}
    @Test void compareWholeFixedSentenceAndPolicySectionWithLongInputs() throws Exception {
        List<Pair> pairs=new ArrayList<>();
        StringBuilder text=new StringBuilder("# 商城政策实验夹具\n");
        int[] lengths={40,80,120,180,240,310,420,550};
        for(int i=0;i<lengths.length;i++) {
            String rule="场景"+i+"退货规则："+"适用说明".repeat(lengths[i]/4)+"，签收七日内支持退货。";
            String exception="例外：场景"+i+"的食品、定制商品和人为损坏不适用。";
            text.append("## 场景").append(i).append("\n").append(rule).append("\n\n").append(exception).append("\n");
            pairs.add(new Pair(rule,exception));
        }
        String corpus=text.toString();
        var doc=Document.builder().id("boundary-fixture").source("boundary.md").content(corpus).build();
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(score("whole-document",corpus.length(),List.of(corpus),pairs));
        for(String strategy:List.of("fixed_size","sentence","policy_section")) for(int size:new int[]{128,256,512}) {
            var chunks=RagService.DocumentChunker.chunkWithMetadata(doc,strategy,size,64).stream().map(RagService.DocumentChunk::getContent).toList();
            rows.add(score(strategy,size,chunks,pairs));
        }
        var output=Map.of("scope","Eight synthetic long-rule/exception pairs; structural coverage only; development regression, not blind retrieval or answer accuracy",
                "corpusCharacters",corpus.length(),"rulePairs",pairs.size(),"modelCalls",0,"rows",rows);
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/policy-chunk-boundary.json").toFile(),output);
        assertTrue(corpus.length()>2000);
        for(var row:rows) if("policy_section".equals(row.get("strategy"))) assertEquals(pairs.size(),row.get("completeRulePairs"));
        assertTrue(rows.stream().anyMatch(r -> "fixed_size".equals(r.get("strategy"))&&(int)r.get("completeRulePairs")<pairs.size()));
    }
    private Map<String,Object> score(String strategy,int target,List<String> chunks,List<Pair> pairs) {
        int complete=(int)pairs.stream().filter(p -> chunks.stream().anyMatch(c -> c.contains(p.rule())&&c.contains(p.exception()))).count();
        return Map.of("strategy",strategy,"targetCharacters",target,"chunks",chunks.size(),"completeRulePairs",complete,
                "pairCoverage",(double)complete/pairs.size(),"maxCharacters",chunks.stream().mapToInt(String::length).max().orElse(0),
                "averageCharacters",chunks.stream().mapToInt(String::length).average().orElse(0),
                "totalIndexedCharacters",chunks.stream().mapToInt(String::length).sum());
    }
}
