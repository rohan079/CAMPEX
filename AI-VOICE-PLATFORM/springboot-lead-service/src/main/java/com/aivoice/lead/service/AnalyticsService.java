package com.aivoice.lead.service;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.AnalyticsDtos.AdLocationReach;
import com.aivoice.lead.dto.AnalyticsDtos.AdReachRow;
import com.aivoice.lead.dto.AnalyticsDtos.AdRow;
import com.aivoice.lead.dto.AnalyticsDtos.AdSales;
import com.aivoice.lead.dto.AnalyticsDtos.CallingAgent;
import com.aivoice.lead.dto.AnalyticsDtos.CallsDay;
import com.aivoice.lead.dto.AnalyticsDtos.ClientAnalytics;
import com.aivoice.lead.dto.AnalyticsDtos.ClientComparisonRow;
import com.aivoice.lead.dto.AnalyticsDtos.Comparison;
import com.aivoice.lead.dto.AnalyticsDtos.LeadsDay;
import com.aivoice.lead.dto.AnalyticsDtos.LocationReach;
import com.aivoice.lead.dto.AnalyticsDtos.Reach;
import com.aivoice.lead.dto.AnalyticsDtos.SalesDay;
import com.aivoice.lead.dto.AnalyticsDtos.Summary;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.entity.Sale;
import com.aivoice.lead.repository.AnalyticsRepository;
import com.aivoice.lead.repository.AnalyticsRepository.AdLeads;
import com.aivoice.lead.repository.AnalyticsRepository.AdLocation;
import com.aivoice.lead.repository.AnalyticsRepository.AdSalesRow;
import com.aivoice.lead.repository.AnalyticsRepository.DayCalls;
import com.aivoice.lead.repository.AnalyticsRepository.DayCount;
import com.aivoice.lead.repository.AnalyticsRepository.DaySales;
import com.aivoice.lead.repository.AnalyticsRepository.Range;
import com.aivoice.lead.repository.ClientRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns raw group-by rows into the series the dashboard charts. */
@Service
public class AnalyticsService {

    public static final int MIN_DAYS = 7;
    public static final int MAX_DAYS = 365;

    private final AnalyticsRepository repo;
    private final ClientRepository clients;
    private final ClientService clientService;
    private final AppProperties props;
    private final Clock clock;
    private final String currency;

    public AnalyticsService(AnalyticsRepository repo, ClientRepository clients, ClientService clientService,
                            AppProperties props, Clock clock, @Value("${app.currency:INR}") String currency) {
        this.repo = repo;
        this.clients = clients;
        this.clientService = clientService;
        this.props = props;
        this.clock = clock;
        this.currency = currency;
    }

    Range range(int days) {
        int clamped = Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);
        var zone = props.zone();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate from = today.minusDays(clamped - 1L);
        return new Range(from, today, from.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant(), zone.getId());
    }

    /** {@code clientId == null} aggregates every client. */
    @Transactional(readOnly = true)
    public ClientAnalytics forClient(UUID clientId, int days) {
        Client client = clientId == null ? null : clientService.require(clientId);
        Range range = range(days);
        int span = (int) (range.to().toEpochDay() - range.from().toEpochDay()) + 1;
        List<LocalDate> axis = axis(range);

        Map<LocalDate, Long> leadsPerDay = new HashMap<>();
        repo.leadsByDay(range, clientId).forEach(r -> leadsPerDay.merge(r.day(), r.count(), Long::sum));

        List<DaySales> salesRows = repo.salesByDay(range, clientId);
        List<SalesDay> allSales = salesSeries(axis, salesRows, null);
        List<SalesDay> adSalesSeries = salesSeries(axis, salesRows, Sale.CHANNEL_AD);
        List<SalesDay> agentSalesSeries = salesSeries(axis, salesRows, Sale.CHANNEL_AGENT);

        Map<LocalDate, DayCalls> callsPerDay = new HashMap<>();
        repo.callsByDay(range, clientId).forEach(r -> callsPerDay.put(r.day(), r));
        List<CallsDay> callsSeries = axis.stream().map(d -> {
            DayCalls c = callsPerDay.get(d);
            return new CallsDay(d, c == null ? 0 : c.calls(), c == null ? 0 : c.connected());
        }).toList();

        long totalLeads = leadsPerDay.values().stream().mapToLong(Long::longValue).sum();
        long calls = callsSeries.stream().mapToLong(CallsDay::calls).sum();
        long connected = callsSeries.stream().mapToLong(CallsDay::connected).sum();

        Totals ad = totals(adSalesSeries);
        Totals agent = totals(agentSalesSeries);
        long sales = ad.count + agent.count;
        BigDecimal revenue = ad.amount.add(agent.amount);

        List<AdLocation> reachRows = repo.reachByAdAndLocation(range, clientId);
        List<AdLeads> leadsByAd = repo.leadsByAd(range, clientId);
        List<String[]> registeredAds = repo.adNames(clientId);
        Reach reach = reach(reachRows, leadsByAd, registeredAds);
        List<AdRow> adRows = adRows(leadsByAd, repo.salesByAd(range, clientId), reachRows, registeredAds);

        Summary summary = new Summary(totalLeads, repo.adLeads(range, clientId), calls, connected, sales, revenue,
                ad.count, ad.amount, agent.count, agent.amount, reach.totalReach(),
                ratio(sales, totalLeads));

        return new ClientAnalytics(
                client == null ? null : client.getId().toString(),
                client == null ? "All clients" : clientService.businessName(client),
                currency, range.from(), range.to(), span, summary,
                axis.stream().map(d -> new LeadsDay(d, leadsPerDay.getOrDefault(d, 0L))).toList(),
                allSales,
                new AdSales(ad.count, ad.amount, adSalesSeries, adRows),
                new CallingAgent(calls, connected, agent.count, agent.amount, ratio(connected, calls),
                        ratio(agent.count, connected), callsSeries, agentSalesSeries),
                reach);
    }

    @Transactional(readOnly = true)
    public Comparison compare(int days) {
        Range range = range(days);
        int span = (int) (range.to().toEpochDay() - range.from().toEpochDay()) + 1;
        Map<UUID, Long> leads = new HashMap<>();
        repo.leadsPerClient(range).forEach(t -> leads.put(t.clientId(), t.count()));
        Map<UUID, Long> calls = new HashMap<>();
        repo.callsPerClient(range).forEach(t -> calls.put(t.clientId(), t.count()));
        Map<UUID, Long> reach = new HashMap<>();
        repo.reachPerClient(range).forEach(t -> reach.put(t.clientId(), t.count()));
        Map<UUID, BigDecimal> adRevenue = new HashMap<>();
        Map<UUID, BigDecimal> agentRevenue = new HashMap<>();
        Map<UUID, Long> salesCount = new HashMap<>();
        repo.salesPerClient(range).forEach(t -> {
            salesCount.merge(t.clientId(), t.count(), Long::sum);
            (Sale.CHANNEL_AD.equals(t.channel()) ? adRevenue : agentRevenue)
                    .merge(t.clientId(), t.amount(), BigDecimal::add);
        });

        List<ClientComparisonRow> rows = new ArrayList<>();
        for (Client client : clients.findAllByOrderByNameAsc()) {
            UUID id = client.getId();
            BigDecimal ad = adRevenue.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal agent = agentRevenue.getOrDefault(id, BigDecimal.ZERO);
            rows.add(new ClientComparisonRow(id.toString(), clientService.businessName(client),
                    leads.getOrDefault(id, 0L), calls.getOrDefault(id, 0L), salesCount.getOrDefault(id, 0L),
                    ad.add(agent), ad, agent, reach.getOrDefault(id, 0L)));
        }
        rows.sort(Comparator.comparing(ClientComparisonRow::revenue).reversed()
                .thenComparing(Comparator.comparingLong(ClientComparisonRow::leads).reversed()));
        return new Comparison(currency, range.from(), range.to(), span, rows);
    }

    // ------------------------------------------------------------------ assembly

    private static List<LocalDate> axis(Range range) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    /** One entry per day; {@code channel == null} adds both channels together. */
    static List<SalesDay> salesSeries(List<LocalDate> axis, List<DaySales> rows, String channel) {
        Map<LocalDate, long[]> counts = new HashMap<>();
        Map<LocalDate, BigDecimal> amounts = new HashMap<>();
        for (DaySales row : rows) {
            if (channel != null && !channel.equals(row.channel())) {
                continue;
            }
            counts.computeIfAbsent(row.day(), d -> new long[1])[0] += row.count();
            amounts.merge(row.day(), row.amount(), BigDecimal::add);
        }
        return axis.stream()
                .map(d -> new SalesDay(d, counts.containsKey(d) ? counts.get(d)[0] : 0,
                        amounts.getOrDefault(d, BigDecimal.ZERO)))
                .toList();
    }

    private record Totals(long count, BigDecimal amount) {
    }

    private static Totals totals(List<SalesDay> series) {
        long count = series.stream().mapToLong(SalesDay::sales).sum();
        BigDecimal amount = series.stream().map(SalesDay::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Totals(count, amount);
    }

    static double ratio(long part, long whole) {
        return whole == 0 ? 0 : Math.round((double) part / whole * 10_000) / 10_000.0;
    }

    private static String adLabel(String adId, Map<String, String> names) {
        if (adId == null || adId.isEmpty()) {
            return "No ad (direct)";
        }
        String name = names.get(adId);
        return name == null || name.isBlank() ? adId : name;
    }

    /** Best known name per ad: registered name, else the name on reach rows, else the lead headline. */
    private static Map<String, String> nameLookup(List<String[]> registered, List<AdLocation> reach,
                                                  List<AdLeads> leads) {
        Map<String, String> names = new HashMap<>();
        leads.forEach(l -> putIfText(names, l.adId(), l.name()));
        reach.forEach(r -> putIfText(names, r.adId(), r.adName()));
        registered.forEach(r -> putIfText(names, r[0], r[1]));
        return names;
    }

    private static void putIfText(Map<String, String> map, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private List<AdRow> adRows(List<AdLeads> leads, List<AdSalesRow> sales, List<AdLocation> reach,
                               List<String[]> registered) {
        Map<String, String> names = nameLookup(registered, reach, leads);
        Set<String> ids = new LinkedHashSet<>();
        leads.forEach(l -> ids.add(l.adId()));
        sales.forEach(s -> ids.add(s.adId()));
        reach.forEach(r -> ids.add(r.adId()));

        Map<String, Long> leadCount = new HashMap<>();
        leads.forEach(l -> leadCount.merge(l.adId(), l.leads(), Long::sum));
        Map<String, Long> reachTotal = new HashMap<>();
        reach.forEach(r -> reachTotal.merge(r.adId(), r.reach(), Long::sum));

        List<AdRow> rows = new ArrayList<>();
        for (String id : ids) {
            long adSales = 0;
            long agentSales = 0;
            BigDecimal adRevenue = BigDecimal.ZERO;
            BigDecimal agentRevenue = BigDecimal.ZERO;
            for (AdSalesRow s : sales) {
                if (!s.adId().equals(id)) {
                    continue;
                }
                if (Sale.CHANNEL_AD.equals(s.channel())) {
                    adSales += s.count();
                    adRevenue = adRevenue.add(s.amount());
                } else {
                    agentSales += s.count();
                    agentRevenue = agentRevenue.add(s.amount());
                }
            }
            long leadsForAd = leadCount.getOrDefault(id, 0L);
            long reachForAd = reachTotal.getOrDefault(id, 0L);
            rows.add(new AdRow(id, adLabel(id, names), leadsForAd, adSales, adRevenue, agentSales, agentRevenue,
                    reachForAd, ratio(leadsForAd, reachForAd)));
        }
        rows.sort(Comparator.comparing((AdRow r) -> r.revenue().add(r.agentRevenue())).reversed()
                .thenComparing(Comparator.comparingLong(AdRow::leads).reversed()));
        return rows;
    }

    Reach reach(List<AdLocation> rows, List<AdLeads> leads, List<String[]> registered) {
        Map<String, String> names = nameLookup(registered, rows, leads);
        Map<String, Long> byLocation = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byAdLocation = new LinkedHashMap<>();
        long total = 0;
        for (AdLocation row : rows) {
            total += row.reach();
            byLocation.merge(row.location(), row.reach(), Long::sum);
            byAdLocation.computeIfAbsent(row.adId(), k -> new LinkedHashMap<>())
                    .merge(row.location(), row.reach(), Long::sum);
        }
        Map<String, Long> leadsPerAd = new HashMap<>();
        leads.forEach(l -> leadsPerAd.merge(l.adId(), l.leads(), Long::sum));

        List<LocationReach> locations = byLocation.entrySet().stream()
                .map(e -> new LocationReach(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(LocationReach::reach).reversed())
                .toList();

        List<AdReachRow> ads = new ArrayList<>();
        List<AdLocationReach> matrix = new ArrayList<>();
        byAdLocation.forEach((adId, perLocation) -> {
            long adReach = perLocation.values().stream().mapToLong(Long::longValue).sum();
            List<LocationReach> sorted = perLocation.entrySet().stream()
                    .map(e -> new LocationReach(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingLong(LocationReach::reach).reversed())
                    .toList();
            long leadsForAd = leadsPerAd.getOrDefault(adId, 0L);
            ads.add(new AdReachRow(adId, adLabel(adId, names), adReach, leadsForAd, ratio(leadsForAd, adReach),
                    sorted.stream().limit(3).toList()));
            sorted.forEach(l -> matrix.add(new AdLocationReach(adId, adLabel(adId, names), l.location(), l.reach())));
        });
        ads.sort(Comparator.comparingLong(AdReachRow::reach).reversed());
        matrix.sort(Comparator.comparingLong(AdLocationReach::reach).reversed());
        return new Reach(total, locations, ads, matrix.stream().limit(500).toList());
    }
}
