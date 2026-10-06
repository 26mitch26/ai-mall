package com.ai.mall.agent.customer.service.rag;

import java.util.List;

/** Configurable query/passage scoring contract. Implementations must return one finite score per passage. */
public interface Reranker {
    List<Double> score(String query, List<String> passages) throws Exception;
}
