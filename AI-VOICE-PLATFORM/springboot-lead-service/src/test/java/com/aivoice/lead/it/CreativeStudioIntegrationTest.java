package com.aivoice.lead.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.creative.CreativeAiGateway;
import com.aivoice.lead.service.creative.CreativeAiGateway.GeneratedImage;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
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

/** Creative Studio over HTTP + PostgreSQL. The AI service and Meta are mocks. Gated on CAMPEX_IT_DATABASE_URL. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class CreativeStudioIntegrationTest {

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

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final String PNG_B64 = Base64.getEncoder().encodeToString(PNG);
    static final String ACCOUNT = "act_5550002";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseTokenVerifier firebase;
    @MockitoBean CreativeAiGateway ai;
    @MockitoBean MetaExecutionGateway meta;
    @MockitoBean MetaAccessResolver metaAccess;

    private String client;

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(metaAccess.resolve(any(), any())).thenReturn(new MetaAccess(true, null, List.of(ACCOUNT), "t"));
        when(ai.image(anyString(), isNull(), isNull())).thenReturn(new GeneratedImage("image/png", PNG_B64, "Villa at dawn. No text.", "gpt-image-1"));
        when(ai.check(anyList(), anyList())).thenReturn(Map.of("unsupported_numbers", List.of(), "flagged_phrases", List.of()));
        client = ok(as("admin@it.test", post("/api/clients"), "{\"name\":\"Creative " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"voice_agent_enabled\":false}")).get("id").asText();
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"m@c.test\",\"role\":\"manager\"}").andExpect(status().isOk());
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"v@c.test\",\"role\":\"viewer\"}").andExpect(status().isOk());
        jdbc.update("INSERT INTO client_ad_accounts (id, client_id, ad_account_id, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(client), ACCOUNT, "m@c.test", Timestamp.from(Instant.now()));
    }

    private ResultActions as(String email, MockHttpServletRequestBuilder req, String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String generated() throws Exception {
        return ok(as("m@c.test", post("/api/clients/" + client + "/creative/generate"),
                "{\"name\":\"Diwali villas\",\"ad_type\":\"villa\",\"prompt\":\"Villa at dawn\",\"facts\":[\"3 BHK villas from 85 lakh\"]}"))
                .at("/asset/id").asText();
    }

    private void editCopy(String id, String headline, String text) throws Exception {
        ok(as("m@c.test", put("/api/creative/assets/" + id), "{\"headline\":\"" + headline + "\",\"primary_text\":\"" + text
                + "\",\"cta\":\"LEARN_MORE\"}"));
    }

    @Test
    void generatedArtworkIsStoredAndServedOnlyToMembers() throws Exception {
        String id = generated();
        JsonNode detail = ok(as("v@c.test", get("/api/creative/assets/" + id), null));
        assertThat(detail.at("/asset/status").asText()).isEqualTo("GENERATED");
        assertThat(detail.at("/versions/0/image_source").asText()).isEqualTo("ai_generated");
        String imageId = detail.at("/asset/image_id").asText();
        byte[] served = as("v@c.test", get("/api/creative/images/" + imageId), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PNG);
        as("stranger@x.test", get("/api/creative/images/" + imageId), null).andExpect(status().isNotFound());
        as("v@c.test", post("/api/clients/" + client + "/creative/generate"), "{\"name\":\"x\",\"prompt\":\"abc\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void prohibitedClaimsBlockApprovalAndUnverifiedNumbersNeedAcknowledgement() throws Exception {
        String id = generated();
        editCopy(id, "Guaranteed returns", "Assured rental income of 12 percent");
        when(ai.check(anyList(), anyList())).thenReturn(Map.of("unsupported_numbers", List.of("12"),
                "flagged_phrases", List.of(Map.of("text", "guaranteed", "reason", "Guarantees are not allowed"))));
        ok(as("m@c.test", post("/api/creative/assets/" + id + "/submit"), null));
        as("m@c.test", post("/api/creative/assets/" + id + "/approve"), "{\"claims_acknowledged\":true}").andExpect(status().isConflict());

        editCopy(id, "East-facing villas", "3 BHK villas from 85 lakh, 12 km from Guntur");
        when(ai.check(anyList(), anyList())).thenReturn(Map.of("unsupported_numbers", List.of("12"), "flagged_phrases", List.of()));
        ok(as("m@c.test", post("/api/creative/assets/" + id + "/submit"), null));
        as("m@c.test", post("/api/creative/assets/" + id + "/approve"), "{}").andExpect(status().isConflict());
        JsonNode approved = ok(as("m@c.test", post("/api/creative/assets/" + id + "/approve"), "{\"claims_acknowledged\":true}"));
        assertThat(approved.at("/asset/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/asset/approved_version").asInt()).isEqualTo(approved.at("/asset/current_version").asInt());
        assertThat(approved.at("/approvals/0/claims_acknowledged").asBoolean()).isTrue();
    }

    @Test
    void uploadsMustReallyBeImages() throws Exception {
        String fake = Base64.getEncoder().encodeToString("<script>alert(1)</script>".getBytes());
        as("m@c.test", post("/api/clients/" + client + "/creative/assets"),
                "{\"name\":\"x\",\"image_base64\":\"" + fake + "\",\"mime_type\":\"image/png\"}").andExpect(status().isBadRequest());
        JsonNode up = ok(as("m@c.test", post("/api/clients/" + client + "/creative/assets"),
                "{\"name\":\"Brochure\",\"image_base64\":\"" + PNG_B64 + "\",\"mime_type\":\"image/png\"}"));
        assertThat(up.at("/asset/status").asText()).isEqualTo("EDITING");
    }

    @Test
    void copySuggestionsDoNotExposeProviderDetails() throws Exception {
        when(ai.copy(anyMap(), anyList())).thenReturn(Map.of("headlines", List.of("A", "B", "C", "D", "E"), "provider", "openai", "model", "x"));
        JsonNode r = ok(as("m@c.test", post("/api/clients/" + client + "/creative/copy"), "{\"product\":\"Green Villas\",\"facts\":[\"3 BHK\"]}"));
        assertThat(r.has("provider")).isFalse();
        assertThat(r.has("model")).isFalse();
        assertThat(r.get("headlines").size()).isEqualTo(5);
    }

    @Test
    void campaignDraftsMayOnlyUseTheApprovedCreativeCopy() throws Exception {
        String id = generated();
        editCopy(id, "East-facing villas", "Book a site visit this weekend");
        ok(as("m@c.test", post("/api/creative/assets/" + id + "/submit"), null));
        int version = ok(as("m@c.test", post("/api/creative/assets/" + id + "/approve"), "{}")).at("/asset/approved_version").asInt();
        String base = "{\"ad_account_id\":\"" + ACCOUNT + "\",\"name\":\"With creative\",\"objective\":\"leads\",\"platforms\":[\"facebook\"],"
                + "\"location\":\"Guntur\",\"age_min\":25,\"age_max\":55,\"daily_budget_minor\":50000,\"cta\":\"LEARN_MORE\",\"page_id\":\"42\","
                + "\"creative_asset_id\":\"" + id + "\",\"creative_version\":" + version + ",";
        String good = base + "\"headline\":\"East-facing villas\",\"primary_text\":\"Book a site visit this weekend\"}";
        String changed = base + "\"headline\":\"East-facing villas!!\",\"primary_text\":\"Book a site visit this weekend\"}";
        String e1 = ok(as("m@c.test", post("/api/clients/" + client + "/campaign-executions"), good)).at("/execution/id").asText();
        assertThat(ok(as("m@c.test", post("/api/campaign-executions/" + e1 + "/validate"), null)).get("valid").asBoolean()).isTrue();
        String e2 = ok(as("m@c.test", post("/api/clients/" + client + "/campaign-executions"), changed)).at("/execution/id").asText();
        JsonNode bad = ok(as("m@c.test", post("/api/campaign-executions/" + e2 + "/validate"), null));
        assertThat(bad.get("errors").findValuesAsText("code")).contains("copy_changed");
    }
}
