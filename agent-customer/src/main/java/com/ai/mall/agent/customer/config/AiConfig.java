package com.ai.mall.agent.customer.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${ai.model.mimo.api-key:${MIMO_API_KEY:}}")
    private String mimoApiKey;

    @Value("${ai.model.mimo.base-url:https://api.mimo.xiaomi.com/v1}")
    private String mimoBaseUrl;

    @Value("${ai.model.mimo.model:MiMo-7B}")
    private String mimoModel;

    @Value("${ai.model.openai.api-key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;

    @Value("${ai.model.openai.model:gpt-4}")
    private String openaiModel;

    @Bean
    public OpenAiChatModel mimoChatModel() {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(mimoApiKey)
                .baseUrl(mimoBaseUrl)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(mimoModel)
                .temperature(0.7)
                .maxTokens(2048)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public OpenAiChatModel openaiChatModel() {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(openaiApiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openaiModel)
                .temperature(0.7)
                .maxTokens(2048)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
