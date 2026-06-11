package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiDefinition {
    private String path;
    private String method;
    private String summary;
    private List<Parameter> parameters;
    private Map<String, Response> responses;
}
