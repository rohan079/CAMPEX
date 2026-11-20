package com.aivoice.lead.service.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalizes and SHA-256 hashes customer identifiers following Meta's customer-information parameter rules
 * (lowercase, trimmed; phone as digits including country code; names without punctuation). Values that already look
 * like a SHA-256 hex digest are passed through (the website SDK hashes in the browser).
 */
public final class UserDataHasher {

    private static final Pattern SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Meta user_data keys that must be hashed. */
    public static final List<String> HASHED_KEYS = List.of("em", "ph", "fn", "ln", "ct", "st", "zp", "country",
            "external_id", "ge", "db");

    private UserDataHasher() {
    }

    public static boolean isSha256(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Normalized form of one identifier, or null when it is empty or invalid. */
    public static String normalize(String key, String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "em" -> EMAIL.matcher(lower).matches() ? lower : null;
            case "ph" -> {
                String digits = value.replaceAll("\\D", "").replaceFirst("^0+", "");
                if (digits.length() == 10) {
                    // Same default as PhoneNumbers: a bare 10-digit number is Indian.
                    digits = "91" + digits;
                }
                yield digits.length() >= 11 && digits.length() <= 15 ? digits : null;
            }
            case "fn", "ln", "ct" -> {
                String folded = Normalizer.normalize(lower, Normalizer.Form.NFKC)
                        .replaceAll("[\\p{Punct}\\s]", "");
                yield folded.isEmpty() ? null : folded;
            }
            case "st", "country" -> {
                String letters = lower.replaceAll("[^a-z]", "");
                yield letters.isEmpty() ? null : letters;
            }
            case "zp" -> {
                String compact = lower.replaceAll("\\s", "");
                yield compact.isEmpty() ? null : compact;
            }
            default -> lower;
        };
    }

    /** Hash of one identifier; an already-hashed value is kept as-is. */
    public static String hash(String key, String raw) {
        if (raw != null && isSha256(raw.trim().toLowerCase(Locale.ROOT))) {
            return raw.trim().toLowerCase(Locale.ROOT);
        }
        String normalized = normalize(key, raw);
        return normalized == null ? null : sha256(normalized);
    }

    /** Hashes every supported key in {@code raw}; unknown keys are dropped. */
    public static Map<String, Object> hashAll(Map<String, ?> raw) {
        Map<String, Object> hashed = new LinkedHashMap<>();
        if (raw == null) {
            return hashed;
        }
        for (String key : HASHED_KEYS) {
            Object value = raw.get(key);
            if (value instanceof String s) {
                String h = hash(key, s);
                if (h != null) {
                    hashed.put(key, h);
                }
            }
        }
        return hashed;
    }
}
