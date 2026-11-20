package com.aivoice.lead.service.performance;

import com.aivoice.lead.entity.ClientAdAccount;
import com.aivoice.lead.repository.ClientAdAccountRepository;
import com.aivoice.lead.service.MetaConnectionStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Which ad accounts and which stored Meta token to use for a client. The signed-in user's own Meta connection is
 * preferred; otherwise the connection of the user who mapped the ad account (a client manager). Tokens never leave
 * the server.
 */
@Component
public class MetaAccessResolver {

    public record MetaAccess(boolean available, String note, List<String> adAccounts, String token) {
    }

    private final ClientAdAccountRepository adAccounts;
    private final MetaConnectionStore connections;

    public MetaAccessResolver(ClientAdAccountRepository adAccounts, MetaConnectionStore connections) {
        this.adAccounts = adAccounts;
        this.connections = connections;
    }

    public MetaAccess resolve(UUID clientId, String requesterEmail) {
        List<ClientAdAccount> mapped = adAccounts.findByClientIdOrderByAdAccountIdAsc(clientId);
        List<String> ids = mapped.stream().map(ClientAdAccount::getAdAccountId).toList();
        if (ids.isEmpty()) {
            return new MetaAccess(false, "No Meta ad accounts are mapped to this client (Tracking → Ad accounts)", ids, null);
        }
        String token = requesterEmail == null ? null : connections.userAccessToken(requesterEmail);
        if (token == null || token.isBlank()) {
            for (ClientAdAccount account : mapped) {
                if (account.getCreatedBy() != null) {
                    token = connections.userAccessToken(account.getCreatedBy());
                    if (token != null && !token.isBlank()) break;
                }
            }
        }
        if (token == null || token.isBlank()) {
            return new MetaAccess(false, "No Meta Business connection is available for this client (Channels → Connect Meta)", ids, null);
        }
        return new MetaAccess(true, null, ids, token);
    }
}
