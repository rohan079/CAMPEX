package com.aivoice.lead.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Optimization actions over HTTP + PostgreSQL. Meta is a mock. Gated on CAMPEX_IT_DATABASE_URL. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class CampaignActionIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("CAMPEX_IT_DATABASE_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("CAMPEX_IT_DATABASE_USER", "campex"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAMPEX_IT_DATABASE_PASSWORD", ""));
        r.add("app.environment", () -> "production");
        r.add("app.business-name", () -> "IT");
        r.add("app.business-description", () -> "IT");
        r.add("app.call-poll-seconds", () -> "3600");
        r.add("app.ad-popup-token", () -> "popup-token");
        r.add("app.internal-service-key", () -> "service-key");
        r.add("app.whatsapp.verify-token", () -> "x");
        r.add("app.whatsapp.app-secret", () -> "x");
        r.add("app.whatsapp.access-token", () -> "x");
        r.add("app.whatsapp.phone-number-id", () -> "x");
        r.add("app.whatsapp.graph-api-version", () -> "v27.0");
        r.add("campex.firebase.project-id", () -> "it-project");
        r.add("campex.access.platform-admin-emails", () -> "admin@it.test");
        r.add("campex.tracking.capi-enabled", () -> "false");
        r.add("meta.token-encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    static final String ACCOUNT = "act_5550003";
    static final String RUN = String.valueOf(System.nanoTime() % 1_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseTokenVerifier firebase;
    @MockitoBean MetaExecutionGateway meta;
    @MockitoBean MetaAccessResolver metaAccess;

    private String client;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(metaAccess.resolve(any(), any())).thenReturn(new MetaAccess(true, null, List.of(ACCOUNT), "tok"));
        client = ok(as("admin@it.test", post("/api/clients"), "{\"name\":\"Actions " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"voice_agent_enabled\":false}")).get("id").asText();
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"m@a.test\",\"role\":\"manager\"}").andExpect(status().isOk());
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"v@a.test\",\"role\":\"viewer\"}").andExpect(status().isOk());
        jdbc.update("INSERT INTO client_ad_accounts (id, client_id, ad_account_id, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(client), ACCOUNT, "m@a.test", Timestamp.from(Instant.now()));
    }

    private String obj() {
        return RUN + (++seq);
    }

    private static Map<String, Object> adset(String id, String status, String budget) {
        return Map.of("id", id, "name", "Villas · audience", "account_id", "5550003", "status", status, "daily_budget", budget);
    }

    private ResultActions as(String email, MockHttpServletRequestBuilder req, String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String approvedBudgetChange(String id, String from, long to) throws Exception {
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", from));
        String action = ok(as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"set_daily_budget\",\"level\":\"adset\",\"object_id\":\"" + id + "\",\"daily_budget_minor\":" + to + "}")).get("id").asText();
        ok(as("m@a.test", post("/api/campaign-actions/" + action + "/approve"), "{\"note\":\"ok\"}"));
        return action;
    }

    @Test
    void approvedChangeIsAppliedOnceAndVerifiedAgainstMeta() throws Exception {
        String id = obj();
        String action = approvedBudgetChange(id, "35000", 38500);
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "35000"), adset(id, "ACTIVE", "38500"));
        JsonNode done = ok(as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}"));
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("verification").asText()).isEqualTo("verified");
        assertThat(done.at("/before_state/daily_budget").asText()).isEqualTo("35000");
        assertThat(done.at("/proposed_after/daily_budget").asLong()).isEqualTo(38500);
        assertThat(done.at("/actual_after/daily_budget").asText()).isEqualTo("38500");
        verify(meta, times(1)).applyChange("tok", "adset", id, null, 38500L);
        as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}").andExpect(status().isConflict());
        verify(meta, times(1)).applyChange(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void nothingRunsWithoutApprovalConfirmationOrPermission() throws Exception {
        String id = obj();
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "35000"));
        String action = ok(as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"pause\",\"level\":\"adset\",\"object_id\":\"" + id + "\"}")).get("id").asText();
        as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}").andExpect(status().isConflict());
        as("v@a.test", post("/api/campaign-actions/" + action + "/approve"), "{}").andExpect(status().isForbidden());
        ok(as("m@a.test", post("/api/campaign-actions/" + action + "/approve"), "{}"));
        as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{}").andExpect(status().isBadRequest());
        as("v@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}").andExpect(status().isForbidden());
        as("stranger@x.test", get("/api/campaign-actions/" + action), null).andExpect(status().isNotFound());
        verify(meta, never()).applyChange(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void guardrailsBlockLargeIncreasesAndDuplicateOpenChanges() throws Exception {
        String id = obj();
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "35000"));
        as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"set_daily_budget\",\"level\":\"adset\",\"object_id\":\"" + id + "\",\"daily_budget_minor\":70000}")
                .andExpect(status().isConflict());
        ok(as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"pause\",\"level\":\"adset\",\"object_id\":\"" + id + "\"}"));
        as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"set_daily_budget\",\"level\":\"adset\",\"object_id\":\"" + id + "\",\"daily_budget_minor\":38500}")
                .andExpect(status().isConflict());
    }

    @Test
    void objectsOutsideTheClientsAccountsAreRefused() throws Exception {
        String id = obj();
        when(meta.objectState("tok", "ad", id)).thenReturn(Map.of("id", id, "account_id", "9999999", "status", "ACTIVE"));
        as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"pause\",\"level\":\"ad\",\"object_id\":\"" + id + "\"}").andExpect(status().isConflict());
    }

    @Test
    void staleApprovalIsNotExecutedWhenMetaChangedSincePreview() throws Exception {
        String id = obj();
        String action = approvedBudgetChange(id, "35000", 38500);
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "30000"));
        JsonNode failed = ok(as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}"));
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("last_error").asText()).contains("changed since the preview");
        verify(meta, never()).applyChange(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void errorFromMetaIsResolvedByReadingTheActualState() throws Exception {
        String id = obj();
        String action = approvedBudgetChange(id, "35000", 38500);
        doThrow(new GatewayException("timeout", null)).when(meta).applyChange(anyString(), anyString(), anyString(), isNull(), any());
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "35000"), adset(id, "ACTIVE", "35000"));
        JsonNode failed = ok(as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}"));
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("allowed_actions").toString()).contains("execute");

        // Retry: Meta had applied it after all, so nothing is sent again.
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "38500"));
        JsonNode retried = ok(as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}"));
        assertThat(retried.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(retried.get("attempts").asInt()).isEqualTo(2);
        verify(meta, times(1)).applyChange(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void acceptedRequestThatMetaDoesNotReflectIsFlaggedAsMismatch() throws Exception {
        String id = obj();
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "35000"));
        String action = ok(as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"),
                "{\"action_type\":\"pause\",\"level\":\"adset\",\"object_id\":\"" + id + "\"}")).get("id").asText();
        ok(as("m@a.test", post("/api/campaign-actions/" + action + "/approve"), "{}"));
        doReturn(Map.of("success", true)).when(meta).applyChange(anyString(), anyString(), anyString(), any(), any());
        JsonNode done = ok(as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}"));
        assertThat(done.get("verification").asText()).isEqualTo("mismatch");
    }

    @Test
    void recommendationsPrefillAPreviewAndAreAcceptedOnApproval() throws Exception {
        String campaign = obj();
        UUID rec = UUID.randomUUID();
        jdbc.update("INSERT INTO performance_recommendations (id, client_id, category, recommendation_type, campaign_id, object_name, title, "
                        + "current_performance, reasoning, expected_impact, confidence, risk_level, recommended_action, proposed_change, status, "
                        + "dedup_key, analysis_from, analysis_to, calculated_at, created_at, expires_at) VALUES (?, ?, 'budget', 'increase_budget', ?, "
                        + "'Villas', 'Increase budget', 'c', 'r', 'e', 'medium', 'low', 'a', ?::jsonb, 'pending', ?, current_date, current_date, now(), now(), now() + interval '3 days')",
                rec, UUID.fromString(client), campaign, "{\"field\":\"daily_budget\",\"from\":350,\"to\":385}", "k" + campaign);
        when(meta.objectState("tok", "campaign", campaign)).thenReturn(Map.of("id", campaign, "name", "Villas", "account_id", "5550003",
                "status", "ACTIVE", "daily_budget", "35000"));
        JsonNode preview = ok(as("m@a.test", post("/api/clients/" + client + "/campaign-actions/preview"), "{\"recommendation_id\":\"" + rec + "\"}"));
        assertThat(preview.get("action_type").asText()).isEqualTo("set_daily_budget");
        assertThat(preview.at("/proposed_after/daily_budget").asLong()).isEqualTo(38500);
        assertThat(preview.get("source").asText()).isEqualTo("recommendation");
        ok(as("m@a.test", post("/api/campaign-actions/" + preview.get("id").asText() + "/approve"), "{}"));
        assertThat(jdbc.queryForObject("SELECT status FROM performance_recommendations WHERE id = ?", String.class, rec)).isEqualTo("accepted");
    }

    @Test
    void concurrentExecuteCallsApplyOnce() throws Exception {
        String id = obj();
        String action = approvedBudgetChange(id, "35000", 38500);
        CountDownLatch inMeta = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(meta.objectState("tok", "adset", id)).thenReturn(adset(id, "ACTIVE", "35000"), adset(id, "ACTIVE", "38500"));
        when(meta.applyChange(anyString(), anyString(), anyString(), any(), any())).thenAnswer(i -> {
            inMeta.countDown();
            release.await(10, TimeUnit.SECONDS);
            return Map.of("success", true);
        });
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Integer> first = pool.submit(() -> as("m@a.test", post("/api/campaign-actions/" + action + "/execute"),
                    "{\"confirm\":true}").andReturn().getResponse().getStatus());
            assertThat(inMeta.await(10, TimeUnit.SECONDS)).isTrue();
            int second = as("m@a.test", post("/api/campaign-actions/" + action + "/execute"), "{\"confirm\":true}").andReturn().getResponse().getStatus();
            release.countDown();
            assertThat(second).isEqualTo(409);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        verify(meta, times(1)).applyChange(anyString(), eq("adset"), anyString(), any(), any());
    }
}
