package com.aivoice.lead.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.MetaPerformanceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

/** Experiment execution over HTTP + PostgreSQL. Meta (actions and insights) is mocked. Gated on CAMPEX_IT_DATABASE_URL. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class ExperimentExecutionIntegrationTest {

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

    static final String ACCOUNT = "act_5550005";
    static final String RUN = String.valueOf(System.nanoTime() % 1_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseTokenVerifier firebase;
    @MockitoBean MetaExecutionGateway meta;
    @MockitoBean MetaAccessResolver metaAccess;
    @MockitoBean MetaPerformanceClient insights;

    String client;
    int seq;

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(metaAccess.resolve(any(), any())).thenReturn(new MetaAccess(true, null, List.of(ACCOUNT), "tok"));
        when(insights.insights(anyString(), any(), any(), anyString(), anyBoolean(), any(), anyString())).thenReturn(List.of());
        client = ok(as("admin@it.test", post("/api/clients"), "{\"name\":\"Exp " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"voice_agent_enabled\":false}")).get("id").asText();
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"m@e.test\",\"role\":\"manager\"}").andExpect(status().isOk());
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"v@e.test\",\"role\":\"viewer\"}").andExpect(status().isOk());
        jdbc.update("INSERT INTO client_ad_accounts (id, client_id, ad_account_id, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(client), ACCOUNT, "m@e.test", Timestamp.from(Instant.now()));
    }

    ResultActions as(String email, MockHttpServletRequestBuilder req, String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    record Ids(String experiment, String control, String test) {
    }

    Ids experiment(String status) throws Exception {
        String c = RUN + (++seq), t = RUN + (++seq);
        when(meta.objectState("tok", "ad", c)).thenReturn(Map.of("id", c, "name", "Control ad", "account_id", "5550005", "campaign_id", "900" + RUN, "status", status, "effective_status", status));
        when(meta.objectState("tok", "ad", t)).thenReturn(Map.of("id", t, "name", "Test ad", "account_id", "5550005", "campaign_id", "900" + RUN, "status", status, "effective_status", status));
        String end = LocalDate.now().plusDays(14).toString();
        String id = ok(as("m@e.test", post("/api/clients/" + client + "/experiments"), "{\"name\":\"Headline test\",\"hypothesis\":\"h\","
                + "\"variable\":\"headline\",\"primary_metric\":\"ctr\",\"min_sample_size\":1000,\"start_date\":\"" + LocalDate.now() + "\",\"end_date\":\"" + end + "\","
                + "\"control\":{\"name\":\"Control\",\"ad_id\":\"" + c + "\"},\"test\":{\"name\":\"New headline\",\"ad_id\":\"" + t + "\"}}")).get("id").asText();
        return new Ids(id, c, t);
    }

    @Test
    void pausedVariantsAreLaunchedOnlyThroughApprovedActionsThenMeasured() throws Exception {
        Ids x = experiment("PAUSED");
        JsonNode exec = ok(as("m@e.test", get("/api/experiments/" + x.experiment() + "/execution"), null));
        assertThat(exec.get("ready").asBoolean()).isTrue();
        assertThat(exec.get("phase").asText()).isEqualTo("setup");

        JsonNode launched = ok(as("m@e.test", post("/api/experiments/" + x.experiment() + "/launch"), null));
        assertThat(launched.get("experiment_status").asText()).isEqualTo("draft");
        assertThat(launched.get("phase").asText()).isEqualTo("awaiting_activation");
        assertThat(launched.get("actions").findValuesAsText("action_type")).containsExactly("resume", "resume");
        assertThat(launched.get("actions").findValuesAsText("status")).containsOnly("PROPOSED");
        // Launching again does not prepare duplicates.
        assertThat(ok(as("m@e.test", post("/api/experiments/" + x.experiment() + "/launch"), null)).get("actions").size()).isEqualTo(2);
        verify(meta, never()).applyChange(anyString(), anyString(), anyString(), any(), any());

        // Once Meta shows both variants delivering, launch starts measurement.
        when(meta.objectState("tok", "ad", x.control())).thenReturn(Map.of("id", x.control(), "account_id", "5550005", "campaign_id", "900" + RUN, "status", "ACTIVE", "effective_status", "ACTIVE"));
        when(meta.objectState("tok", "ad", x.test())).thenReturn(Map.of("id", x.test(), "account_id", "5550005", "campaign_id", "900" + RUN, "status", "ACTIVE", "effective_status", "ACTIVE"));
        JsonNode running = ok(as("m@e.test", post("/api/experiments/" + x.experiment() + "/launch"), null));
        assertThat(running.get("experiment_status").asText()).isEqualTo("running");
        assertThat(running.at("/progress/guidance").asText()).contains("Keep it running");
    }

    @Test
    void readinessBlocksForeignAccountsAndWrongLevels() throws Exception {
        Ids x = experiment("ACTIVE");
        when(meta.objectState("tok", "ad", x.test())).thenReturn(Map.of("id", x.test(), "account_id", "999", "status", "ACTIVE"));
        JsonNode exec = ok(as("m@e.test", get("/api/experiments/" + x.experiment() + "/execution"), null));
        assertThat(exec.get("ready").asBoolean()).isFalse();
        assertThat(exec.get("checks").findValuesAsText("code")).contains("account");
        as("m@e.test", post("/api/experiments/" + x.experiment() + "/launch"), null).andExpect(status().isConflict());
        as("v@e.test", post("/api/experiments/" + x.experiment() + "/launch"), null).andExpect(status().isForbidden());
        as("stranger@x.test", get("/api/experiments/" + x.experiment() + "/execution"), null).andExpect(status().isNotFound());
    }

    @Test
    void onlyAReliableWinnerCanLeadToAPreparedPauseOfTheLoser() throws Exception {
        Ids x = experiment("ACTIVE");
        jdbc.update("UPDATE campaign_experiments SET status = 'completed', outcome = 'no_reliable_winner', started_at = now(), completed_at = now() WHERE id = ?",
                UUID.fromString(x.experiment()));
        as("m@e.test", post("/api/experiments/" + x.experiment() + "/follow-up"), null).andExpect(status().isConflict());

        UUID winner = jdbc.queryForObject("SELECT id FROM experiment_variants WHERE experiment_id = ? AND role = 'test'", UUID.class, UUID.fromString(x.experiment()));
        jdbc.update("UPDATE campaign_experiments SET outcome = 'winner', winner_variant_id = ? WHERE id = ?", winner, UUID.fromString(x.experiment()));
        JsonNode follow = ok(as("m@e.test", post("/api/experiments/" + x.experiment() + "/follow-up"), null));
        JsonNode pause = follow.get("actions").get(0);
        assertThat(pause.get("purpose").asText()).isEqualTo("pause_loser");
        assertThat(pause.get("variant_role").asText()).isEqualTo("control");
        assertThat(pause.get("status").asText()).isEqualTo("PROPOSED");
        as("m@e.test", post("/api/experiments/" + x.experiment() + "/follow-up"), null).andExpect(status().isConflict());
        verify(meta, never()).applyChange(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void onlyApprovedCreativesCanBeAttachedToVariants() throws Exception {
        Ids x = experiment("ACTIVE");
        UUID variant = jdbc.queryForObject("SELECT id FROM experiment_variants WHERE experiment_id = ? AND role = 'test'", UUID.class, UUID.fromString(x.experiment()));
        UUID creative = UUID.randomUUID();
        jdbc.update("INSERT INTO creative_assets (id, client_id, name, status, current_version, created_by, updated_by, created_at, updated_at) "
                + "VALUES (?, ?, 'Draft art', 'EDITING', 1, 'm@e.test', 'm@e.test', now(), now())", creative, UUID.fromString(client));
        String path = "/api/experiments/" + x.experiment() + "/variants/" + variant + "/creative";
        as("m@e.test", put(path), "{\"creative_asset_id\":\"" + creative + "\"}").andExpect(status().isConflict());
        jdbc.update("UPDATE creative_assets SET status = 'APPROVED', approved_version = 1 WHERE id = ?", creative);
        JsonNode linked = ok(as("m@e.test", put(path), "{\"creative_asset_id\":\"" + creative + "\"}"));
        assertThat(linked.get("variants").findValuesAsText("creative_name")).contains("Draft art");
        as("v@e.test", put(path), "{\"creative_asset_id\":null}").andExpect(status().isForbidden());
    }
}
