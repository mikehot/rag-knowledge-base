package com.example.ragknowledgebase.observability;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragknowledgebase.ask.AskResponse;
import com.example.ragknowledgebase.ask.AskResultStatus;
import com.example.ragknowledgebase.ask.AskTimingsResponse;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class OperationalEndpointsTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OperationalMetrics operationalMetrics;

    @MockitoBean
    private AccessControlService accessControlService;

    @Test
    void exposesStatusOnlyHealthAndProbeEndpointsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/livez"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/readyz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiDocsAreDisabledOutsideLocalProfile() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isNotFound());
    }

    @Test
    void protectsOperationalMetricsFromAnonymousAccess() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void rejectsAnonymousAskWithUnauthorizedContract() throws Exception {
        mockMvc.perform(post("/api/ask")
                .contentType("application/json")
                .content("{\"question\":\"年假怎么申请？\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    void rejectsInvalidBearerTokenWithUnauthorizedContract() throws Exception {
        mockMvc.perform(post("/api/ask")
                .header("Authorization", "Bearer invalid-token")
                .contentType("application/json")
                .content("{\"question\":\"年假怎么申请？\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void rejectsMalformedAndInvalidAskRequestsWithBadRequestContract() throws Exception {
        mockMvc.perform(post("/api/ask")
                .with(operatorAuthentication())
                .contentType("application/json")
                .content("{\"question\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/ask")
                .with(operatorAuthentication())
                .contentType("application/json")
                .content("not-json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsMetricsForAuthenticatedNonOperators() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(operatorAuthentication()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void exposesSanitizedBusinessMetricsToAuthorizedOperators() throws Exception {
        when(accessControlService.canReadAuditEvents(any())).thenReturn(true);
        operationalMetrics.recordAsk(new AskResponse(
            "answer",
            true,
            List.of(),
            UUID.randomUUID(),
            25,
            8,
            null,
            new AskTimingsResponse(5, 5, 15)
        ), AskResultStatus.ANSWERED);

        mockMvc.perform(get("/actuator/metrics/rag.ask.requests").with(operatorAuthentication()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("rag.ask.requests"));
        mockMvc.perform(get("/actuator/prometheus").with(operatorAuthentication()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andExpect(content().string(containsString("rag_ask_requests_total")))
            .andExpect(content().string(containsString("rag_ask_duration_seconds_bucket")));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor operatorAuthentication() {
        AuthenticatedUser user = new AuthenticatedUser(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "operator"
        );
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
