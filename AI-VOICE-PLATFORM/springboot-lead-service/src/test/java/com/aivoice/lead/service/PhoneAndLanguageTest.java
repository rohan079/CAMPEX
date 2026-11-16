package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.service.Exceptions.InvalidPhoneException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PhoneAndLanguageTest {

    private final LanguageService languages = new LanguageService();

    @Test
    void tenDigitNumbersBecomeIndian() {
        assertEquals("+919876543210", PhoneNumbers.normalize("98765 43210"));
        assertEquals("+919876543210", PhoneNumbers.normalize("09876543210"));
        assertEquals("+14155550123", PhoneNumbers.normalize("+1 (415) 555-0123"));
    }

    @Test
    void shortNumbersAreRejected() {
        assertThrows(InvalidPhoneException.class, () -> PhoneNumbers.normalize("12345"));
    }

    @Test
    void lookupKeysCoverPlusAndBareForms() {
        assertEquals(List.of("+919876543210", "919876543210"), PhoneNumbers.keys("9876543210"));
    }

    @Test
    void detectsTeluguByKeywordAndScript() {
        assertEquals(Optional.of("te"), languages.detect("telugu please"));
        assertEquals(Optional.of("te"), languages.detect("నాకు తెలుగు కావాలి"));
        assertEquals(Optional.of("en"), languages.detect("English"));
        assertEquals(Optional.empty(), languages.detect("hello there"));
    }

    @Test
    void adMessageRequestsExplicitCallPermissionInsteadOfPromisingUnconsentedCalls() {
        String english = languages.adDetails("en", "Acme", "Summer sale", "20% off", "");
        assertTrue(english.contains("Summer sale"));
        assertTrue(english.contains("Reply CALL to give permission"));
        assertTrue(english.contains("we will not call unless you reply CALL"));
        assertFalse(english.contains("we will call once a day"));
        String telugu = languages.adDetails("te", "Acme", "Summer sale", null, "Fallback");
        assertTrue(telugu.contains("CALL సమాధానం లేకుండా మేము కాల్ చేయము"));
        assertTrue(telugu.contains("Fallback"));
    }

    @Test
    void notInterestedWorksInBothLanguages() {
        assertTrue(ConsentService.isNotInterested("Not interested"));
        assertTrue(ConsentService.isNotInterested("stop"));
        assertTrue(ConsentService.isNotInterested("నాకు ఆసక్తి లేదు"));
        assertFalse(ConsentService.isNotInterested("Tell me more"));
        assertFalse(ConsentService.isNotInterested(null));
    }

    @Test
    void consentNeedsAnExplicitKeyword() {
        assertTrue(ConsentService.isConsent(" yes "));
        assertTrue(ConsentService.isConsent("call"));
        assertFalse(ConsentService.isConsent("maybe call me later"));
    }
}
