package com.ai.mall.agent.customer.config;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Explicit read-only evaluation mode. Existing Redis lexical index is required. */
@Configuration
@ConditionalOnProperty(name = "ai.rag.vector-store.enabled", havingValue = "false")
public class LexicalEvaluationVectorStoreConfig {
    @Bean(name = {"vectorStore", "memoryVectorStore"})
    @Primary
    VectorStore disabledVectorStore() {
        return new VectorStore() {
            @Override public void add(List<Document> documents) { throw disabled(); }
            @Override public void delete(List<String> ids) { throw disabled(); }
            @Override public void delete(Filter.Expression filter) { throw disabled(); }
            @Override public List<Document> similaritySearch(SearchRequest request) { return List.of(); }
            private IllegalStateException disabled() {
                return new IllegalStateException("Vector writes are disabled in lexical evaluation mode");
            }
        };
    }
}
