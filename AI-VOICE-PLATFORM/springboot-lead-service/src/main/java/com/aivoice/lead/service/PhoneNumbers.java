package com.aivoice.lead.service;

import com.aivoice.lead.service.Exceptions.InvalidPhoneException;
import java.util.List;

public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** Returns E.164 with a leading '+'. A bare 10-digit number is treated as Indian (+91). */
    public static String normalize(String raw) {
        String digits = onlyDigits(raw);
        if (digits.length() == 10) {
            digits = "91" + digits;
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = "91" + digits.substring(1);
        }
        if (digits.length() < 10) {
            throw new InvalidPhoneException("Enter a valid mobile number");
        }
        return "+" + digits;
    }

    /** The forms a number may be stored under: "+9198..." from the popup, "9198..." from WhatsApp. */
    public static List<String> keys(String raw) {
        try {
            String canonical = normalize(raw);
            return List.of(canonical, canonical.substring(1));
        } catch (InvalidPhoneException e) {
            String compact = onlyDigits(raw);
            return compact.isEmpty() ? List.of() : List.of(compact);
        }
    }

    /** Normalised when possible, otherwise the digits as received. */
    public static String forStorage(String raw) {
        try {
            return normalize(raw);
        } catch (InvalidPhoneException e) {
            return raw;
        }
    }

    /** WhatsApp's Cloud API wants the number without '+'. */
    public static String forWhatsApp(String e164) {
        return e164.startsWith("+") ? e164.substring(1) : e164;
    }

    private static String onlyDigits(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }
}
