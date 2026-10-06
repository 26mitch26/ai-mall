package com.ai.mall.agent.customer.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.ToString;

/** Optional request-only generation configuration. Credentials are never persisted. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatModelConfig {
    private String provider;
    private String model;
    private String baseUrl;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean thinkingSupported;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long modelSize;
    @ToString.Exclude
    private String apiKey;
}
