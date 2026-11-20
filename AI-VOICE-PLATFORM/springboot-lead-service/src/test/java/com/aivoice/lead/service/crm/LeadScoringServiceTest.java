package com.aivoice.lead.service.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.dto.CrmDtos.ScoringRule;
import com.aivoice.lead.entity.ClientProject;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadQualification;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadScoringServiceTest {

    private static ClientProject project() {
        ClientProject p = new ClientProject();
        p.setLocation("Guntur, Andhra Pradesh");
        p.setMinPrice(new BigDecimal("5000000"));
        p.setMaxPrice(new BigDecimal("9000000"));
        return p;
    }

    private static Lead lead() {
        Lead l = new Lead();
        l.setPhoneNumber("+919876543210");
        return l;
    }

    @Test
    void perfectLeadScores100WithAReasonPerFactor() {
        LeadQualification q = new LeadQualification();
        q.setBudgetMin(new BigDecimal("6000000"));
        q.setBudgetMax(new BigDecimal("8000000"));
        q.setPreferredLocation("guntur");
        q.setPurchaseTimeline("IMMEDIATE");
        q.setFinancingPreference("SELF_FUNDED");
        q.setGenuineInterest(true);
        var result = LeadScoringService.score(lead(), q, project(), LeadScoringService.DEFAULT_RULES);
        assertEquals(100, result.score());
        assertEquals(6, result.factors().size());
        assertTrue(result.factors().stream().allMatch(f -> f.matched() && !f.reason().isBlank()));
    }

    @Test
    void missingDataScoresZeroAndSaysWhy() {
        Lead noContact = new Lead();
        noContact.setPhoneNumber("12");
        var result = LeadScoringService.score(noContact, new LeadQualification(), null, LeadScoringService.DEFAULT_RULES);
        assertEquals(0, result.score());
        assertTrue(result.factors().stream().noneMatch(f -> f.matched()));
        assertEquals("No project pricing to compare against", result.factors().get(0).reason());
    }

    @Test
    void partialCreditAndConfigurableWeights() {
        LeadQualification q = new LeadQualification();
        q.setBudgetMin(new BigDecimal("100"));
        q.setBudgetMax(new BigDecimal("200"));
        q.setPurchaseTimeline("WITHIN_3_MONTHS");
        var rules = List.of(new ScoringRule("timeline_immediate", 50), new ScoringRule("budget_matches_project", 50),
                new ScoringRule("genuine_interest", 0));
        var result = LeadScoringService.score(lead(), q, project(), rules);
        assertEquals(25, result.score());
        assertEquals(2, result.factors().size());
        assertFalse(result.factors().get(1).matched());
        assertEquals("Budget is outside the project price range", result.factors().get(1).reason());
    }
}
