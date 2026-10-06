package com.ai.mall.agent.customer.service.llm;

import com.ai.mall.agent.customer.model.ChatModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;

@Service
public class OllamaModelCatalog {
    public record Entry(String name, long size, String parameterSize, boolean canChat, boolean thinking) {}
    public record Listing(String defaultModel, List<Entry> models) {}
    private final URI origin;
    private final String defaultModel;
    private final RestTemplate http;
    private final CloudEndpointPolicy cloudEndpoints = new CloudEndpointPolicy();
    private List<Entry> cached = List.of();
    private long cachedAt;
    public OllamaModelCatalog(@Value("${ai.model.llm.base-url:http://localhost:11434/api/chat}") String url,
            @Value("${ai.model.llm.model:qwen3.5-noVL:latest}") String defaultModel) {
        this.origin=URI.create(url); this.defaultModel=defaultModel;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        this.http=new RestTemplate(factory);
    }
    public synchronized Listing list(boolean refresh) {
        if (!refresh && cachedAt>0 && System.currentTimeMillis()-cachedAt<30000) return new Listing(defaultModel,cached);
        try {
            JsonNode tags=http.getForObject(origin.resolve("/api/tags"),JsonNode.class);
            List<Entry> entries=new ArrayList<>();
            if(tags!=null) for(JsonNode tag:tags.path("models")) {
                String name=tag.path("name").asText();
                if(name.isBlank()) continue;
                var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
                JsonNode show=http.postForObject(origin.resolve("/api/show"),new HttpEntity<>(Map.of("model",name),headers),JsonNode.class);
                boolean completion=false, thinking=false;
                if(show!=null) for(JsonNode cap:show.path("capabilities")) {
                    if("completion".equals(cap.asText())) completion=true;
                    if("thinking".equals(cap.asText())) thinking=true;
                }
                entries.add(new Entry(name,tag.path("size").asLong(),tag.path("details").path("parameter_size").asText(),completion,thinking));
            }
            entries.sort(Comparator.comparing(Entry::name));
            cached=List.copyOf(entries); cachedAt=System.currentTimeMillis();
            return new Listing(defaultModel,cached);
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"无法读取本地 Ollama 模型，请检查服务",e); }
    }
    public ChatModelConfig validate(ChatModelConfig requested) {
        if(requested==null) return null;
        String provider=requested.getProvider()==null?"ollama":requested.getProvider().trim().toLowerCase(Locale.ROOT);
        String name=requested.getModel()==null?"":requested.getModel().trim();
        if(name.isEmpty() && "ollama".equals(provider)) return null;
        if(!name.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"模型名不合法");
        if("ollama".equals(provider)) {
            Entry entry=list(false).models().stream().filter(m -> m.name().equals(name) || m.name().equals(name+":latest")).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"模型未安装；请先在 Ollama 安装或创建"));
            if(!entry.canChat()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"该模型不支持聊天，请选择生成模型");
            return ChatModelConfig.builder().provider(provider).model(entry.name()).thinkingSupported(entry.thinking()).modelSize(entry.size()).build();
        }
        if(!"openai-compatible".equals(provider)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支持该接口类型");
        String key=requested.getApiKey()==null?"":requested.getApiKey().trim();
        if(key.isEmpty() || key.length()>4096 || key.contains("\n") || key.contains("\r"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写有效 API Key");
        if(name.equals(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请把模型名和 API Key 分别填写");
        return ChatModelConfig.builder().provider(provider).model(name).baseUrl(cloudEndpoints.validate(requested.getBaseUrl())).apiKey(key).build();
    }
}
