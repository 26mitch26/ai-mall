package com.ai.mall.agent.customer.service.workflow;

import com.ai.mall.agent.customer.model.Document;
import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.ai.mall.agent.customer.model.workflow.AfterSaleWorkflowState;
import com.ai.mall.agent.customer.service.rag.RagService;
import com.ai.mall.agent.customer.service.security.OperationFingerprint;
import com.ai.mall.agent.customer.service.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Durable, resumable after-sale workflow with owner checks and atomic versioned transitions. */
@Service
@RequiredArgsConstructor
public class AfterSaleWorkflowService {
    private static final long TTL_DAYS = 7;
    private static final DefaultRedisScript<Long> CAS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1; end; return 0;", Long.class);

    private final ToolRegistry toolRegistry;
    private final RagService ragService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${agent.workflow.execution-timeout-ms:60000}")
    private long executionTimeoutMs = 60000;

    public AfterSaleWorkflowState prepare(ToolInvocationContext context, String orderSn, String reason, String description) {
        requireOwner(context);
        if (orderSn == null || orderSn.isBlank() || orderSn.length() > 64) throw new IllegalArgumentException("订单号无效");
        String normalizedReason = reason == null || reason.isBlank() ? "用户申请售后" : reason.trim();
        String normalizedDescription = description == null ? "" : description.trim();
        if (normalizedReason.length() > 256 || normalizedDescription.length() > 2000) throw new IllegalArgumentException("售后说明过长");
        String hash = sha256(orderSn.trim().toUpperCase() + "\n" + normalizedReason + "\n" + normalizedDescription);

        String lookup = toolRegistry.executeStructuredTool("get_order_info", json(Map.of("order_sn", orderSn.trim())), context);
        JsonNode orderRoot = readJson(lookup);
        JsonNode orderData = orderRoot.path("data");
        if (orderRoot.has("error") || orderRoot.path("blocked").asBoolean(false)
                || orderRoot.path("code").asInt(0) != 200 || !orderData.isObject()
                || orderData.path("orderSn").asText().isBlank()
                || !context.getMemberId().equals(orderData.path("memberId").asText())) {
            throw new IllegalStateException("无法核实订单归属或状态，暂不能准备售后申请");
        }
        String orderSummary = orderData.toString();

        String policyQuery = "售后政策 " + normalizedReason + " " + normalizedDescription;
        RagService.RetrievalOutcome evidence = ragService.retrieveWithEvidence(policyQuery, 4);
        List<Document> documents = evidence.documents();
        String policy = evidence.weakEvidence()
                ? RagService.NO_CONTEXT_ANSWER
                : ragService.generateAnswer(policyQuery, documents);
        String draft = "售后申请草稿\n订单：" + orderSn.trim() + "\n原因：" + normalizedReason +
                (normalizedDescription.isBlank() ? "" : "\n说明：" + normalizedDescription) +
                "\n政策参考：" + policy +
                (evidence.weakEvidence() ? "\n当前资料不足以判断申请资格，需人工核验。" : "") +
                "\n请确认后提交；确认前不会创建售后工单。";

        Instant now = Instant.now();
        AfterSaleWorkflowState state = AfterSaleWorkflowState.builder()
                .taskId(UUID.randomUUID().toString()).workflowType("after_sale").ownerId(context.getMemberId())
                .sessionId(context.getSessionId()).orderSn(orderSn.trim()).reason(normalizedReason)
                .description(normalizedDescription).requestHash(hash).orderSummary(orderSummary)
                .policyAnswer(policy).policyEvidenceWeak(evidence.weakEvidence())
                .policySourceIds(documents.stream().map(Document::getId).filter(java.util.Objects::nonNull).toList())
                .policySourceVersions(documents.stream().map(Document::getVersion).filter(java.util.Objects::nonNull).distinct().toList())
                .draft(draft).status("WAITING_CONFIRMATION").version(1)
                .operationId(UUID.randomUUID().toString()).createdAt(now).updatedAt(now).build();
        String key = key(state.getTaskId());
        Boolean saved = redis.opsForValue().setIfAbsent(key, json(state), TTL_DAYS, TimeUnit.DAYS);
        if (!Boolean.TRUE.equals(saved)) throw new IllegalStateException("无法持久化售后工作流");
        return state;
    }

    public AfterSaleWorkflowState prepareCancellation(ToolInvocationContext context, String orderSn) {
        requireOwner(context);
        if (orderSn == null || orderSn.isBlank() || orderSn.length() > 64) throw new IllegalArgumentException("订单号无效");
        String response = toolRegistry.executeStructuredTool("get_order_info", json(Map.of("order_sn", orderSn.trim())), context);
        JsonNode root = readJson(response);
        JsonNode order = root.path("data");
        if (root.path("code").asInt(0) != 200 || !order.isObject() || !orderSn.trim().equalsIgnoreCase(order.path("orderSn").asText())
                || !context.getMemberId().equals(order.path("memberId").asText()) || order.path("status").asInt(-1) != 0) {
            throw new IllegalStateException("只能为当前账号的待付款订单准备取消申请");
        }
        Instant now = Instant.now();
        String hash = sha256("cancel\n" + orderSn.trim().toUpperCase());
        AfterSaleWorkflowState state = AfterSaleWorkflowState.builder().taskId(UUID.randomUUID().toString())
                .workflowType("order_cancellation").ownerId(context.getMemberId()).sessionId(context.getSessionId())
                .orderSn(orderSn.trim()).requestHash(hash).orderSummary(order.toString())
                .draft("订单取消申请草稿\n订单：" + orderSn.trim() + "\n当前状态：待付款\n确认后将取消订单并释放库存。")
                .status("WAITING_CONFIRMATION").version(1).operationId(UUID.randomUUID().toString())
                .createdAt(now).updatedAt(now).build();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(state.getTaskId()), json(state), TTL_DAYS, TimeUnit.DAYS))) {
            throw new IllegalStateException("无法持久化订单取消工作流");
        }
        return state;
    }

    public AfterSaleWorkflowState confirm(ToolInvocationContext context, String taskId, long expectedVersion, boolean approved) {
        AfterSaleWorkflowState current = get(context, taskId);
        if (!"WAITING_CONFIRMATION".equals(current.getStatus())) return current;
        if (expectedVersion < 1) throw new IllegalArgumentException("无效的工作流版本");
        if (current.getVersion() != expectedVersion) throw new IllegalStateException("工作流版本已变化，请先刷新状态");
        Instant now = Instant.now();
        if (!approved) {
            current.setStatus("REJECTED");
            current.setVersion(current.getVersion() + 1);
            current.setUpdatedAt(now);
            cas(current, expectedVersion);
            return get(context, taskId);
        }

        // Claim before the external write. Concurrent/replayed confirmations cannot cross this transition.
        current.setStatus("EXECUTING");
        current.setVersion(current.getVersion() + 1);
        current.setUpdatedAt(now);
        cas(current, expectedVersion);
        ToolInvocationContext approvedContext = ToolInvocationContext.builder()
                .sessionId(current.getSessionId()).memberId(current.getOwnerId()).userToken(context.getUserToken())
                .operationId(current.getOperationId()).operationHash(current.getRequestHash()).writeApproved(true).build();
        String toolName = "order_cancellation".equals(current.getWorkflowType()) ? "cancel_order" : "create_after_sale";
        Map<String, Object> frozenParams = new java.util.TreeMap<>();
        frozenParams.put("order_sn", current.getOrderSn());
        if (!"order_cancellation".equals(current.getWorkflowType())) {
            frozenParams.put("reason", current.getReason());
            frozenParams.put("description", current.getDescription());
        }
        String params = json(frozenParams);
        approvedContext.setOperationHash(OperationFingerprint.hash(params));
        String result;
        try {
            result = toolRegistry.executeStructuredTool(toolName, params, approvedContext);
        } catch (RuntimeException e) {
            markUnknown(current.getTaskId(), current.getOwnerId(), "执行结果未知，请查询 operationId=" + current.getOperationId());
            return get(context, taskId);
        }
        JsonNode response = readJson(result);
        current = get(context, taskId);
        if (!"EXECUTING".equals(current.getStatus())) return current;
        current.setResult(result);
        current.setUpdatedAt(Instant.now());
        current.setVersion(current.getVersion() + 1);
        if (response.has("error") || response.path("blocked").asBoolean(false) || (!response.path("code").isMissingNode() && response.path("code").asInt(500) != 200)) {
            // A transport/tool error does not prove whether the remote write committed. Never retry blindly.
            current.setStatus("UNKNOWN");
        } else {
            current.setStatus("COMPLETED");
        }
        replaceOwned(current);
        return current;
    }

    public AfterSaleWorkflowState get(ToolInvocationContext context, String taskId) {
        requireOwner(context);
        String raw = redis.opsForValue().get(key(taskId));
        if (raw == null) throw new IllegalArgumentException("工作流不存在或已过期");
        AfterSaleWorkflowState state = read(raw, AfterSaleWorkflowState.class);
        if (!context.getMemberId().equals(state.getOwnerId())) throw new SecurityException("无权访问此工作流");
        boolean executionExpired = "EXECUTING".equals(state.getStatus()) && state.getUpdatedAt() != null
                && Instant.now().toEpochMilli() - state.getUpdatedAt().toEpochMilli() >= executionTimeoutMs;
        if ("UNKNOWN".equals(state.getStatus()) || executionExpired) reconcile(context, state);
        String refreshed = redis.opsForValue().get(key(taskId));
        if (refreshed != null) state = read(refreshed, AfterSaleWorkflowState.class);
        return state;
    }

    private void reconcile(ToolInvocationContext context, AfterSaleWorkflowState state) {
        boolean cancellation = "order_cancellation".equals(state.getWorkflowType());
        String lookup = cancellation
                ? toolRegistry.executeStructuredTool("get_order_info", json(Map.of("order_sn", state.getOrderSn())), context)
                : toolRegistry.lookupOperation("after_sale", state.getOperationId(), context);
        JsonNode root = readJson(lookup);
        JsonNode operation = root.path("data");
        if (cancellation) {
            boolean verifiedOrder = root.path("code").asInt(0) == 200 && operation.isObject()
                    && state.getOrderSn().equalsIgnoreCase(operation.path("orderSn").asText())
                    && state.getOwnerId().equals(operation.path("memberId").asText());
            if (verifiedOrder && operation.path("status").asInt(-1) == 4) {
                state.setStatus("COMPLETED");
                state.setResult(lookup);
                state.setVersion(state.getVersion() + 1);
                state.setUpdatedAt(Instant.now());
                replaceOwned(state);
                return;
            }
            if ("UNKNOWN".equals(state.getStatus())) return;
            state.setStatus("UNKNOWN");
            state.setResult("订单取消执行结果未知；当前订单尚未确认关闭，不会自动重试。operationId=" + state.getOperationId());
            state.setVersion(state.getVersion() + 1);
            state.setUpdatedAt(Instant.now());
            replaceOwned(state);
            return;
        }
        String remoteStatus = operation.path("status").asText();
        if ("PROCESSING".equals(remoteStatus)) return;
        if (!"COMPLETED".equals(remoteStatus) && "UNKNOWN".equals(state.getStatus())) return;
        state.setStatus("COMPLETED".equals(remoteStatus) ? "COMPLETED" : "UNKNOWN");
        if ("COMPLETED".equals(remoteStatus)) {
            state.setResult(operation.path("result").isMissingNode() ? lookup : operation.path("result").toString());
        } else if (state.getResult() == null || state.getResult().isBlank()) {
            state.setResult("执行结果未知，请核对后端操作记录；不会自动重试。operationId=" + state.getOperationId());
        }
        state.setVersion(state.getVersion() + 1);
        state.setUpdatedAt(Instant.now());
        replaceOwned(state);
    }

    private void markUnknown(String taskId, String owner, String detail) {
        String raw = redis.opsForValue().get(key(taskId));
        if (raw == null) return;
        AfterSaleWorkflowState state = read(raw, AfterSaleWorkflowState.class);
        if (!owner.equals(state.getOwnerId()) || !"EXECUTING".equals(state.getStatus())) return;
        state.setStatus("UNKNOWN"); state.setResult(detail); state.setVersion(state.getVersion() + 1); state.setUpdatedAt(Instant.now());
        Long updated = redis.execute(CAS_SCRIPT, List.of(key(taskId)), raw, json(state), String.valueOf(TTL_DAYS * 86400));
        if (!Long.valueOf(1).equals(updated)) return;
    }

    private void cas(AfterSaleWorkflowState changed, long expectedVersion) {
        String k = key(changed.getTaskId());
        String raw = redis.opsForValue().get(k);
        if (raw == null) throw new IllegalStateException("工作流不存在或已过期");
        AfterSaleWorkflowState actual = read(raw, AfterSaleWorkflowState.class);
        if (!changed.getOwnerId().equals(actual.getOwnerId()) || actual.getVersion() != expectedVersion ||
                !"WAITING_CONFIRMATION".equals(actual.getStatus())) throw new IllegalStateException("工作流已被其他请求更新");
        Long updated = redis.execute(CAS_SCRIPT, List.of(k), raw, json(changed), String.valueOf(TTL_DAYS * 86400));
        if (!Long.valueOf(1).equals(updated)) throw new IllegalStateException("工作流已被其他请求更新");
    }

    private void replaceOwned(AfterSaleWorkflowState state) {
        String k = key(state.getTaskId());
        String raw = redis.opsForValue().get(k);
        if (raw == null) throw new IllegalStateException("工作流不存在或已过期");
        AfterSaleWorkflowState actual = read(raw, AfterSaleWorkflowState.class);
        if (!state.getOwnerId().equals(actual.getOwnerId()) || actual.getVersion() + 1 != state.getVersion()) throw new IllegalStateException("工作流已被其他请求更新");
        Long updated = redis.execute(CAS_SCRIPT, List.of(k), raw, json(state), String.valueOf(TTL_DAYS * 86400));
        if (!Long.valueOf(1).equals(updated)) throw new IllegalStateException("工作流已被其他请求更新");
    }

    private void requireOwner(ToolInvocationContext c) {
        if (c == null || !c.isAuthenticated()) throw new SecurityException("需要登录后操作售后工作流");
        if (c.getSessionId() == null || c.getSessionId().isBlank() || c.getSessionId().length() > 128) {
            throw new IllegalArgumentException("无效的会话编号");
        }
    }
    private String key(String taskId) {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("无效的工作流编号");
        return "agent:workflow:after-sale:" + taskId;
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("JSON序列化失败", e); } }
    private <T> T read(String value, Class<T> type) { try { return objectMapper.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("工作流数据损坏", e); } }
    private JsonNode readJson(String value) { try { return objectMapper.readTree(value); } catch (Exception e) { return objectMapper.createObjectNode().put("error", "invalid response"); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
