package com.ai.mall.agent.test.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssertionDetail {
    private String assertionName;
    private boolean passed;
    private String expected;
    private String actual;
    private String message;
}