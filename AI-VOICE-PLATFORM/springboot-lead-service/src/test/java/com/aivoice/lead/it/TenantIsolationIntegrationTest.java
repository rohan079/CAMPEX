package com.aivoice.lead.it;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end tenant isolation over HTTP with a real PostgreSQL (Flyway + Hibernate validate). Firebase is mocked:
 * the bearer token is the user's email. Runs only when CAMPEX_IT_DATABASE_URL points at a disposable database, e.g.
 * CAMPEX_IT_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/campex_it CAMPEX_IT_DATABASE_USER=campex mvn test
 * Never point it at a shared or production database: it creates clients and leads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class TenantIsolationIntegrationTest {

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

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    FirebaseTokenVerifier firebase;

    private String clientA;
    private String clientB;
    private String leadB;

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        clientA = id(as("admin@it.test", post("/api/clients"), "{\"name\":\"Tenant A " + suffix + "\",\"voice_agent_enabled\":false}"));
        clientB = id(as("admin@it.test", post("/api/clients"), "{\"name\":\"Tenant B " + suffix + "\",\"voice_agent_enabled\":false}"));
        as("admin@it.test", post("/api/clients/" + clientA + "/members"), "{\"user_email\":\"manager@a.test\",\"role\":\"manager\"}")
                .andExpect(status().isOk());
        as("admin@it.test", post("/api/clients/" + clientA + "/members"), "{\"user_email\":\"viewer@a.test\",\"role\":\"viewer\"}")
                .andExpect(status().isOk());
        leadB = id(as("admin@it.test", post("/api/clients/" + clientB + "/crm/leads"),
                "{\"phone_number\":\"98" + (10000000 + (int) (Math.random() * 89999999)) + "\",\"name\":\"B lead\"}"));
    }

    private ResultActions as(String email, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                             String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private String id(ResultActions result) throws Exception {
        JsonNode node = json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return node.get("id").asText();
    }

    @Test
    void unauthenticatedDashboardCallsAreRejected() throws Exception {
        as(null, get("/api/clients/" + clientA + "/tracking/pixels"), null).andExpect(status().isUnauthorized());
        as(null, get("/api/leads/" + leadB + "/crm"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void membersSeeOnlyTheirOwnClient() throws Exception {
        as("manager@a.test", get("/api/clients/" + clientA + "/tracking/pixels"), null).andExpect(status().isOk());
        as("manager@a.test", get("/api/clients/" + clientB + "/tracking/pixels"), null).andExpect(status().isNotFound());
        as("manager@a.test", get("/api/clients/" + clientB + "/crm/pipeline"), null).andExpect(status().isNotFound());
        as("manager@a.test", get("/api/clients/" + clientB + "/sales"), null).andExpect(status().isNotFound());
        as("manager@a.test", get("/api/clients/" + clientB + "/site-visits"), null).andExpect(status().isNotFound());
        as("manager@a.test", get("/api/clients/" + clientB + "/measurement/kpis"), null).andExpect(status().isNotFound());
        as("manager@a.test", get("/api/leads/" + leadB + "/crm"), null).andExpect(status().isNotFound());
        as("manager@a.test", get("/api/leads/" + leadB + "/attribution"), null).andExpect(status().isNotFound());
        as("manager@a.test", patch("/api/leads/" + leadB + "/status"), "{\"status\":\"CONTACTED\"}")
                .andExpect(status().isNotFound());
        as("manager@a.test", post("/api/clients/" + clientA + "/sales"),
                "{\"lead_id\":\"" + leadB + "\",\"booking_value\":100}").andExpect(status().isNotFound());
        as("stranger@x.test", get("/api/clients/" + clientA + "/crm/pipeline"), null).andExpect(status().isNotFound());
    }

    @Test
    void phaseTwoIntelligenceIsTenantScoped() throws Exception {
        for (String path : List.of("performance/overview", "performance/campaigns", "performance/ads", "performance/trends",
                "recommendations", "budget/analysis", "budget/policy", "creative-performance", "audience-performance",
                "placement-performance", "anomalies", "experiments")) {
            as("manager@a.test", get("/api/clients/" + clientA + "/" + path), null).andExpect(status().isOk());
            as("manager@a.test", get("/api/clients/" + clientB + "/" + path), null).andExpect(status().isNotFound());
        }
        String experimentB = id(as("admin@it.test", post("/api/clients/" + clientB + "/experiments"), "{\"name\":\"B test\","
                + "\"hypothesis\":\"h\",\"variable\":\"headline\",\"primary_metric\":\"ctr\",\"min_sample_size\":100,"
                + "\"control\":{\"name\":\"c\",\"ad_id\":\"111\"},\"test\":{\"name\":\"t\",\"ad_id\":\"222\"}}"));
        as("manager@a.test", get("/api/experiments/" + experimentB), null).andExpect(status().isNotFound());
        as("manager@a.test", post("/api/experiments/" + experimentB + "/start"), null).andExpect(status().isNotFound());
        as("manager@a.test", post("/api/clients/" + clientB + "/recommendations/generate"), "{}").andExpect(status().isNotFound());
        as("viewer@a.test", post("/api/clients/" + clientA + "/recommendations/generate"), "{}").andExpect(status().isForbidden());
        as("viewer@a.test", put("/api/clients/" + clientA + "/budget/policy"), "{\"max_change_pct\":10}")
                .andExpect(status().isForbidden());
        as("manager@a.test", put("/api/clients/" + clientA + "/budget/policy"), "{\"max_change_pct\":10}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.max_change_pct").value(10));
        // No Meta access: explicit notes, no invented numbers.
        as("manager@a.test", get("/api/clients/" + clientA + "/performance/campaigns"), null)
                .andExpect(jsonPath("$.meta_available").value(false))
                .andExpect(jsonPath("$.data_notes[0].code").value("meta_unavailable"))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void rolesAreEnforced() throws Exception {
        String pixel = "{\"meta_pixel_id\":\"1234567\",\"name\":\"P\",\"allowed_domains\":[\"a.test\"]}";
        as("viewer@a.test", post("/api/clients/" + clientA + "/tracking/pixels"), pixel).andExpect(status().isForbidden());
        as("viewer@a.test", get("/api/clients/" + clientA + "/tracking/pixels"), null).andExpect(status().isOk());
        as("manager@a.test", post("/api/clients/" + clientA + "/tracking/pixels"), pixel)
                .andExpect(status().isOk()).andExpect(jsonPath("$.capi_token_configured").value(false));
        as("manager@a.test", post("/api/clients/" + clientA + "/members"), "{\"user_email\":\"x@a.test\",\"role\":\"owner\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void publicTrackingEndpointNeedsNoLoginButValidatesThePixel() throws Exception {
        as(null, post("/api/tracking/events"), "{\"client_id\":\"" + clientA + "\",\"pixel_id\":\"7654321\","
                + "\"event_name\":\"PageView\",\"event_id\":\"evt-it-000001\"}").andExpect(status().isNotFound());
        as(null, post("/api/tracking/events"), "{\"client_id\":\"" + clientA + "\"}").andExpect(status().isBadRequest());
    }
}
