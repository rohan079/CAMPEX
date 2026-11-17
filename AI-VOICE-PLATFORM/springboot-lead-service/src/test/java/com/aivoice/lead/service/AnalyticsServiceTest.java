package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.AnalyticsDtos.AdRow;
import com.aivoice.lead.dto.AnalyticsDtos.ClientAnalytics;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.repository.AnalyticsRepository;
import com.aivoice.lead.repository.AnalyticsRepository.AdLeads;
import com.aivoice.lead.repository.AnalyticsRepository.AdLocation;
import com.aivoice.lead.repository.AnalyticsRepository.AdSalesRow;
import com.aivoice.lead.repository.AnalyticsRepository.DayCalls;
import com.aivoice.lead.repository.AnalyticsRepository.DayCount;
import com.aivoice.lead.repository.AnalyticsRepository.DaySales;
import com.aivoice.lead.repository.ClientRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2); // 06:00Z is 11:30 in Kolkata
    private static final UUID CLIENT = UUID.randomUUID();

    private AnalyticsRepository repo;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        repo = mock(AnalyticsRepository.class);
        ClientService clientService = mock(ClientService.class);
        Client client = new Client();
        client.setId(CLIENT);
        client.setName("Sunrise Homes");
        when(clientService.require(CLIENT)).thenReturn(client);
        when(clientService.businessName(client)).thenReturn("Sunrise Homes");
        AppProperties props = new AppProperties("development", "Acme", "", "Asia/Kolkata", 7, 3, "", "", "",
                null, "http://localhost:8000", 30, 1);
        service = new AnalyticsService(repo, mock(ClientRepository.class), clientService, props,
                Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneOffset.UTC), "INR");
    }

    private void stubData() {
        when(repo.leadsByDay(any(), any())).thenReturn(List.of(
                new DayCount(TODAY, 5), new DayCount(TODAY.minusDays(2), 3)));
        when(repo.adLeads(any(), any())).thenReturn(7L);
        when(repo.salesByDay(any(), any())).thenReturn(List.of(
                new DaySales(TODAY, "ad", 1, new BigDecimal("1000.00")),
                new DaySales(TODAY, "calling_agent", 2, new BigDecimal("3500.50")),
                new DaySales(TODAY.minusDays(1), "calling_agent", 1, new BigDecimal("500.00"))));
        when(repo.callsByDay(any(), any())).thenReturn(List.of(new DayCalls(TODAY, 10, 6)));
        when(repo.leadsByAd(any(), any())).thenReturn(List.of(
                new AdLeads("ad-1", "Summer sale", 5), new AdLeads("", null, 3)));
        when(repo.salesByAd(any(), any())).thenReturn(List.of(
                new AdSalesRow("ad-1", "ad", 1, new BigDecimal("1000.00")),
                new AdSalesRow("ad-1", "calling_agent", 3, new BigDecimal("4000.50"))));
        when(repo.reachByAdAndLocation(any(), any())).thenReturn(List.of(
                new AdLocation("ad-1", "Summer sale", "Hyderabad", 4000),
                new AdLocation("ad-1", "Summer sale", "Vijayawada", 1000),
                new AdLocation("ad-2", null, "Hyderabad", 500)));
        when(repo.adNames(any())).thenReturn(List.<String[]>of(new String[] {"ad-2", "Festival offer"}));
    }

    @Test
    void everyDayInTheRangeIsPresentEvenWithoutActivity() {
        stubData();
        ClientAnalytics result = service.forClient(CLIENT, 30);
        assertEquals(30, result.days());
        assertEquals(TODAY.minusDays(29), result.from());
        assertEquals(TODAY, result.to());
        assertEquals(30, result.leadsByDay().size());
        assertEquals(30, result.salesByDay().size());
        assertEquals(30, result.callingAgent().callsByDay().size());
        assertEquals(5, result.leadsByDay().get(29).leads());
        assertEquals(0, result.leadsByDay().get(28).leads());
        assertEquals(3, result.leadsByDay().get(27).leads());
    }

    @Test
    void rangeIsClampedToSaneBounds() {
        stubData();
        assertEquals(7, service.forClient(CLIENT, 1).days());
        assertEquals(365, service.forClient(CLIENT, 10_000).days());
    }

    @Test
    void salesAreSplitBetweenAdsAndTheCallingAgent() {
        stubData();
        ClientAnalytics result = service.forClient(CLIENT, 30);
        assertEquals("Sunrise Homes", result.clientName());
        assertEquals("INR", result.currency());

        assertEquals(1, result.summary().adSales());
        assertEquals(new BigDecimal("1000.00"), result.summary().adRevenue());
        assertEquals(3, result.summary().agentSales());
        assertEquals(new BigDecimal("4000.50"), result.summary().agentRevenue());
        assertEquals(4, result.summary().sales());
        assertEquals(new BigDecimal("5000.50"), result.summary().revenue());
        assertEquals(new BigDecimal("1000.00"), result.adSales().revenue());
        assertEquals(new BigDecimal("4000.50"), result.callingAgent().revenue());
    }

    @Test
    void callingAgentRatesUseConnectedCallsAsTheBase() {
        stubData();
        var agent = service.forClient(CLIENT, 30).callingAgent();
        assertEquals(10, agent.calls());
        assertEquals(6, agent.connected());
        assertEquals(0.6, agent.connectedRate());
        assertEquals(0.5, agent.saleRate()); // 3 agent sales over 6 connected calls
    }

    @Test
    void adTableCombinesLeadsSalesAndReachPerAd() {
        stubData();
        List<AdRow> ads = service.forClient(CLIENT, 30).adSales().byAd();
        AdRow summer = ads.stream().filter(a -> a.adId().equals("ad-1")).findFirst().orElseThrow();
        assertEquals("Summer sale", summer.adName());
        assertEquals(5, summer.leads());
        assertEquals(1, summer.sales());
        assertEquals(3, summer.agentSales());
        assertEquals(5000, summer.reach());
        assertEquals(0.001, summer.leadRate());
        assertEquals("ad-1", ads.get(0).adId(), "best-earning ad first");

        AdRow direct = ads.stream().filter(a -> a.adId().isEmpty()).findFirst().orElseThrow();
        assertEquals("No ad (direct)", direct.adName());
        AdRow festival = ads.stream().filter(a -> a.adId().equals("ad-2")).findFirst().orElseThrow();
        assertEquals("Festival offer", festival.adName(), "registered name fills a missing one");
    }

    @Test
    void reachIsGroupedByLocationAndAd() {
        stubData();
        var reach = service.forClient(CLIENT, 30).reach();
        assertEquals(5500, reach.totalReach());
        assertEquals("Hyderabad", reach.byLocation().get(0).location());
        assertEquals(4500, reach.byLocation().get(0).reach());
        assertEquals("ad-1", reach.byAd().get(0).adId());
        assertEquals("Hyderabad", reach.byAd().get(0).topLocations().get(0).location());
        assertEquals(3, reach.byAdLocation().size());
        assertEquals(5500, service.forClient(CLIENT, 30).summary().reach());
    }

    @Test
    void anEmptyClientProducesZerosNotErrors() {
        when(repo.leadsByDay(any(), any())).thenReturn(List.of());
        when(repo.salesByDay(any(), any())).thenReturn(List.of());
        when(repo.callsByDay(any(), any())).thenReturn(List.of());
        when(repo.leadsByAd(any(), any())).thenReturn(List.of());
        when(repo.salesByAd(any(), any())).thenReturn(List.of());
        when(repo.reachByAdAndLocation(any(), any())).thenReturn(List.of());
        when(repo.adNames(any())).thenReturn(List.of());
        ClientAnalytics result = service.forClient(CLIENT, 7);
        assertEquals(0, result.summary().leads());
        assertEquals(BigDecimal.ZERO, result.summary().revenue());
        assertEquals(0.0, result.summary().leadToSaleRate());
        assertEquals(0.0, result.callingAgent().saleRate());
        assertTrue(result.reach().byLocation().isEmpty());
        assertTrue(result.adSales().byAd().isEmpty());
    }

    @Test
    void allClientsModePassesNoClientFilter() {
        when(repo.leadsByDay(any(), isNull())).thenReturn(List.of(new DayCount(TODAY, 2)));
        when(repo.adLeads(any(), isNull())).thenReturn(1L);
        when(repo.salesByDay(any(), isNull())).thenReturn(List.of());
        when(repo.callsByDay(any(), isNull())).thenReturn(List.of());
        when(repo.leadsByAd(any(), isNull())).thenReturn(List.of());
        when(repo.salesByAd(any(), isNull())).thenReturn(List.of());
        when(repo.reachByAdAndLocation(any(), isNull())).thenReturn(List.of());
        when(repo.adNames(isNull())).thenReturn(List.of());
        ClientAnalytics result = service.forClient(null, 7);
        assertEquals("All clients", result.clientName());
        assertEquals(2, result.summary().leads());
    }

    @Test
    void slugsAreUrlSafe() {
        assertEquals("sunrise-homes", ClientService.slugify("Sunrise Homes!"));
        assertEquals("cafe-mocha", ClientService.slugify("  Café   Mocha  "));
        assertEquals("client", ClientService.slugify("తెలుగు"));
    }
}
