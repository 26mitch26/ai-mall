package com.ai.mall.common.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Function;

/**
 * 模型路由服务：根据熔断器状态自动路由到MiMo模型或本地备用模型
 * 支持自动降级和恢复
 */
@Service
public class ModelRouterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelRouterService.class);

    /** MiMo模型标识 */
    private static final String MODEL_MIMO = "mimo";
    /** 本地备用模型标识 */
    private static final String MODEL_LOCAL = "local";

    /** MiMo模型熔断器 */
    private final ModelCircuitBreaker mimoCircuitBreaker;
    /** 本地模型熔断器 */
    private final ModelCircuitBreaker localCircuitBreaker;

    /** MiMo模型调用器 */
    private Function<String, String> mimoCaller;
    /** 本地备用模型调用器 */
    private Function<String, String> localCaller;

    public ModelRouterService() {
        this.mimoCircuitBreaker = new ModelCircuitBreaker(5, 30_000, 3);
        this.localCircuitBreaker = new ModelCircuitBreaker(10, 60_000, 5);
    }

    public ModelRouterService(int mimoFailureThreshold, long mimoResetTimeoutMs, int mimoHalfOpenMaxAttempts,
                              int localFailureThreshold, long localResetTimeoutMs, int localHalfOpenMaxAttempts) {
        this.mimoCircuitBreaker = new ModelCircuitBreaker(mimoFailureThreshold, mimoResetTimeoutMs, mimoHalfOpenMaxAttempts);
        this.localCircuitBreaker = new ModelCircuitBreaker(localFailureThreshold, localResetTimeoutMs, localHalfOpenMaxAttempts);
    }

    /**
     * 注册MiMo模型调用器
     */
    public void registerMimoCaller(Function<String, String> caller) {
        this.mimoCaller = caller;
    }

    /**
     * 注册本地备用模型调用器
     */
    public void registerLocalCaller(Function<String, String> caller) {
        this.localCaller = caller;
    }

    /**
     * 根据熔断器状态路由到合适的模型
     * @return 被选中的模型标识
     */
    public String route() {
        // 优先使用MiMo模型
        if (mimoCircuitBreaker.isAvailable()) {
            LOGGER.debug("路由到MiMo模型，当前熔断器状态: {}", mimoCircuitBreaker.getState());
            return MODEL_MIMO;
        }
        // MiMo不可用时降级到本地模型
        if (localCircuitBreaker.isAvailable()) {
            LOGGER.info("MiMo模型不可用(状态:{})，降级到本地模型", mimoCircuitBreaker.getState());
            return MODEL_LOCAL;
        }
        // 两个模型都不可用，强制尝试本地模型
        LOGGER.warn("MiMo和本地模型均不可用，强制尝试本地模型");
        return MODEL_LOCAL;
    }

    /**
     * 带降级的模型调用
     * 优先调用MiMo模型，失败时自动降级到本地模型
     *
     * @param prompt 输入提示词
     * @return 模型输出结果
     */
    public String callWithFallback(String prompt) {
        String selectedModel = route();

        if (MODEL_MIMO.equals(selectedModel)) {
            try {
                String result = callModel(mimoCaller, prompt, MODEL_MIMO);
                mimoCircuitBreaker.recordSuccess();
                return result;
            } catch (Exception e) {
                LOGGER.error("MiMo模型调用失败: {}", e.getMessage());
                mimoCircuitBreaker.recordFailure();
                // 降级到本地模型
                return callLocalWithFallback(prompt);
            }
        } else {
            return callLocalWithFallback(prompt);
        }
    }

    /**
     * 调用本地模型（带熔断保护）
     */
    private String callLocalWithFallback(String prompt) {
        try {
            String result = callModel(localCaller, prompt, MODEL_LOCAL);
            localCircuitBreaker.recordSuccess();
            return result;
        } catch (Exception e) {
            LOGGER.error("本地模型调用失败: {}", e.getMessage());
            localCircuitBreaker.recordFailure();
            throw new RuntimeException("所有模型均不可用: MiMo状态=" + mimoCircuitBreaker.getState()
                    + ", 本地模型状态=" + localCircuitBreaker.getState(), e);
        }
    }

    /**
     * 执行模型调用
     */
    private String callModel(Function<String, String> caller, String prompt, String modelName) {
        if (caller == null) {
            throw new IllegalStateException(modelName + "模型调用器未注册");
        }
        LOGGER.info("调用{}模型，prompt长度: {}", modelName, prompt.length());
        return caller.apply(prompt);
    }

    /**
     * 获取当前路由状态信息
     */
    public Map<String, Object> getRouteStatus() {
        return Map.of(
                "mimoState", mimoCircuitBreaker.getState().name(),
                "mimoFailureCount", mimoCircuitBreaker.getFailureCount(),
                "localState", localCircuitBreaker.getState().name(),
                "localFailureCount", localCircuitBreaker.getFailureCount()
        );
    }

    public ModelCircuitBreaker getMimoCircuitBreaker() {
        return mimoCircuitBreaker;
    }

    public ModelCircuitBreaker getLocalCircuitBreaker() {
        return localCircuitBreaker;
    }
}
