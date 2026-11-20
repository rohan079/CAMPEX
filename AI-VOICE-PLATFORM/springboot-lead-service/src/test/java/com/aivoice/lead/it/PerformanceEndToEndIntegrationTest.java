package com.aivoice.lead.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.MetaConnectionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 2 end to end against PostgreSQL and a fake Meta Graph API served from this test (no real Meta call).
 * Verifies that Meta delivery and CRM outcomes are joined correctly and that every engine runs on real data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class PerformanceEndToEndIntegrationTest {

    static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
    static final HttpServer GRAPH = fakeGraph();

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
        r.add("app.voice-service-base-url", () -> "http://127.0.0.1:9");
        r.add("app.whatsapp.verify-token", () -> "x");
        r.add("app.whatsapp.app-secret", () -> "x");
        r.add("app.whatsapp.access-token", () -> "x");
        r.add("app.whatsapp.phone-number-id", () -> "x");
        r.add("app.whatsapp.graph-api-version", () -> "v27.0");
        r.add("campex.firebase.project-id", () -> "it-project");
        r.add("campex.access.platform-admin-emails", () -> "admin@it.test");
        r.add("campex.performance.graph-base-url", () -> "http://127.0.0.1:" + GRAPH.getAddress().getPort());
        r.add("campex.performance.scheduled-sync-enabled", () -> "false");
        r.add("meta.token-encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stop() {
        GRAPH.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    FirebaseTokenVerifier firebase;

    @MockitoBean
    MetaConnectionStore connections;

    private JsonNode call(MockHttpServletRequestBuilder req, String body, int expected) throws Exception {
        req.header("Authorization", "Bearer admin@it.test");
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        String out = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }

    @Test
    void metaAndCrmDataFlowThroughEveryEngine() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(connections.userAccessToken(anyString())).thenReturn("fake-user-token");
        String client = call(post("/api/clients"), "{\"name\":\"Perf " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"voice_agent_enabled\":false}", 200).get("id").asText();
        call(put("/api/clients/" + client + "/measurement/ad-accounts"), "{\"ad_account_ids\":[\"act_123\"]}", 200);

        // CRM: 10 leads from campaign 1 (6 qualified), 10 from campaign 2 (1 qualified).
        int phone = 1000 + (int) (Math.random() * 8000);
        for (int i = 0; i < 20; i++) {
            boolean first = i < 10;
            String lead = call(post("/api/clients/" + client + "/crm/leads"), "{\"phone_number\":\"98765" + phone + (i < 10 ? "0" : "") + i
                    + "\",\"name\":\"L" + i + "\",\"attribution\":{\"source\":\"website\",\"campaign_id\":\"" + (first ? "1001" : "1002")
                    + "\",\"ad_id\":\"" + (first ? "3001" : "3003") + "\",\"fbclid\":\"IwAR" + i + "\"}}", 200).get("id").asText();
            if ((first && i < 6) || i == 10) {
                call(patch("/api/leads/" + lead + "/status"), "{\"status\":\"QUALIFIED\"}", 200);
            }
        }

        String range = "?from=" + LocalDate.now().minusDays(27) + "&to=" + LocalDate.now();
        String rangeBody = "{\"from\":\"" + LocalDate.now().minusDays(27) + "\",\"to\":\"" + LocalDate.now() + "\"}";
        JsonNode campaigns = call(get("/api/clients/" + client + "/performance/campaigns" + range), null, 200);
        assertTrue(campaigns.get("meta_available").asBoolean());
        JsonNode c1 = find(campaigns.get("rows"), "1001");
        assertEquals(9000.0, c1.get("spend").asDouble());
        assertEquals(60, c1.get("meta_leads").asLong());
        assertEquals(10, c1.get("crm_leads").asLong());
        assertEquals(6, c1.get("qualified_leads").asLong());
        assertEquals(1500.0, c1.get("cost_per_qualified_lead").asDouble());
        assertEquals("OUTCOME_LEADS", c1.get("objective").asText());
        JsonNode totals = campaigns.get("totals");
        assertEquals(18500.0, totals.get("spend").asDouble());
        assertEquals(7, totals.get("qualified_leads").asLong());
        assertEquals(30000, totals.get("reach").asLong());

        JsonNode overview = call(get("/api/clients/" + client + "/performance/overview" + range), null, 200);
        assertTrue(overview.get("pct_change").has("spend"));

        // Budget guardrails, then recommendations (AI unavailable -> deterministic text only).
        call(put("/api/clients/" + client + "/budget/policy"), "{\"target_cost_per_qualified_lead\":2000,\"daily_spend_limit\":735}", 200);
        JsonNode budget = call(get("/api/clients/" + client + "/budget/analysis" + range), null, 200);
        assertEquals("increase", find(budget.get("campaigns"), "1001").get("decision").asText());
        assertEquals(385.0, find(budget.get("campaigns"), "1001").get("proposed_daily_budget").asDouble());
        JsonNode generated = call(post("/api/clients/" + client + "/recommendations/generate"), rangeBody, 200);
        assertTrue(generated.get("created").asInt() >= 2);
        assertFalse(generated.get("ai_explained").asBoolean());
        List<String> types = new ArrayList<>();
        generated.get("recommendations").forEach(r -> types.add(r.get("recommendation_type").asText()));
        assertTrue(types.contains("improve_lead_quality"), types.toString());
        assertTrue(types.contains("increase_budget"), types.toString());
        JsonNode again = call(post("/api/clients/" + client + "/recommendations/generate"), rangeBody, 200);
        assertEquals(0, again.get("created").asInt());
        assertTrue(again.get("skipped_duplicates").asInt() >= 2);
        String budgetRec = null;
        for (JsonNode r : generated.get("recommendations")) {
            if (r.get("recommendation_type").asText().equals("increase_budget")) budgetRec = r.get("id").asText();
            assertTrue(r.get("evidence").size() > 0);
            assertTrue(r.get("impact_is_estimate").asBoolean());
        }
        JsonNode accepted = call(patch("/api/recommendations/" + budgetRec + "/status"), "{\"status\":\"accepted\"}", 200);
        assertTrue(accepted.get("message").asText().contains("Nothing was changed in Meta"));
        call(patch("/api/recommendations/" + budgetRec + "/status"), "{\"status\":\"rejected\"}", 409);

        // Snapshot sync + anomaly detection: campaign 1001's CTR collapses in the last three days.
        JsonNode sync = call(post("/api/clients/" + client + "/performance/refresh"), null, 200);
        assertTrue(sync.get("snapshots").asInt() > 50);
        JsonNode anomalies = call(get("/api/clients/" + client + "/anomalies"), null, 200);
        boolean ctrAlert = false;
        for (JsonNode a : anomalies) ctrAlert |= a.get("metric").asText().equals("ctr") && a.get("campaign_id").asText().equals("1001");
        assertTrue(ctrAlert, anomalies.toString());
        JsonNode trends = call(get("/api/clients/" + client + "/performance/trends"), null, 200);
        assertTrue(trends.get("meta_available").asBoolean());

        JsonNode creative = call(get("/api/clients/" + client + "/creative-performance" + range), null, 200);
        assertEquals("Villas near Guntur", find(creative.get("creatives"), "3001", "ad_id").get("headline").asText());
        JsonNode audience = call(get("/api/clients/" + client + "/audience-performance" + range + "&breakdown=age"), null, 200);
        assertEquals(2, audience.get("segments").size());
        JsonNode placements = call(get("/api/clients/" + client + "/placement-performance" + range), null, 200);
        assertEquals("publisher_platform,platform_position", placements.get("breakdown").asText());

        String experiment = call(post("/api/clients/" + client + "/experiments"), "{\"name\":\"Headline test\",\"hypothesis\":\"Price in headline\","
                + "\"variable\":\"headline\",\"primary_metric\":\"ctr\",\"min_sample_size\":1000,\"start_date\":\""
                + LocalDate.now().minusDays(10) + "\",\"control\":{\"name\":\"A\",\"ad_id\":\"3001\"},"
                + "\"test\":{\"name\":\"B\",\"ad_id\":\"3002\"}}", 200).get("id").asText();
        call(post("/api/experiments/" + experiment + "/start"), null, 200);
        JsonNode completed = call(post("/api/experiments/" + experiment + "/complete"), null, 200);
        assertEquals("winner", completed.get("outcome").asText());
        assertTrue(completed.get("result_summary").asText().contains("Nothing was changed in Meta"));

        call(post("/api/clients/" + client + "/performance/ask"), "{\"question\":\"Which campaign is best?\"}", 503);

        synchronized (REQUESTS) {
            assertFalse(REQUESTS.isEmpty());
            assertTrue(REQUESTS.stream().allMatch(r -> r.startsWith("GET ")), "Campex must only read from Meta");
            assertTrue(REQUESTS.stream().noneMatch(r -> r.contains("access_token")), "token must not be in URLs");
        }
    }

    private static JsonNode find(JsonNode rows, String id) {
        return find(rows, id, rows.size() > 0 && rows.get(0).has("campaign_id") && !rows.get(0).has("id") ? "campaign_id" : "id");
    }

    private static JsonNode find(JsonNode rows, String id, String field) {
        for (JsonNode r : rows) if (id.equals(r.path(field).asText())) return r;
        throw new AssertionError(id + " not found in " + rows);
    }

    // ---------------------------------------------------------------- fake Meta Graph API

    static HttpServer fakeGraph() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String uri = exchange.getRequestURI().toString();
                REQUESTS.add(exchange.getRequestMethod() + " " + uri);
                if (!"Bearer fake-user-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, "{\"error\":{\"code\":190}}");
                    return;
                }
                respond(exchange, 200, route(exchange.getRequestURI().getPath(), query(exchange.getRequestURI().getRawQuery())));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            q.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }

    static String route(String path, Map<String, String> q) {
        if (path.endsWith("/act_123")) return "{\"id\":\"act_123\",\"currency\":\"INR\",\"timezone_name\":\"Asia/Kolkata\"}";
        if (path.endsWith("/campaigns")) {
            return "{\"data\":[{\"id\":\"1001\",\"name\":\"Villas\",\"objective\":\"OUTCOME_LEADS\",\"status\":\"ACTIVE\","
                    + "\"effective_status\":\"ACTIVE\",\"daily_budget\":\"35000\"},{\"id\":\"1002\",\"name\":\"Plots\","
                    + "\"objective\":\"OUTCOME_LEADS\",\"status\":\"ACTIVE\",\"effective_status\":\"ACTIVE\",\"daily_budget\":\"35000\"}]}";
        }
        if (path.endsWith("/adsets")) return "{\"data\":[]}";
        if (path.endsWith("/ads")) {
            return "{\"data\":[" + ad("3001", "1001", "Villas near Guntur") + "," + ad("3002", "1001", "Villas from [price]")
                    + "," + ad("3003", "1002", "Plots in Vijayawada") + "]}";
        }
        String level = q.getOrDefault("level", "");
        String breakdowns = q.get("breakdowns");
        if (breakdowns != null) {
            String[] keys = breakdowns.split(",");
            return "{\"data\":[" + segment(keys, "a", "8000", 90000, 60) + "," + segment(keys, "b", "10500", 290000, 50) + "]}";
        }
        if ("account".equals(level)) {
            return "{\"data\":[{\"spend\":\"18500\",\"impressions\":\"380000\",\"reach\":\"30000\",\"frequency\":\"12.6\","
                    + "\"inline_link_clicks\":\"5500\",\"account_currency\":\"INR\"}]}";
        }
        boolean daily = "1".equals(q.get("time_increment"));
        List<String[]> objects = switch (level) {
            case "ad" -> List.of(new String[] {"1001", "3001", "4500", "100000", "1500", "30"},
                    new String[] {"1001", "3002", "4500", "100000", "2200", "30"},
                    new String[] {"1002", "3003", "9500", "180000", "2500", "50"});
            default -> List.of(new String[] {"1001", null, "9000", "200000", "3000", "60"},
                    new String[] {"1002", null, "9500", "180000", "2500", "50"});
        };
        StringBuilder out = new StringBuilder("{\"data\":[");
        if (!daily) {
            for (String[] o : objects) out.append(out.length() > 9 ? "," : "").append(row(o, o[2], o[3], o[4], o[5], null));
        } else {
            String range = q.get("time_range");
            LocalDate since = LocalDate.parse(range.substring(range.indexOf("since\":\"") + 8, range.indexOf("since\":\"") + 18));
            LocalDate until = LocalDate.parse(range.substring(range.indexOf("until\":\"") + 8, range.indexOf("until\":\"") + 18));
            for (LocalDate d = since; !d.isAfter(until); d = d.plusDays(1)) {
                boolean collapse = !d.isBefore(until.minusDays(2)) && "1001".equals(objects.get(0)[0]);
                for (String[] o : objects) {
                    long wobble = d.getDayOfMonth() % 3;
                    long clicks = "1001".equals(o[0]) && collapse ? 20 : 100 + wobble * 4;
                    out.append(out.length() > 9 ? "," : "").append(row(o, "300", String.valueOf(10000 + wobble * 150),
                            String.valueOf(clicks), String.valueOf(3 + wobble), d));
                }
            }
        }
        return out.append("]}").toString();
    }

    static String row(String[] o, String spend, String impressions, String clicks, String leads, LocalDate day) {
        return "{\"campaign_id\":\"" + o[0] + "\",\"campaign_name\":\"" + ("1001".equals(o[0]) ? "Villas" : "Plots") + "\""
                + (o[1] == null ? "" : ",\"ad_id\":\"" + o[1] + "\",\"ad_name\":\"Ad " + o[1] + "\",\"adset_id\":\"as" + o[1] + "\"")
                + ",\"spend\":\"" + spend + "\",\"impressions\":\"" + impressions + "\",\"reach\":\"" + Long.parseLong(impressions) / 2
                + "\",\"frequency\":\"2.0\",\"clicks\":\"" + clicks + "\",\"inline_link_clicks\":\"" + clicks
                + "\",\"actions\":[{\"action_type\":\"lead\",\"value\":\"" + leads + "\"}],\"account_currency\":\"INR\""
                + (day == null ? "" : ",\"date_start\":\"" + day + "\",\"date_stop\":\"" + day + "\"") + "}";
    }

    static String segment(String[] keys, String suffix, String spend, long impressions, long leads) {
        StringBuilder s = new StringBuilder("{");
        for (String k : keys) s.append("\"").append(k).append("\":\"").append(k).append("-").append(suffix).append("\",");
        return s + "\"spend\":\"" + spend + "\",\"impressions\":\"" + impressions + "\",\"inline_link_clicks\":\""
                + impressions / 100 + "\",\"actions\":[{\"action_type\":\"lead\",\"value\":\"" + leads + "\"}]}";
    }

    static String ad(String id, String campaign, String headline) {
        return "{\"id\":\"" + id + "\",\"name\":\"Ad " + id + "\",\"campaign_id\":\"" + campaign + "\",\"adset_id\":\"as" + id
                + "\",\"effective_status\":\"ACTIVE\",\"creative\":{\"id\":\"cr" + id + "\",\"title\":\"" + headline
                + "\",\"body\":\"Body\",\"call_to_action_type\":\"LEARN_MORE\",\"image_url\":\"https://img.test/" + id + ".jpg\"}}";
    }
}
