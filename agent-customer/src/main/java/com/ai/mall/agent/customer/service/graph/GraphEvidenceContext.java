package com.ai.mall.agent.customer.service.graph;

import com.ai.mall.agent.customer.model.Document;
import java.util.List;

/** Request-scoped graph retrieval candidates. Never stores data across users or requests. */
public final class GraphEvidenceContext {
    private static final ThreadLocal<List<Document>> CURRENT = new ThreadLocal<>();
    private GraphEvidenceContext() {}
    public static List<Document> currentDocuments() { List<Document> docs=CURRENT.get(); return docs==null ? List.of() : docs; }
    public static Scope open(List<Document> documents) { List<Document> previous=CURRENT.get(); CURRENT.set(List.copyOf(documents)); return new Scope(previous); }
    public static final class Scope implements AutoCloseable {
        private final List<Document> previous;
        private Scope(List<Document> previous) { this.previous=previous; }
        @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
