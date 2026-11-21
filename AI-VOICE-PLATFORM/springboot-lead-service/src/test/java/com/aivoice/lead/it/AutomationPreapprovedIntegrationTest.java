package com.aivoice.lead.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Level 4 with the server flag ON: only a capped, reversible pause can run without a per-change approval. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class AutomationPreapprovedIntegrationTest {

    static final boolean PREAPPROVED = true;

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
        r.add("campex.automation.enabled", () -> "false");
        r.add("campex.automation.preapproved-actions-enabled", () -> String.valueOf(PREAPPROVED));
        r.add("meta.token-encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    static final String ACCOUNT = "act_5550004";
    static final String RUN = String.valueOf(System.nanoTime() % 1_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseTokenVerifier firebase;
    @MockitoBean MetaExecutionGateway meta;
    @MockitoBean MetaAccessResolver metaAccess;

    String client;
    int seq;

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(metaAccess.resolve(any(), any())).thenReturn(new MetaAccess(true, null, List.of(ACCOUNT), "tok"));
        client = ok(as("admin@it.test", post("/api/clients"), "{\"name\":\"Rules " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"voice_agent_enabled\":false}")).get("id").asText();
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"m@r.test\",\"role\":\"manager\"}").andExpect(status().isOk());
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"v@r.test\",\"role\":\"viewer\"}").andExpect(status().isOk());
        jdbc.update("INSERT INTO client_ad_accounts (id, client_id, ad_account_id, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(client), ACCOUNT, "m@r.test", Timestamp.from(Instant.now()));
    }

    ResultActions as(String email, MockHttpServletRequestBuilder req, String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 14 settled days for one campaign: spend ₹1000/day, 1 lead/day, CTR halves in the last 7 days. */
    String campaignWithData() {
        String id = RUN + (++seq);
        LocalDate lastComplete = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        for (int i = 13; i >= 0; i--) {
            jdbc.update("INSERT INTO performance_snapshots (id, client_id, ad_account_id, level, object_id, campaign_id, name, day, spend, "
                            + "impressions, link_clicks, meta_leads, attribution_setting, fetched_at) VALUES (?, ?, ?, 'campaign', ?, ?, 'Villas', ?, 1000, 2000, ?, 1, '7d_click', now())",
                    UUID.randomUUID(), UUID.fromString(client), ACCOUNT, id, id, Date.valueOf(lastComplete.minusDays(i)), i >= 7 ? 30 : 15);
        }
        return id;
    }

    String rule(String body) throws Exception {
        return ok(as("m@r.test", post("/api/clients/" + client + "/automation-rules"), body)).get("id").asText();
    }

    JsonNode run(String rule) throws Exception {
        return ok(as("m@r.test", post("/api/automation-rules/" + rule + "/run"), null));
    }

    @Test
    void preapprovedRulePausesOnceWithinItsDailyCapAndIsVerified() throws Exception {
        String campaign = campaignWithData();
        when(meta.objectState("tok", "campaign", campaign)).thenReturn(
                Map.of("id", campaign, "name", "Villas", "account_id", "5550004", "status", "ACTIVE"),
                Map.of("id", campaign, "name", "Villas", "account_id", "5550004", "status", "ACTIVE"),
                Map.of("id", campaign, "name", "Villas", "account_id", "5550004", "status", "PAUSED"));
        String r = rule("{\"name\":\"Auto pause\",\"rule_type\":\"cpl_above_threshold\",\"threshold\":500,\"execution_level\":\"PREAPPROVED\","
                + "\"campaign_id\":\"" + campaign + "\",\"cooldown_hours\":1}");
        JsonNode result = run(r).get(0);
        assertThat(result.get("result").asText()).isEqualTo("action_applied");
        JsonNode action = ok(as("m@r.test", get("/api/campaign-actions/" + result.get("action_id").asText()), null));
        assertThat(action.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(action.get("verification").asText()).isEqualTo("verified");
        assertThat(action.get("approved_by").asText()).isEqualTo("automation:Auto pause");
        verify(meta, times(1)).applyChange("tok", "campaign", campaign, "PAUSED", null);
    }

    @Test
    void preapprovedRulesMustBeScopedToOneCampaign() throws Exception {
        as("m@r.test", post("/api/clients/" + client + "/automation-rules"), "{\"name\":\"Auto\",\"rule_type\":\"cpl_above_threshold\",\"threshold\":500,"
                + "\"execution_level\":\"PREAPPROVED\"}").andExpect(status().isBadRequest());
    }
}
