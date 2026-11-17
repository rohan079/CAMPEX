package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aivoice.lead.config.MetaOAuthProperties;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetaTokenCipherTest {

    @Test
    void encryptsTokensAndBindsCiphertextToOwnerContext() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        MetaOAuthProperties properties = new MetaOAuthProperties(
                "app-id", "app-secret", "https://campex.example/oauth/meta/callback", "v27.0", key,
                "https://campex.example", List.of("pages_show_list"));
        MetaTokenCipher cipher = new MetaTokenCipher(properties);

        MetaTokenCipher.EncryptedValue encrypted = cipher.encrypt("opaque-meta-token", "user:operator@example.test");
        assertNotEquals("opaque-meta-token", new String(encrypted.ciphertext()));
        assertEquals("opaque-meta-token", cipher.decrypt(
                encrypted.ciphertext(), encrypted.nonce(), "user:operator@example.test"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(
                encrypted.ciphertext(), encrypted.nonce(), "user:other@example.test"));
    }
}
