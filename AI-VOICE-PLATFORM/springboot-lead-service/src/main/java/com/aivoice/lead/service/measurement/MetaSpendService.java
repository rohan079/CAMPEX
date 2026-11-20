package com.aivoice.lead.service.measurement;

import com.aivoice.lead.entity.ClientAdAccount;
import com.aivoice.lead.repository.ClientAdAccountRepository;
import com.aivoice.lead.service.MetaConnectionStore;
import com.aivoice.lead.service.MetaGraphClient;
import com.aivoice.lead.service.MetaGraphClient.InsightRow;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Meta-reported spend and impressions for a client's mapped ad accounts, fetched live from Meta Insights with the
 * signed-in user's Meta Business connection. Imported reach data is never used here.
 */
@Service
public class MetaSpendService {

    public record Spend(boolean available, String note, List<String> adAccounts, List<InsightRow> rows) {
    }

    private record CacheKey(String account, LocalDate since, LocalDate until, String level, String owner) {
    }

    private record Cached(Instant at, List<InsightRow> rows) {
    }

    private static final Duration TTL = Duration.ofMinutes(10);

    private final ClientAdAccountRepository adAccounts;
    private final MetaConnectionStore connections;
    private final MetaGraphClient graph;
    private final Clock clock;
    private final String currency;
    private final Map<CacheKey, Cached> cache = new ConcurrentHashMap<>();

    public MetaSpendService(ClientAdAccountRepository adAccounts, MetaConnectionStore connections, MetaGraphClient graph,
                            Clock clock, @Value("${app.currency:INR}") String currency) {
        this.adAccounts = adAccounts;
        this.connections = connections;
        this.graph = graph;
        this.clock = clock;
        this.currency = currency;
    }

    public Spend spend(UUID clientId, String ownerEmail, LocalDate since, LocalDate until, String level,
                       String adAccountFilter) {
        List<String> accounts = adAccounts.findByClientIdOrderByAdAccountIdAsc(clientId).stream()
                .map(ClientAdAccount::getAdAccountId).toList();
        if (adAccountFilter != null && !adAccountFilter.isBlank()) {
            String wanted = adAccountFilter.startsWith("act_") ? adAccountFilter : "act_" + adAccountFilter;
            if (!accounts.contains(wanted)) {
                return new Spend(false, "That ad account is not mapped to this client", accounts, List.of());
            }
            accounts = List.of(wanted);
        }
        if (accounts.isEmpty()) {
            return new Spend(false, "No Meta ad accounts are mapped to this client (Tracking → Settings)", accounts, List.of());
        }
        String token = ownerEmail == null ? null : connections.userAccessToken(ownerEmail);
        if (token == null || token.isBlank()) {
            return new Spend(false, "Connect Meta Business under Channels to load Meta-reported spend", accounts, List.of());
        }
        List<InsightRow> rows = new ArrayList<>();
        try {
            for (String account : accounts) {
                CacheKey key = new CacheKey(account, since, until, level, ownerEmail);
                Cached cached = cache.get(key);
                if (cached == null || cached.at().plus(TTL).isBefore(clock.instant())) {
                    cached = new Cached(clock.instant(), graph.insights(account, since, until, level, token));
                    cache.put(key, cached);
                }
                rows.addAll(cached.rows());
            }
        } catch (MetaGraphException e) {
            return new Spend(false, "Meta Insights request failed (code " + e.graphCode() + ")", accounts, List.of());
        }
        for (InsightRow row : rows) {
            if (row.accountCurrency() != null && !row.accountCurrency().equalsIgnoreCase(currency)) {
                // Never divide revenue in one currency by spend in another.
                return new Spend(false, "Ad account currency " + row.accountCurrency() + " differs from APP_CURRENCY "
                        + currency + "; cost metrics are hidden", accounts, List.of());
            }
        }
        return new Spend(true, null, accounts, rows);
    }
}
