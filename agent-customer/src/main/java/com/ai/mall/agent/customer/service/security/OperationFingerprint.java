package com.ai.mall.agent.customer.service.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Canonical JSON hash binds approval to the exact tool arguments. */
public final class OperationFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private OperationFingerprint() {}
    public static String hash(String json) {
        try {
            String canonical = MAPPER.writeValueAsString(sort(MAPPER.readTree(json)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalArgumentException("Invalid operation arguments"); }
    }
    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sort(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode(); node.forEach(value -> result.add(sort(value))); return result;
        }
        return node;
    }
}
