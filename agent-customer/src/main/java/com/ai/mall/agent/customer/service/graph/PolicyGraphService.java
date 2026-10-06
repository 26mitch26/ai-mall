package com.ai.mall.agent.customer.service.graph;

import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.service.telemetry.AgentTelemetry;
import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.*;

/** A local, versioned policy-topic graph. Paths expand retrieval; they never substitute for policy text. */
@Service
public class PolicyGraphService {
    private final RagService rag;
    private final boolean enabled;
    private final String uri,user,password;
    private Driver driver;
    private static final List<String> TOPICS=List.of("退款","退货","运费","优惠券","积分","赠品","保修","换货","发票");
    public record PathEvidence(String from, String to, String sharedTopic, String fromVersion, String toVersion) {}
    public record Result(String status, List<PathEvidence> paths, List<String> retrievalHints) {}
    public PolicyGraphService(RagService rag,@Value("${ai.rag.graph.enabled:false}") boolean enabled,
            @Value("${ai.rag.graph.uri:bolt://localhost:7687}") String uri,
            @Value("${ai.rag.graph.username:neo4j}") String user,@Value("${ai.rag.graph.password:}") String password) {
        this.rag=rag;this.enabled=enabled;this.uri=uri;this.user=user;this.password=password;
    }
    public Result retrieve(String query) {
        return AgentTelemetry.observed("graph",()->retrieveObserved(query));
    }
    private Result retrieveObserved(String query) {
        if (!enabled) return new Result("disabled",List.of(),List.of());
        List<String> topics=TOPICS.stream().filter(query::contains).toList();
        if (topics.size()<2) return new Result("single-topic",List.of(),List.of());
        long start=System.nanoTime();
        try {
            Driver graph=connection();
            List<Map<String,String>> documents=rag.listIndexedSources();
            List<Map<String,Object>> rows=new ArrayList<>();
            for (Map<String,String> summary:documents.stream().limit(200).toList()) {
                Map<String,String> document=rag.findFullSource(summary.get("source"));
                if (!document.getOrDefault("scope","public").equals("public")) continue;
                String content=document.getOrDefault("content","");
                rows.add(Map.of("source",document.getOrDefault("source",summary.get("source")),"version",document.getOrDefault("version","legacy"),
                        "topics",TOPICS.stream().filter(content::contains).toList()));
            }
            List<PathEvidence> paths;
            try (Session session=graph.session()) {
                // Only public published revisions returned by RagService are eligible; snapshot sources are passed in query.
                session.executeWrite(tx -> {
                    tx.run("UNWIND $rows AS row MERGE (p:MallPolicy {source:row.source,version:row.version}) WITH p,row UNWIND row.topics AS name MERGE (t:MallPolicyTopic {name:name}) MERGE (p)-[:MENTIONS]->(t)",Map.of("rows",rows)).consume();
                    return null;
                });
                paths=session.executeRead(tx -> tx.run("MATCH (a:MallPolicy)-[:MENTIONS]->(t:MallPolicyTopic)<-[:MENTIONS]-(b:MallPolicy) WHERE t.name IN $topics AND a.source < b.source AND any(r IN $rows WHERE r.source=a.source AND r.version=a.version) AND any(r IN $rows WHERE r.source=b.source AND r.version=b.version) RETURN DISTINCT a.source AS a,b.source AS b,t.name AS topic,a.version AS av,b.version AS bv LIMIT 8",Map.of("topics",topics,"rows",rows))
                        .list(record -> new PathEvidence(record.get("a").asString(),record.get("b").asString(),record.get("topic").asString(),record.get("av").asString(),record.get("bv").asString())));
            }
            AgentTelemetry.recordStage("graph",(System.nanoTime()-start)/1_000_000,"success");
            return new Result("ready",paths,paths.stream().map(PathEvidence::sharedTopic).distinct().toList());
        } catch (Exception ex) {
            AgentTelemetry.recordStage("graph",(System.nanoTime()-start)/1_000_000,"fallback");
            return new Result("unavailable-vector-fallback",List.of(),List.of());
        }
    }
    public List<Document> evidenceFor(Result result) {
        if (!"ready".equals(result.status())) return List.of();
        Map<String,String> expected = new LinkedHashMap<>();
        for (PathEvidence path:result.paths()) { expected.put(path.from(),path.fromVersion()); expected.put(path.to(),path.toVersion()); }
        List<Document> evidence = new ArrayList<>();
        for (var entry:expected.entrySet()) {
            if(evidence.size()>=4) break;
            Map<String,String> source=rag.findFullSource(entry.getKey());
            if(!entry.getValue().equals(source.getOrDefault("version","legacy")) || !source.getOrDefault("scope","public").equals("public")) continue;
            java.time.Instant effective=null;
            if(!source.getOrDefault("effectiveAt","").isBlank()) {
                try { effective=java.time.Instant.parse(source.get("effectiveAt")); } catch(Exception ex) { continue; }
                if(effective.isAfter(java.time.Instant.now())) continue;
            }
            String content=source.getOrDefault("content","");
            if(content.isBlank()) continue;
            evidence.add(Document.builder().id(source.getOrDefault("docId",UUID.nameUUIDFromBytes(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()))
                    .content(content).source(entry.getKey()).type(source.getOrDefault("type","policy")).version(source.get("version"))
                    .contentHash(source.get("contentHash")).scope("public").effectiveAt(effective).retrievalSource("graph").build());
        }
        return evidence;
    }
    private synchronized Driver connection() {
        if (driver==null) driver=GraphDatabase.driver(uri,AuthTokens.basic(user,password),Config.builder().withConnectionTimeout(2,java.util.concurrent.TimeUnit.SECONDS).withMaxTransactionRetryTime(2,java.util.concurrent.TimeUnit.SECONDS).build());
        return driver;
    }
    @PreDestroy public synchronized void close() { if(driver!=null) driver.close(); }
}
