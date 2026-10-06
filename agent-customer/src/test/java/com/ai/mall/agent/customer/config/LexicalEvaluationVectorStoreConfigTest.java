package com.ai.mall.agent.customer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LexicalEvaluationVectorStoreConfigTest {
    @Test void lexicalProfileNeedsNoMilvusClientAndFailsClosedOnVectorWrites() {
        new ApplicationContextRunner().withUserConfiguration(MilvusVectorStoreConfig.class,
                LexicalEvaluationVectorStoreConfig.class)
                .withPropertyValues("ai.rag.vector-store.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("milvusServiceClient"));
                    VectorStore store = context.getBean("vectorStore", VectorStore.class);
                    assertSame(store, context.getBean("memoryVectorStore"));
                    assertTrue(store.similaritySearch(SearchRequest.builder().query("query").build()).isEmpty());
                    assertThrows(IllegalStateException.class, () -> store.add(List.of()));
                });
    }
}
