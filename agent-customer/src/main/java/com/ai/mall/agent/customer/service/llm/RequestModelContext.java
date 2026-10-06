package com.ai.mall.agent.customer.service.llm;

import com.ai.mall.agent.customer.model.ChatModelConfig;
import java.util.LinkedHashSet;
import java.util.Set;

/** Generation overrides are isolated to a chat worker, never a global model mutation. */
public final class RequestModelContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private RequestModelContext() {}
    private static class State {
        String provider, model, endpoint, key;
        Boolean thinking;
        Long bytes;
        boolean probe;
        boolean explicit;
        final Set<String> usedModels = new LinkedHashSet<>();
    }
    public static Scope open(ChatModelConfig config, String defaultModel) {
        State previous = CURRENT.get();
        State state = new State();
        state.provider = config == null ? "ollama" : config.getProvider();
        state.model = config == null ? defaultModel : config.getModel();
        state.explicit = config != null;
        if (config != null) { state.endpoint = config.getBaseUrl(); state.key = config.getApiKey(); state.thinking=config.getThinkingSupported(); state.bytes=config.getModelSize(); }
        CURRENT.set(state);
        return new Scope(previous, state);
    }
    public static String modelOr(String fallback) { return CURRENT.get() == null ? fallback : CURRENT.get().model; }
    public static boolean cloud() { return CURRENT.get() != null && "openai-compatible".equals(CURRENT.get().provider); }
    public static String endpoint() { return CURRENT.get() == null ? null : CURRENT.get().endpoint; }
    public static String key() { return CURRENT.get() == null ? null : CURRENT.get().key; }
    public static boolean sendThinkFlag() { return CURRENT.get()==null || !Boolean.FALSE.equals(CURRENT.get().thinking); }
    public static Long modelSize() { return CURRENT.get()==null ? null : CURRENT.get().bytes; }
    public static boolean probe() { return CURRENT.get()!=null && CURRENT.get().probe; }
    public static boolean explicit() { return CURRENT.get()!=null && CURRENT.get().explicit; }
    public static String cacheNamespace() { return CURRENT.get() == null ? "" : "|model:" + CURRENT.get().provider + ":" + CURRENT.get().model; }
    public static void used(String model) { if (CURRENT.get() != null) CURRENT.get().usedModels.add(model); }
    public static final class Scope implements AutoCloseable {
        private final State previous, current;
        private Scope(State previous, State current) { this.previous=previous; this.current=current; }
        public String selectedModel() { return current.model; }
        public String provider() { return current.provider; }
        public void probeMode() { current.probe=true; }
        public java.util.List<String> usedModels() { return java.util.List.copyOf(current.usedModels); }
        @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); current.key=null; current.endpoint=null; }
    }
}
