package com.aivoice.lead.service;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.ClientDtos.AdDto;
import com.aivoice.lead.dto.ClientDtos.ClientDto;
import com.aivoice.lead.dto.ClientDtos.CreateClientRequest;
import com.aivoice.lead.dto.ClientDtos.ReachRow;
import com.aivoice.lead.dto.ClientDtos.RegisterAdRequest;
import com.aivoice.lead.dto.ClientDtos.UpdateClientMetaAppRequest;
import com.aivoice.lead.dto.ClientDtos.UpdateClientVoiceSettingsRequest;
import com.aivoice.lead.entity.Ad;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.repository.AdRepository;
import com.aivoice.lead.repository.AnalyticsRepository;
import com.aivoice.lead.repository.AnalyticsRepository.ClientTotals;
import com.aivoice.lead.repository.ClientRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.VoiceCallRepository;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The advertisers we run marketing for, and which ads belong to whom. */
@Service
public class ClientService {

    private final ClientRepository clients;
    private final LeadRepository leads;
    private final VoiceCallRepository calls;
    private final AdRepository ads;
    private final AnalyticsRepository analytics;
    private final AppProperties props;
    private final MetaTokenCipher cipher;

    public ClientService(ClientRepository clients, LeadRepository leads, VoiceCallRepository calls,
                         AdRepository ads, AnalyticsRepository analytics,
                         AppProperties props, MetaTokenCipher cipher) {
        this.clients = clients;
        this.leads = leads;
        this.calls = calls;
        this.ads = ads;
        this.analytics = analytics;
        this.props = props;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<ClientDto> list() {
        Map<UUID, Long> leadCounts = new HashMap<>();
        for (ClientTotals totals : analytics.leadCountsPerClient()) {
            leadCounts.put(totals.clientId(), totals.count());
        }
        return clients.findAllByOrderByNameAsc().stream()
                .map(c -> toDto(c, leadCounts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public ClientDto create(CreateClientRequest request) {
        Client client = new Client();
        client.setName(request.name().trim());
        String base = slugify(request.slug() == null || request.slug().isBlank() ? request.name() : request.slug());
        client.setSlug(uniqueSlug(base));
        client.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
        client.setVoiceAgentEnabled(request.voiceAgentEnabled());
        client.setKnowledgeBase(cleanKnowledgeBase(request.knowledgeBase()));
        return toDto(clients.save(client), 0);
    }

    @Transactional
    public ClientDto updateVoiceSettings(UUID clientId, UpdateClientVoiceSettingsRequest request) {
        Client client = require(clientId);
        client.setVoiceAgentEnabled(request.voiceAgentEnabled());
        client.setKnowledgeBase(cleanKnowledgeBase(request.knowledgeBase()));
        if (!request.voiceAgentEnabled()) {
            leads.findByClientId(clientId).forEach(lead -> calls.cancelQueued(lead.getId()));
        }
        return toDto(clients.save(client), leadCount(clientId));
    }

    @Transactional
    public ClientDto updateMetaAppSettings(UUID clientId, UpdateClientMetaAppRequest request) {
        if (!cipher.isConfigured()) {
            throw new IllegalStateException("Meta token encryption is not configured");
        }
        Client client = require(clientId);
        if (request.clear()) {
            client.setMetaAppId(null);
            client.setMetaAppSecretCiphertext(null);
            client.setMetaAppSecretNonce(null);
            client.setMetaLoginConfigId(null);
            return toDto(clients.save(client), leadCount(clientId));
        }

        String appId = request.metaAppId() == null ? "" : request.metaAppId().trim();
        String secret = request.metaAppSecret() == null ? "" : request.metaAppSecret().trim();
        String loginConfig = request.metaLoginConfigId() == null ? "" : request.metaLoginConfigId().trim();

        boolean hasExistingSecret = MetaAppCredentialsResolver.hasClientCredentials(client)
                || (client.getMetaAppSecretCiphertext() != null && client.getMetaAppSecretNonce() != null);
        if (appId.isBlank()) {
            throw new IllegalArgumentException("Meta App ID is required");
        }
        if (secret.isBlank() && !hasExistingSecret) {
            throw new IllegalArgumentException("Meta App Secret is required");
        }

        client.setMetaAppId(appId);
        if (!secret.isBlank()) {
            MetaTokenCipher.EncryptedValue encrypted = cipher.encrypt(
                    secret, MetaAppCredentialsResolver.secretAad(client.getId()));
            client.setMetaAppSecretCiphertext(encrypted.ciphertext());
            client.setMetaAppSecretNonce(encrypted.nonce());
        }
        client.setMetaLoginConfigId(loginConfig.isBlank() ? null : loginConfig);
        return toDto(clients.save(client), leadCount(clientId));
    }

    private long leadCount(UUID clientId) {
        return analytics.leadCountsPerClient().stream()
                .filter(row -> row.clientId().equals(clientId))
                .mapToLong(ClientTotals::count)
                .findFirst().orElse(0L);
    }

    @Transactional(readOnly = true)
    public Client require(UUID id) {
        return clients.findById(id).orElseThrow(() -> new NotFoundException("Client not found"));
    }

    @Transactional(readOnly = true)
    public Client defaultClient() {
        return require(Client.DEFAULT_ID);
    }

    /**
     * Which client a new lead belongs to: the explicit slug, else whoever owns the ad, else the default client.
     */
    @Transactional(readOnly = true)
    public Client resolve(String slug, String... adIds) {
        if (slug != null && !slug.isBlank()) {
            return clients.findBySlug(slug.trim().toLowerCase())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown client: " + slug));
        }
        for (String adId : adIds) {
            Optional<Client> owner = byAd(adId);
            if (owner.isPresent()) {
                return owner.get();
            }
        }
        return defaultClient();
    }

    @Transactional(readOnly = true)
    public Optional<Client> byAd(String externalAdId) {
        if (externalAdId == null || externalAdId.isBlank()) {
            return Optional.empty();
        }
        return ads.findByExternalId(externalAdId.trim()).flatMap(ad -> clients.findById(ad.getClientId()));
    }

    /** The default client speaks as the business configured in BUSINESS_NAME. */
    public String businessName(Client client) {
        return client.isDefault() ? props.businessName() : client.getName();
    }

    public String businessDescription(Client client) {
        if (client.getDescription() != null && !client.getDescription().isBlank()) {
            return client.getDescription();
        }
        return client.isDefault() ? props.businessDescription() : "";
    }

    public boolean voiceAgentEnabled(Client client) {
        return client.isVoiceAgentEnabled();
    }

    public String knowledgeBase(Client client) {
        return client.getKnowledgeBase() == null ? "" : client.getKnowledgeBase();
    }

    @Transactional
    public AdDto registerAd(UUID clientId, RegisterAdRequest request) {
        require(clientId);
        String externalId = request.externalId().trim();
        Ad ad = ads.findByExternalId(externalId).orElseGet(Ad::new);
        if (ad.getId() != null && !ad.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("That ad id already belongs to another client");
        }
        ad.setClientId(clientId);
        ad.setExternalId(externalId);
        if (request.name() != null && !request.name().isBlank()) {
            ad.setName(request.name().trim());
        }
        return toDto(ads.save(ad));
    }

    @Transactional(readOnly = true)
    public List<AdDto> listAds(UUID clientId) {
        require(clientId);
        return ads.findByClientIdOrderByCreatedAtDesc(clientId).stream().map(this::toDto).toList();
    }

    @Transactional
    public int importReach(UUID clientId, List<ReachRow> rows) {
        require(clientId);
        return analytics.upsertReach(clientId, rows);
    }

    private ClientDto toDto(Client c, long leads) {
        boolean metaConfigured = MetaAppCredentialsResolver.hasClientCredentials(c);
        return new ClientDto(
                c.getId().toString(),
                businessName(c),
                c.getSlug(),
                businessDescription(c),
                leads,
                c.isVoiceAgentEnabled(),
                knowledgeBase(c),
                c.getCreatedAt(),
                metaConfigured,
                metaConfigured ? c.getMetaAppId() : null,
                c.getMetaLoginConfigId());
    }

    private static String cleanKnowledgeBase(String knowledgeBase) {
        return knowledgeBase == null ? "" : knowledgeBase.strip();
    }

    private AdDto toDto(Ad ad) {
        return new AdDto(ad.getId().toString(), ad.getClientId().toString(), ad.getExternalId(), ad.getName());
    }

    static String slugify(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "client" : slug.substring(0, Math.min(slug.length(), 70));
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        for (int n = 2; clients.existsBySlug(candidate); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }
}
