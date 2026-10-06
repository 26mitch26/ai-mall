package com.ai.mall.agent.ops.controller;

import com.ai.mall.agent.ops.model.*;
import com.ai.mall.agent.ops.service.agent.Orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiOpsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AiOpsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Orchestrator orchestrator;

    /** 控制面依赖：capabilities 现在会读这些 bean，测试里打桩避免拉起真实组件。 */
    @MockitoBean
    private com.ai.mall.agent.ops.service.metric.MetricsIngestService ingestService;

    @MockitoBean
    private com.ai.mall.agent.ops.service.audit.GateDecisionStore gateStore;

    @MockitoBean
    private com.ai.mall.agent.ops.service.execution.SimulatedExecutor executor;

    @org.junit.jupiter.api.BeforeEach
    void stubCapabilitiesDependencies() {
        org.mockito.Mockito.lenient().when(ingestService.watchTargets()).thenReturn(java.util.List.of());
        org.mockito.Mockito.lenient().when(executor.mode()).thenReturn("simulated");
        org.mockito.Mockito.lenient().when(gateStore.summary())
                .thenReturn(java.util.Map.of("total", 0, "pending", 0));
    }

    @Test
    void testTriggerIncidentEndpoint() throws Exception {
        IncidentState incident = IncidentState.builder()
                .id("incident-1")
                .alert(AlertEvent.builder()
                        .id("alert-1")
                        .metricName("cpu_usage_percent")
                        .metricValue(95.3)
                        .targetService("order-service")
                        .severity(Severity.CRITICAL)
                        .status("detected")
                        .build())
                .rcaResult(RCAResult.builder()
                        .alertId("alert-1")
                        .rootCause("近期代码部署引入性能退化")
                        .confidence(0.54)
                        .build())
                .healAction(HealAction.builder()
                        .id("heal-1")
                        .alertId("alert-1")
                        .level(HealLevel.L1_ONCALL)
                        .action("rollback")
                        .status("ready")
                        .dryRunPassed(true)
                        .build())
                .changeDecision(ChangeDecision.builder()
                        .id("change-1")
                        .alertId("alert-1")
                        .status("pending")
                        .riskScore(0.22)
                        .approver("oncall-engineer")
                        .build())
                .status("healing")
                .startTime(LocalDateTime.now())
                .build();

        when(orchestrator.triggerIncident(anyString(), anyDouble(), anyString()))
                .thenReturn(incident);

        Map<String, Object> request = Map.of(
                "metric_name", "cpu_usage_percent",
                "metric_value", 95.3,
                "target_service", "order-service"
        );

        mockMvc.perform(post("/api/v1/incidents/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("incident-1"))
                .andExpect(jsonPath("$.status").value("healing"))
                .andExpect(jsonPath("$.alert.metricName").value("cpu_usage_percent"))
                .andExpect(jsonPath("$.rcaResult.rootCause").value("近期代码部署引入性能退化"))
                .andExpect(jsonPath("$.healAction.action").value("rollback"))
                .andExpect(jsonPath("$.changeDecision.status").value("pending"));
    }

    @Test
    void testTriggerIncidentWithDefaultValues() throws Exception {
        IncidentState incident = IncidentState.builder()
                .id("incident-2")
                .alert(AlertEvent.builder()
                        .id("alert-2")
                        .metricName("cpu_usage_percent")
                        .metricValue(95.3)
                        .targetService("order-service")
                        .severity(Severity.CRITICAL)
                        .status("detected")
                        .build())
                .status("detected")
                .startTime(LocalDateTime.now())
                .build();

        when(orchestrator.triggerIncident(eq("cpu_usage_percent"), eq(95.3), eq("order-service")))
                .thenReturn(incident);

        // Send empty object to trigger defaults
        mockMvc.perform(post("/api/v1/incidents/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.metricName").value("cpu_usage_percent"))
                .andExpect(jsonPath("$.alert.metricValue").value(95.3))
                .andExpect(jsonPath("$.alert.targetService").value("order-service"));
    }

    @Test
    void testTriggerIncidentReturnsNullWhenNoAnomaly() throws Exception {
        when(orchestrator.triggerIncident(anyString(), anyDouble(), anyString()))
                .thenReturn(null);

        Map<String, Object> request = Map.of(
                "metric_name", "cpu_usage_percent",
                "metric_value", 50.0,
                "target_service", "order-service"
        );

        mockMvc.perform(post("/api/v1/incidents/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void testGetIncidentById() throws Exception {
        IncidentState incident = IncidentState.builder()
                .id("incident-1")
                .status("resolved")
                .startTime(LocalDateTime.now())
                .build();

        when(orchestrator.getIncident("incident-1")).thenReturn(incident);

        mockMvc.perform(get("/api/v1/incidents/incident-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("incident-1"))
                .andExpect(jsonPath("$.status").value("resolved"));
    }

    @Test
    void testGetIncidentByIdNotFound() throws Exception {
        when(orchestrator.getIncident("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/v1/incidents/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void testGetAllIncidents() throws Exception {
        IncidentState incident1 = IncidentState.builder()
                .id("incident-1")
                .status("resolved")
                .build();
        IncidentState incident2 = IncidentState.builder()
                .id("incident-2")
                .status("healing")
                .build();

        when(orchestrator.getAllIncidents())
                .thenReturn(Map.of("incident-1", incident1, "incident-2", incident2));

        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['incident-1'].status").value("resolved"))
                .andExpect(jsonPath("$['incident-2'].status").value("healing"));
    }

    @Test
    void testGetAllIncidentsEmpty() throws Exception {
        when(orchestrator.getAllIncidents()).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}