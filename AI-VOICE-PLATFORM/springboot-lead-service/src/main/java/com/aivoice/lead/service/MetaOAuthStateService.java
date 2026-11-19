package com.aivoice.lead.service;

import com.aivoice.lead.entity.MetaOAuthState;
import com.aivoice.lead.repository.MetaOAuthStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetaOAuthStateService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final MetaOAuthStateRepository states;

    public record PendingAuth(String ownerEmail, UUID clientId) {
    }

    public MetaOAuthStateService(MetaOAuthStateRepository states) {
        this.states = states;
    }

    @Transactional
    public String create(String ownerEmail, UUID clientId) {
        Instant now = Instant.now();
        states.deleteByExpiresAtBefore(now);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        MetaOAuthState record = new MetaOAuthState();
        record.setStateHash(hash(state));
        record.setOwnerEmail(ownerEmail);
        record.setClientId(clientId);
        record.setCreatedAt(now);
        record.setExpiresAt(now.plus(STATE_TTL));
        states.save(record);
        return state;
    }

    @Transactional
    public PendingAuth consume(String state) {
        if (state == null || state.length() < 32 || state.length() > 128) {
            throw invalidState();
        }
        String hash = hash(state);
        MetaOAuthState record = states.lockByStateHash(hash).orElseThrow(MetaOAuthStateService::invalidState);
        states.delete(record);
        if (!record.getExpiresAt().isAfter(Instant.now())) throw invalidState();
        return new PendingAuth(record.getOwnerEmail(), record.getClientId());
    }

    private static ResponseStatusException invalidState() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired OAuth state");
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
