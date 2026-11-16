package com.aivoice.lead.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** WhatsApp wording and language choice. The spoken call prompts live in the voice service. */
@Service
public class LanguageService {

    public enum Kind { CONSENT, OPT_OUT, CONSENTED, CONSENTED_NO_VOICE_FOLLOWUP, LANGUAGE, REMOVED, REPLIED }

    public static final List<String> SUPPORTED = List.of("en", "te");

    private static final Pattern TELUGU_SCRIPT = Pattern.compile("[\\u0C00-\\u0C7F]");

    private static final Set<String> TELUGU_REQUESTS = Set.of(
            "TELUGU", "IN TELUGU", "SPEAK TELUGU", "TELUGU PLEASE", "TELUGU LO",
            "తెలుగు", "తెలుగులో", "తెలుగు లో");

    private static final Set<String> ENGLISH_REQUESTS = Set.of(
            "ENGLISH", "IN ENGLISH", "SPEAK ENGLISH", "ENGLISH PLEASE",
            "ఇంగ్లీష్", "ఇంగ్లీషులో", "ఇంగ్లీషు");

    private static final Map<Kind, Map<String, String>> MESSAGES = Map.of(
            Kind.CONSENT, Map.of(
                    "en", "Would you like our AI sales assistant to call your mobile number to discuss the offer? "
                            + "This is an AI-assisted marketing call. Reply CALL to give permission; we will not call "
                            + "unless you reply CALL. Reply STOP if you do not want a call. "
                            + "Reply TELUGU if you prefer Telugu.",
                    "te", "మా AI సేల్స్ అసిస్టెంట్ ఈ ఆఫర్ గురించి మీ మొబైల్‌కు కాల్ చేయాలా? ఇది AI సహాయంతో చేసే "
                            + "మార్కెటింగ్ కాల్. అంగీకరిస్తే CALL అని రిప్లై ఇవ్వండి; మీ CALL సమాధానం లేకుండా మేము "
                            + "కాల్ చేయము. కాల్ వద్దనుకుంటే STOP అని రిప్లై ఇవ్వండి. "
                            + "ఇంగ్లీషులో మాట్లాడాలంటే ENGLISH అని రిప్లై ఇవ్వండి."),
            Kind.OPT_OUT, Map.of(
                    "en", "You will not receive a marketing call from us. Thank you.",
                    "te", "మీరు మా నుండి మార్కెటింగ్ కాల్ అందుకోరు. ధన్యవాదాలు."),
            Kind.CONSENTED, Map.of(
                    "en", "Thank you. We have recorded your request. We will attempt to call you shortly, "
                            + "subject to calling eligibility.",
                    "te", "ధన్యవాదాలు. మీ అభ్యర్థన నమోదు అయింది. అర్హత ఉంటే త్వరలో మీకు కాల్ చేస్తాం."),
            Kind.CONSENTED_NO_VOICE_FOLLOWUP, Map.of(
                    "en", "Thank you. Your permission is recorded, but a phone call was not queued for this request. "
                            + "We will continue through the configured messaging channels.",
                    "te", "ధన్యవాదాలు. మీ అనుమతి నమోదు అయింది, కానీ ఈ అభ్యర్థనకు ఫోన్ కాల్ వరుసలో పెట్టలేదు. "
                            + "అందుబాటులో ఉన్న సందేశ ఛానళ్ల ద్వారా కొనసాగిస్తాం."),
            Kind.LANGUAGE, Map.of(
                    "en", "Sure. We will continue in English.",
                    "te", "సరే. ఇకపై తెలుగులో కొనసాగిస్తాం."),
            Kind.REMOVED, Map.of(
                    "en", "We have deleted your details and will not contact you again.",
                    "te", "మీ వివరాలు తొలగించాం. ఇకపై మేము సంప్రదించము."),
            Kind.REPLIED, Map.of(
                    "en", "Thanks, we received your message. We will continue on WhatsApp and will not place the daily calls.",
                    "te", "ధన్యవాదాలు, మీ సందేశం అందింది. ఇకపై WhatsAppలోనే కొనసాగిస్తాం. రోజువారీ కాల్స్ ఉండవు."));

    public static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return "en";
        }
        String lowered = code.trim().toLowerCase();
        return SUPPORTED.contains(lowered) ? lowered : "en";
    }

    public static boolean isSupported(String code) {
        return code != null && SUPPORTED.contains(code.trim().toLowerCase());
    }

    private static String compact(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** The language a message asks for, or empty when it does not choose one. */
    public Optional<String> detect(String text) {
        String compact = compact(text);
        String upper = compact.toUpperCase();
        if (ENGLISH_REQUESTS.contains(upper) || ENGLISH_REQUESTS.contains(compact)) {
            return Optional.of("en");
        }
        if (TELUGU_REQUESTS.contains(upper) || TELUGU_REQUESTS.contains(compact) || upper.contains("TELUGU")) {
            return Optional.of("te");
        }
        if (text != null && TELUGU_SCRIPT.matcher(text).find()) {
            return Optional.of("te");
        }
        return Optional.empty();
    }

    public boolean isLanguageRequest(String text) {
        String compact = compact(text);
        String upper = compact.toUpperCase();
        return ENGLISH_REQUESTS.contains(upper) || ENGLISH_REQUESTS.contains(compact)
                || TELUGU_REQUESTS.contains(upper) || TELUGU_REQUESTS.contains(compact);
    }

    public String message(Kind kind, String language) {
        return MESSAGES.get(kind).get(normalize(language));
    }

    public String adDetails(String language, String businessName, String headline, String body,
                            String businessDescription) {
        String title = hasText(headline) ? headline : businessName;
        String detail = hasText(body) ? body
                : hasText(businessDescription) ? businessDescription
                : "We will only talk about the offer in the ad you opened.";
        if ("te".equals(normalize(language))) {
            return "నమస్కారం, ఇది " + businessName + ". మీరు మా ప్రకటనలో మీ నంబర్ ఇచ్చారు.\n"
                    + "ప్రకటన: " + title + "\n"
                    + detail + "\n"
                    + "ఆసక్తి లేకపోతే NOT INTERESTED అని రిప్లై ఇవ్వండి. మేము మీ వివరాలు తొలగిస్తాం.\n"
                    + message(Kind.CONSENT, language);
        }
        return "Hello, this is " + businessName + ". You added your number on our ad.\n"
                + "Ad: " + title + "\n"
                + detail + "\n"
                + "Reply NOT INTERESTED and we will delete your details.\n"
                + message(Kind.CONSENT, language);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
