package com.ai.mall.agent.customer.service.security;

import com.ai.mall.agent.customer.model.ToolInvocationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Verifies member identity with the portal. Body/model-provided user IDs are never credentials. */
@Service
public class MemberIdentityResolver {
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String portalUrl;

    public MemberIdentityResolver(RestTemplate restTemplate, ObjectMapper mapper,
                                  @Value("${service.mall-portal.url:http://localhost:8087}") String portalUrl) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.portalUrl = portalUrl.replaceAll("/+$", "");
    }

    public ToolInvocationContext resolve(String sessionId, String authorization) {
        String session = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        if (session.length() > 128 || !session.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session ID");
        }
        if (authorization == null || authorization.isBlank()) return ToolInvocationContext.anonymous(session);
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() > 8192) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A member Bearer token is required");
        }
        String token = authorization.substring(7).trim();
        if (token.isBlank() || token.contains("\r") || token.contains("\n")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid member credentials");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<String> response = restTemplate.exchange(portalUrl + "/sso/info", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            JsonNode body = mapper.readTree(response.getBody());
            JsonNode id = body.path("data").path("id");
            if (!response.getStatusCode().is2xxSuccessful() || body.path("code").asInt() != 200
                    || !id.canConvertToLong() || id.asLong() <= 0) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid member credentials");
            }
            return ToolInvocationContext.builder().sessionId(session).memberId(id.asText()).userToken(token).build();
        } catch (Exception ex) {
            // Tokens and portal response bodies are deliberately excluded from logs/errors.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member authentication could not be verified");
        }
    }
}
