package com.aivoice.lead.service;

import com.aivoice.lead.config.MetaOAuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class MetaTokenCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MetaOAuthProperties properties;

    public record EncryptedValue(byte[] nonce, byte[] ciphertext) {
    }

    public MetaTokenCipher(MetaOAuthProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return encryptionKey() != null;
    }

    public EncryptedValue encrypt(String plaintext, String associatedData) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requiredKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Meta token encryption failed", e);
        }
    }

    public String decrypt(byte[] ciphertext, byte[] nonce, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requiredKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Meta token decryption failed", e);
        }
    }

    private SecretKeySpec encryptionKey() {
        String encoded = properties.tokenEncryptionKey();
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] key = Base64.getDecoder().decode(encoded.trim());
            return key.length == 32 ? new SecretKeySpec(key, "AES") : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SecretKeySpec requiredKey() {
        SecretKeySpec key = encryptionKey();
        if (key == null) {
            throw new IllegalStateException("META_TOKEN_ENCRYPTION_KEY must be base64 for exactly 32 bytes");
        }
        return key;
    }
}
