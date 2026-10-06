package com.ai.mall.agent.customer.service.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded, request-local traces. No prompts, tokens, user IDs or order numbers are metric labels. */
@Component
public class AgentTelemetry {
    private static final ThreadLocal<RequestTrace> CURRENT = new ThreadLocal<>();
    private static volatile MeterRegistry meters;
    private static volatile ObservationRegistry observations = ObservationRegistry.NOOP;
    private static volatile io.micrometer.tracing.Tracer tracer;
    private static final Set<String> STAGES = Set.of("chat", "context", "rewrite", "retrieve", "rerank", "generate", "llm", "tool", "evidence", "workflow", "graph", "vision", "cache");

    public AgentTelemetry(MeterRegistry registry, ObservationRegistry observationRegistry,
                          org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracerProvider) {
        meters = registry;
        observations = observationRegistry;
        tracer=tracerProvider.getIfAvailable();
    }

    public static Scope open() {
        return open(6);
    }

    public static Scope open(int maxModelCalls) {
        RequestTrace previous = CURRENT.get();
        Observation observation = Observation.createNotStarted("agent.customer.request", observations).start();
        Observation.Scope observationScope = observation.openScope();
        String traceId=tracer!=null && tracer.currentSpan()!=null ? tracer.currentSpan().context().traceId() : UUID.randomUUID().toString();
        RequestTrace trace = new RequestTrace(traceId);
        trace.maxCalls = Math.max(0, Math.min(6, maxModelCalls));
        CURRENT.set(trace);
        return new Scope(previous, trace, observation, observationScope);
    }

    public static boolean reserveModelCall() {
        RequestTrace trace=CURRENT.get();
        if(trace==null) return true;
        if(trace.reservedCalls >= trace.maxCalls) return false;
        trace.reservedCalls++;
        return true;
    }

    public static <T> T observed(String stage, java.util.function.Supplier<T> work) {
        String safeStage=STAGES.contains(stage)?stage:"other";
        return Observation.createNotStarted("agent.customer."+safeStage,observations).observe(work);
    }

    public static void recordStage(String stage, long millis, String outcome) {
        String safeStage = STAGES.contains(stage) ? stage : "other";
        String safeOutcome = Set.of("success", "failure", "fallback", "refusal", "hit", "miss").contains(outcome) ? outcome : "other";
        RequestTrace trace = CURRENT.get();
        if (trace != null && trace.stages.size() < 64) trace.stages.add(new Stage(safeStage, Math.max(0, millis), safeOutcome));
        MeterRegistry registry = meters;
        if (registry != null) Timer.builder("agent.customer.stage").tags("stage", safeStage, "outcome", safeOutcome)
                .publishPercentileHistogram().register(registry).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    public static void recordLlm(long millis, long inputTokens, long outputTokens, String outcome) {
        recordStage("llm", millis, outcome);
        RequestTrace trace = CURRENT.get();
        if (trace != null) {
            trace.modelCalls++;
            trace.inputTokens += Math.max(0, inputTokens);
            trace.outputTokens += Math.max(0, outputTokens);
        }
        if (meters != null) {
            meters.counter("agent.customer.model.calls", "outcome", "success".equals(outcome) ? "success" : "failure").increment();
            if (inputTokens >= 0) meters.counter("agent.customer.model.tokens", "direction", "input").increment(inputTokens);
            if (outputTokens >= 0) meters.counter("agent.customer.model.tokens", "direction", "output").increment(outputTokens);
        }
    }

    public record Stage(String name, long durationMs, String outcome) {}
    public record Summary(String traceId, List<Stage> stages, int modelCalls, long inputTokens, long outputTokens) {}

    private static final class RequestTrace {
        private final String id;
        private final List<Stage> stages = new ArrayList<>();
        private int modelCalls;
        private long inputTokens;
        private long outputTokens;
        private int maxCalls=6, reservedCalls;
        private RequestTrace(String id) { this.id = id; }
    }

    public static final class Scope implements AutoCloseable {
        private final RequestTrace previous, trace;
        private final Observation observation;
        private final Observation.Scope observationScope;
        private Scope(RequestTrace previous, RequestTrace trace, Observation observation, Observation.Scope observationScope) {
            this.previous = previous; this.trace = trace; this.observation = observation; this.observationScope = observationScope;
        }
        public Summary summary() { return new Summary(trace.id, List.copyOf(trace.stages), trace.modelCalls, trace.inputTokens, trace.outputTokens); }
        @Override public void close() {
            observationScope.close(); observation.stop();
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
