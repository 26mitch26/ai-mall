package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Parameter {
    private String name;
    private String in;
    private String type;
    private boolean required;
    private String description;
}
