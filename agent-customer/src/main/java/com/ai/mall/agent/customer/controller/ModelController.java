package com.ai.mall.agent.customer.controller;
import com.ai.mall.agent.customer.service.llm.OllamaModelCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/models")
public class ModelController {
    private final OllamaModelCatalog catalog;
    private final com.ai.mall.agent.customer.service.llm.AgentLlmClient llm;
    @GetMapping public OllamaModelCatalog.Listing models(@RequestParam(defaultValue="false") boolean refresh) { return catalog.list(refresh); }
    @PostMapping("/test")
    public org.springframework.http.ResponseEntity<java.util.Map<String,Object>> test(@RequestBody com.ai.mall.agent.customer.model.ChatModelConfig requested) {
        var config=catalog.validate(requested);
        if(config==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"请先选择模型");
        try(var scope=com.ai.mall.agent.customer.service.llm.RequestModelContext.open(config,null);
            var trace=com.ai.mall.agent.customer.service.telemetry.AgentTelemetry.open(1)) {
            scope.probeMode();
            String reply=llm.chat("这是连接测试。请只回复：连接成功。不要输出其他内容。");
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("selectedModel",scope.selectedModel(),"provider",scope.provider(),"usedModels",scope.usedModels(),
                    "reply",reply.length()>256?reply.substring(0,256):reply,"trace",trace.summary()));
        } catch(IllegalStateException failure) {
            return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("code",503,"message",failure.getMessage()));
        }
    }
}
