package com.aivoice.lead.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.execution.MetaExecutionGateway.CampaignState;
import com.aivoice.lead.service.execution.MetaExecutionGateway.FoundCampaign;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.execution.MetaExecutionGateway.PublishCommand;
import com.aivoice.lead.service.execution.MetaExecutionGateway.PublishResult;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
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

/**
 * Phase 3 execution engine over HTTP + PostgreSQL. Meta is never called: the gateway and token resolver are mocks.
 * Gated like the other integration tests on CAMPEX_IT_DATABASE_URL (a disposable database).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class CampaignExecutionIntegrationTest {

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

    static final String ACCOUNT = "act_5550001";
    static final String TOKEN = "stored-meta-token";
    /** Per-run prefix so Meta IDs (unique across executions) never collide with rows from earlier runs. */
    static final String RUN = String.valueOf(System.nanoTime() % 1_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseTokenVerifier firebase;
    @MockitoBean MetaExecutionGateway meta;
    @MockitoBean MetaAccessResolver metaAccess;

    private String clientA;
    private String clientB;

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(metaAccess.resolve(any(), any())).thenReturn(new MetaAccess(true, null, List.of(ACCOUNT), TOKEN));
        when(meta.status(anyString(), anyString(), anyString())).thenReturn(new CampaignState("PAUSED", "PAUSED"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        clientA = id(as("admin@it.test", post("/api/clients"), "{\"name\":\"Exec A " + suffix + "\",\"voice_agent_enabled\":false}"));
        clientB = id(as("admin@it.test", post("/api/clients"), "{\"name\":\"Exec B " + suffix + "\",\"voice_agent_enabled\":false}"));
        for (String[] m : new String[][]{{"manager@a.test", "manager"}, {"viewer@a.test", "viewer"}, {"agent@a.test", "agent"}}) {
            as("admin@it.test", post("/api/clients/" + clientA + "/members"),
                    "{\"user_email\":\"" + m[0] + "\",\"role\":\"" + m[1] + "\"}").andExpect(status().isOk());
        }
        jdbc.update("INSERT INTO client_ad_accounts (id, client_id, ad_account_id, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(clientA), ACCOUNT, "manager@a.test", Timestamp.from(Instant.now()));
    }

    private static String config(long budget, String name) {
        return "{\"ad_account_id\":\"" + ACCOUNT + "\",\"name\":\"" + name + "\",\"objective\":\"leads\","
                + "\"platforms\":[\"facebook\"],\"location\":\"Guntur\",\"age_min\":25,\"age_max\":55,"
                + "\"daily_budget_minor\":" + budget + ",\"headline\":\"Villas near Guntur\","
                + "\"primary_text\":\"Book a site visit.\",\"cta\":\"LEARN_MORE\",\"page_id\":\"42\"}";
    }

    private ResultActions as(String email, MockHttpServletRequestBuilder req, String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private JsonNode ok(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String id(ResultActions result) throws Exception {
        return ok(result).get("id").asText();
    }

    /** Creates an execution and drives it to APPROVED as the manager. */
    private String approved(String name) throws Exception {
        String id = ok(as("manager@a.test", post("/api/clients/" + clientA + "/campaign-executions"), config(50_000, name)))
                .at("/execution/id").asText();
        assertThat(ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/validate"), null)).get("valid").asBoolean()).isTrue();
        ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/submit"), null));
        ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/approve"), "{\"note\":\"ok\"}"));
        return id;
    }

    private JsonNode publish(String id) throws Exception {
        return ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}"));
    }

    private String tagOf(String id) throws Exception {
        return ok(as("manager@a.test", get("/api/campaign-executions/" + id), null)).get("publish_tag").asText();
    }

    @Test
    void approvedConfigurationIsPublishedPausedExactlyOnce() throws Exception {
        when(meta.publish(eq(TOKEN), any())).thenReturn(new PublishResult(RUN + "9001", RUN + "9002", null, "PAUSED", "no ad yet"));
        String id = approved("Launch");
        JsonNode result = publish(id);

        assertThat(result.at("/execution/status").asText()).isEqualTo("PUBLISHED_PAUSED");
        assertThat(result.at("/execution/meta_campaign_id").asText()).isEqualTo(RUN + "9001");
        assertThat(result.at("/execution/meta_status").asText()).isEqualTo("PAUSED");
        assertThat(result.get("meta_adset_id").asText()).isEqualTo(RUN + "9002");
        assertThat(result.toString()).doesNotContain(TOKEN);
        ArgumentCaptor<PublishCommand> sent = ArgumentCaptor.forClass(PublishCommand.class);
        verify(meta, times(1)).publish(eq(TOKEN), sent.capture());
        assertThat(sent.getValue().campaignName()).isEqualTo("Launch [" + tagOf(id) + "]");

        // A second publish of the same execution is refused and never reaches Meta.
        as("manager@a.test", post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}").andExpect(status().isConflict());
        verify(meta, times(1)).publish(anyString(), any());

        JsonNode inbox = ok(as("manager@a.test", get("/api/notifications?unread=true"), null));
        assertThat(inbox.get("items").findValuesAsText("type")).contains("campaign_published", "campaign_approval_requested");
        Integer audits = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_type = 'campaign_execution' AND entity_id = ?",
                Integer.class, id);
        assertThat(audits).isGreaterThanOrEqualTo(5);
    }

    @Test
    void publishingNeedsExplicitConfirmationAndAnApprovalOfTheCurrentConfig() throws Exception {
        String id = approved("Edit me");
        as("manager@a.test", post("/api/campaign-executions/" + id + "/publish"), "{}").andExpect(status().isBadRequest());
        JsonNode edited = ok(as("manager@a.test", put("/api/campaign-executions/" + id), config(60_000, "Edit me")));
        assertThat(edited.at("/execution/status").asText()).isEqualTo("DRAFT");
        assertThat(edited.get("approvals").get(0).get("valid").asBoolean()).isFalse();
        as("manager@a.test", post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}").andExpect(status().isConflict());
        verify(meta, never()).publish(anyString(), any());
    }

    @Test
    void viewersAndAgentsCannotChangeAnythingAndOtherTenantsSeeNothing() throws Exception {
        String id = approved("Roles");
        for (String who : List.of("viewer@a.test", "agent@a.test")) {
            as(who, get("/api/campaign-executions/" + id), null).andExpect(status().isOk());
            as(who, post("/api/clients/" + clientA + "/campaign-executions"), config(50_000, "x")).andExpect(status().isForbidden());
            as(who, post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}").andExpect(status().isForbidden());
            as(who, post("/api/campaign-executions/" + id + "/cancel"), null).andExpect(status().isForbidden());
        }
        as("stranger@x.test", get("/api/campaign-executions/" + id), null).andExpect(status().isNotFound());
        as("stranger@x.test", post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}").andExpect(status().isNotFound());
        as("manager@a.test", get("/api/clients/" + clientB + "/campaign-executions"), null).andExpect(status().isNotFound());
        verify(meta, never()).publish(anyString(), any());
    }

    @Test
    void createIsIdempotentPerKey() throws Exception {
        MockHttpServletRequestBuilder first = post("/api/clients/" + clientA + "/campaign-executions").header("Idempotency-Key", "k-1");
        MockHttpServletRequestBuilder second = post("/api/clients/" + clientA + "/campaign-executions").header("Idempotency-Key", "k-1");
        String a = ok(as("manager@a.test", first, config(50_000, "Once"))).at("/execution/id").asText();
        String b = ok(as("manager@a.test", second, config(50_000, "Once"))).at("/execution/id").asText();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void budgetAboveTheClientPolicyBlocksApproval() throws Exception {
        jdbc.update("INSERT INTO client_budget_policies (client_id, daily_spend_limit, updated_at) VALUES (?, 300, now())",
                UUID.fromString(clientA));
        String id = ok(as("manager@a.test", post("/api/clients/" + clientA + "/campaign-executions"), config(50_000, "Big")))
                .at("/execution/id").asText();
        JsonNode report = ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/validate"), null));
        assertThat(report.get("valid").asBoolean()).isFalse();
        assertThat(report.get("errors").findValuesAsText("code")).contains("over_policy");
        as("manager@a.test", post("/api/campaign-executions/" + id + "/submit"), null).andExpect(status().isConflict());
    }

    @Test
    void timeoutThatActuallyCreatedTheCampaignIsReconciledNotRepeated() throws Exception {
        when(meta.publish(anyString(), any())).thenThrow(new GatewayException("Meta MCP service did not respond", null));
        String id = approved("Timeout found");
        String tag = tagOf(id);
        when(meta.findByNameFragment(TOKEN, ACCOUNT, tag))
                .thenReturn(List.of(new FoundCampaign(RUN + "7001", "Timeout found [" + tag + "]", "PAUSED", "PAUSED", List.of(RUN + "7002"))));
        JsonNode result = publish(id);
        assertThat(result.at("/execution/status").asText()).isEqualTo("PUBLISHED_PAUSED");
        assertThat(result.at("/execution/meta_campaign_id").asText()).isEqualTo(RUN + "7001");
        assertThat(result.get("allowed_actions").toString()).doesNotContain("retry");
        verify(meta, times(1)).publish(anyString(), any());
    }

    @Test
    void timeoutThatCreatedNothingFailsSafelyAndRetryReconcilesFirst() throws Exception {
        when(meta.publish(anyString(), any())).thenThrow(new GatewayException("Meta rejected the request (code 100)", null));
        String id = approved("Timeout empty");
        when(meta.findByNameFragment(anyString(), anyString(), anyString())).thenReturn(List.of());
        JsonNode failed = publish(id);
        assertThat(failed.at("/execution/status").asText()).isEqualTo("FAILED");
        assertThat(failed.at("/execution/publish_outcome").asText()).isEqualTo("rejected");
        assertThat(failed.at("/execution/last_error").asText()).contains("code 100").contains("safe to retry");

        org.mockito.Mockito.doReturn(new PublishResult(RUN + "8001", RUN + "8002", null, "PAUSED", null)).when(meta).publish(anyString(), any());
        JsonNode retried = ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/retry"), null));
        assertThat(retried.at("/execution/status").asText()).isEqualTo("PUBLISHED_PAUSED");
        assertThat(retried.get("publish_attempts").asInt()).isEqualTo(2);
        verify(meta, times(2)).findByNameFragment(anyString(), anyString(), anyString());
    }

    @Test
    void rejectedLocationIsRevisedReapprovedAndPublishedWithTheExactMetaPlace() throws Exception {
        when(meta.publish(anyString(), any())).thenThrow(new GatewayException(
                "Meta could not match the target location. Choose a more specific city, region, or country.", null));
        when(meta.findByNameFragment(anyString(), anyString(), anyString())).thenReturn(List.of());
        String id = approved("Bad place");
        JsonNode failed = publish(id);
        assertThat(failed.at("/execution/status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("allowed_actions").toString()).contains("revise");
        as("viewer@a.test", post("/api/campaign-executions/" + id + "/revise"), null).andExpect(status().isForbidden());

        JsonNode draft = ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/revise"), null));
        assertThat(draft.at("/execution/status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("allowed_actions").toString()).contains("edit").doesNotContain("revise", "publish");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_approvals WHERE execution_id = ? "
                + "AND decision = 'approved' AND invalidated_at IS NULL", Integer.class, UUID.fromString(id))).isZero();

        when(meta.searchLocations(TOKEN, "Guntur")).thenReturn(List.of(new com.aivoice.lead.dto.CampaignExecutionDtos.MetaLocation(
                "1035921", "city", "Guntur", "Andhra Pradesh", "India", "Guntur, Andhra Pradesh, India")));
        JsonNode found = ok(as("manager@a.test", get("/api/clients/" + clientA + "/meta/locations?q=Guntur"), null));
        assertThat(found.at("/locations/0/label").asText()).isEqualTo("Guntur, Andhra Pradesh, India");
        as("viewer@a.test", get("/api/clients/" + clientA + "/meta/locations?q=Guntur"), null).andExpect(status().isForbidden());

        String exact = config(50_000, "Bad place").replace("\"location\":\"Guntur\"",
                "\"location\":\"Guntur, Andhra Pradesh, India\",\"location_key\":\"1035921\",\"location_type\":\"city\"");
        ok(as("manager@a.test", put("/api/campaign-executions/" + id), exact));
        JsonNode report = ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/validate"), null));
        assertThat(report.get("valid").asBoolean()).isTrue();
        assertThat(report.get("warnings").toString()).doesNotContain("unverified");
        ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/submit"), null));
        ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/approve"), "{\"note\":\"ok\"}"));

        org.mockito.Mockito.reset(meta);
        when(meta.status(anyString(), anyString(), anyString())).thenReturn(new CampaignState("PAUSED", "PAUSED"));
        when(meta.findByNameFragment(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(meta.publish(anyString(), any())).thenReturn(new PublishResult(RUN + "6601", RUN + "6602", null, "PAUSED", null));
        assertThat(publish(id).at("/execution/status").asText()).isEqualTo("PUBLISHED_PAUSED");
        ArgumentCaptor<PublishCommand> sent = ArgumentCaptor.forClass(PublishCommand.class);
        verify(meta).publish(eq(TOKEN), sent.capture());
        assertThat(sent.getValue().geoKey()).isEqualTo("1035921");
        assertThat(sent.getValue().geoType()).isEqualTo("city");
    }

    @Test
    void typedLocationWarnsAndOldConfigsKeepTheirHash() throws Exception {
        JsonNode created = ok(as("manager@a.test", post("/api/clients/" + clientA + "/campaign-executions"), config(50_000, "Typed")));
        String id = created.at("/execution/id").asText();
        JsonNode report = ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/validate"), null));
        assertThat(report.get("warnings").toString()).contains("unverified");
        String stored = jdbc.queryForObject("SELECT config::text FROM campaign_executions WHERE id = ?", String.class, UUID.fromString(id));
        assertThat(stored).doesNotContain("location_key").doesNotContain("location_type");
    }

    @Test
    void unconfirmedOutcomeStaysExecutingUntilReconciled() throws Exception {
        when(meta.publish(anyString(), any())).thenThrow(new GatewayException("Meta MCP service did not respond", null));
        String id = approved("Unknown");
        String tag = tagOf(id);
        when(meta.findByNameFragment(anyString(), anyString(), anyString())).thenThrow(new GatewayException("down", null));
        JsonNode pending = publish(id);
        assertThat(pending.at("/execution/status").asText()).isEqualTo("EXECUTING");
        assertThat(pending.at("/execution/publish_outcome").asText()).isEqualTo("unknown");
        as("manager@a.test", post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}").andExpect(status().isConflict());
        as("manager@a.test", post("/api/campaign-executions/" + id + "/retry"), null).andExpect(status().isConflict());

        org.mockito.Mockito.reset(meta);
        when(meta.status(anyString(), anyString(), anyString())).thenReturn(new CampaignState("PAUSED", "PAUSED"));
        when(meta.findByNameFragment(TOKEN, ACCOUNT, tag))
                .thenReturn(List.of(new FoundCampaign(RUN + "6001", "Unknown [" + tag + "]", "PAUSED", "PAUSED", List.of(RUN + "6002"))));
        JsonNode reconciled = ok(as("manager@a.test", post("/api/campaign-executions/" + id + "/reconcile"), null));
        assertThat(reconciled.at("/execution/status").asText()).isEqualTo("PUBLISHED_PAUSED");
        verify(meta, never()).publish(anyString(), any());
    }

    @Test
    void partialCreationIsNeverRetriedOrCancelledAway() throws Exception {
        when(meta.publish(anyString(), any())).thenReturn(new PublishResult(RUN + "5001", null, null, "PAUSED", "ad set failed"));
        String id = approved("Partial");
        JsonNode result = publish(id);
        assertThat(result.at("/execution/status").asText()).isEqualTo("FAILED");
        assertThat(result.at("/execution/publish_outcome").asText()).isEqualTo("partial");
        as("manager@a.test", post("/api/campaign-executions/" + id + "/retry"), null).andExpect(status().isConflict());
        as("manager@a.test", post("/api/campaign-executions/" + id + "/cancel"), null).andExpect(status().isConflict());
        verify(meta, times(1)).publish(anyString(), any());
    }

    @Test
    void concurrentPublishRequestsReachMetaOnce() throws Exception {
        CountDownLatch inMeta = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(meta.publish(anyString(), any())).thenAnswer(i -> {
            inMeta.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new PublishResult(RUN + "4001", RUN + "4002", null, "PAUSED", null);
        });
        String id = approved("Race");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> as("manager@a.test",
                    post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}").andReturn().getResponse().getStatus());
            assertThat(inMeta.await(10, TimeUnit.SECONDS)).isTrue();
            int second = as("manager@a.test", post("/api/campaign-executions/" + id + "/publish"), "{\"confirm\":true}")
                    .andReturn().getResponse().getStatus();
            release.countDown();
            assertThat(second).isEqualTo(409);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        verify(meta, times(1)).publish(anyString(), any());
    }
}
