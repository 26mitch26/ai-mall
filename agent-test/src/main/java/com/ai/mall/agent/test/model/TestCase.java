package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCase {
    private String id;
    private String name;
    private String apiPath;
    private String method;
    private Map<String, Object> requestParams;
    private int expectedStatusCode;
    private String expectedResponse;
    private String description;
}
