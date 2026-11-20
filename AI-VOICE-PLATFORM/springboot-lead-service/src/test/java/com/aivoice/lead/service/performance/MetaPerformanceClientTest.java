package com.aivoice.lead.service.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.config.PerformanceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Meta is a MockRestServiceServer: no request reaches graph.facebook.com. */
class MetaPerformanceClientTest {

    private final MetaOAuthProperties meta = new MetaOAuthProperties("a", "b", "c", "v27.0", "k", "f", "", List.of());
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void insightsUseFixedAttributionWindowsAndHeaderToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("https://graph.test/v27.0/act_123/insights?"),
                        org.hamcrest.Matchers.containsString("action_attribution_windows="),
                        org.hamcrest.Matchers.containsString("7d_click"),
                        org.hamcrest.Matchers.containsString("time_increment=1"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("access_token")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("""
                        {"data":[{"campaign_id":"1","campaign_name":"Villas","spend":"812.50","impressions":"40000",
                          "reach":"21000","frequency":"1.9","clicks":"700","inline_link_clicks":"520","account_currency":"INR",
                          "date_start":"2026-11-01","date_stop":"2026-11-01",
                          "actions":[{"action_type":"link_click","value":"520"},{"action_type":"lead","value":"14"},
                                     {"action_type":"onsite_conversion.lead_grouped","value":"9"}]}]}""",
                        MediaType.APPLICATION_JSON));
        PerformanceProperties props = new PerformanceProperties("https://graph.test", 5, "7d_click,1d_view", 28, true, 1,
                1000, 50, 10, 24);
        MetaPerformanceClient client = new MetaPerformanceClient(builder.build(), json, props, meta);
        var rows = client.insights("123", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), "campaign", true, null, "user-token");
        server.verify();
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("812.50"), rows.get(0).spend());
        assertEquals(520, rows.get(0).linkClicks());
        assertEquals(14, rows.get(0).metaLeads(), "aggregate lead action wins; parts are not double-counted");
        assertEquals(21000L, rows.get(0).reach());
    }

    @Test
    void budgetsAreConvertedFromMinorUnitsAndBreakdownsAreWhitelisted() throws Exception {
        assertEquals(new BigDecimal("1500.00"), MetaPerformanceClient.budget(json.readTree("{\"daily_budget\":\"150000\"}"), "daily_budget", "INR"));
        assertEquals(new BigDecimal("5000"), MetaPerformanceClient.budget(json.readTree("{\"daily_budget\":\"5000\"}"), "daily_budget", "JPY"));
        assertNull(MetaPerformanceClient.budget(json.readTree("{}"), "daily_budget", "INR"));
        MetaPerformanceClient client = new MetaPerformanceClient(RestClient.builder().build(), json,
                PerformanceProperties.defaults(), meta);
        assertThrows(IllegalArgumentException.class, () -> client.insights("1", LocalDate.now(), LocalDate.now(),
                "campaign", false, "age,region,impression_device", "t"));
        assertFalse(MetaPerformanceClient.SUPPORTED_BREAKDOWNS.contains("region,age"));
    }
}
