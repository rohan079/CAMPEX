package com.aivoice.lead.service;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.repository.ClientRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves which Meta app to use for Business Login.
 * Prefer the selected client's stored App ID/Secret; otherwise fall back to platform META_* env vars.
 */
@Service
public class MetaAppCredentialsResolver {

    private final MetaOAuthProperties properties;
    private final ClientRepository clients;
    private final MetaTokenCipher cipher;

    public MetaAppCredentialsResolver(
            MetaOAuthProperties properties,
            ClientRepository clients,
            MetaTokenCipher cipher) {
        this.properties = properties;
        this.clients = clients;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public MetaAppCredentials resolve(UUID clientId) {
        if (clientId != null) {
            Client client = clients.findById(clientId).orElse(null);
            if (client != null && hasClientCredentials(client)) {
                String secret = cipher.decrypt(
                        client.getMetaAppSecretCiphertext(),
                        client.getMetaAppSecretNonce(),
                        secretAad(client.getId()));
                String loginConfig = blankToNull(client.getMetaLoginConfigId());
                if (loginConfig == null) {
                    loginConfig = blankToNull(properties.loginConfigId());
                }
                return new MetaAppCredentials(
                        client.getMetaAppId().trim(),
                        secret,
                        loginConfig == null ? "" : loginConfig,
                        properties.oauthRedirectUri(),
                        properties.graphApiVersion(),
                        client.getId(),
                        true);
            }
        }
        return platformCredentials();
    }

    public MetaAppCredentials platformCredentials() {
        return new MetaAppCredentials(
                properties.appId(),
                properties.appSecret(),
                properties.loginConfigId() == null ? "" : properties.loginConfigId(),
                properties.oauthRedirectUri(),
                properties.graphApiVersion(),
                null,
                false);
    }

    public static boolean hasClientCredentials(Client client) {
        return client.getMetaAppId() != null && !client.getMetaAppId().isBlank()
                && client.getMetaAppSecretCiphertext() != null
                && client.getMetaAppSecretNonce() != null;
    }

    public static String secretAad(UUID clientId) {
        return "meta-app-secret:" + clientId.toString().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
