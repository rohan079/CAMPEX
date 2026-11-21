package com.aivoice.lead.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.aivoice.lead.dto.CampaignExecutionDtos.CampaignConfig;
import com.aivoice.lead.dto.CampaignExecutionDtos.ValidationIssue;
import com.aivoice.lead.dto.CampaignExecutionDtos.ValidationReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignValidatorTest {

    private static final CampaignValidator.Context CTX = new CampaignValidator.Context(Set.of("act_123456"), null);

    static CampaignConfig valid() {
        return new CampaignConfig("act_123456", "Green Villas launch", "leads", List.of("facebook", "instagram"),
                "Guntur", 25, 55, 50_000L, "Villas near Guntur", "Book a site visit this weekend.", null,
                "LEARN_MORE", "https://greenvillas.example.com/launch", null);
    }

    private static List<String> errorFields(ValidationReport report) {
        return report.errors().stream().map(ValidationIssue::field).toList();
    }

    @Test
    void acceptsACompleteConfigAndWarnsThatTheAdIsNotCreated() {
        ValidationReport report = CampaignValidator.validate(valid(), CTX);
        assertThat(report.valid()).isTrue();
        assertThat(report.warnings()).extracting(ValidationIssue::code).contains("not_created");
    }

    @Test
    void rejectsAccountsThatAreNotMappedToTheClient() {
        CampaignConfig c = valid();
        ValidationReport report = CampaignValidator.validate(new CampaignConfig("act_999999", c.name(), c.objective(),
                c.platforms(), c.location(), c.ageMin(), c.ageMax(), c.dailyBudgetMinor(), c.headline(),
                c.primaryText(), c.description(), c.cta(), c.destinationUrl(), c.pageId()), CTX);
        assertThat(report.errors()).extracting(ValidationIssue::code).contains("not_mapped");
    }

    @Test
    void enforcesTheClientDailySpendLimit() {
        ValidationReport report = CampaignValidator.validate(valid(),
                new CampaignValidator.Context(Set.of("act_123456"), new BigDecimal("400")));
        assertThat(report.errors()).extracting(ValidationIssue::code).containsExactly("over_policy");
        assertThat(CampaignValidator.validate(valid(),
                new CampaignValidator.Context(Set.of("act_123456"), new BigDecimal("500"))).valid()).isTrue();
    }

    @Test
    void rejectsBadShapes() {
        ValidationReport report = CampaignValidator.validate(new CampaignConfig("123", "", "boost", List.of("tiktok"),
                " ", 17, 70, 50L, "", "", null, "BUY_NOW_PLEASE", null, null), CTX);
        assertThat(errorFields(report)).contains("name", "objective", "ad_account_id", "platforms", "location", "age",
                "daily_budget_minor", "headline", "primary_text", "cta");
    }

    @Test
    void trafficNeedsASafePublicHttpsUrl() {
        CampaignConfig c = valid();
        for (String url : new String[]{null, "http://example.com", "https://localhost/x", "https://10.0.0.1/",
                "https://user:pw@example.com", "https://intranet/", "not a url"}) {
            ValidationReport report = CampaignValidator.validate(new CampaignConfig(c.adAccountId(), c.name(), "traffic",
                    c.platforms(), c.location(), c.ageMin(), c.ageMax(), c.dailyBudgetMinor(), c.headline(),
                    c.primaryText(), null, c.cta(), url, null), CTX);
            assertThat(errorFields(report)).as(String.valueOf(url)).contains("destination_url");
        }
        assertThat(CampaignValidator.urlProblem("https://greenvillas.example.com/a?b=c")).isNull();
    }

    @Test
    void longCopyIsAWarningNotAnError() {
        CampaignConfig c = valid();
        ValidationReport report = CampaignValidator.validate(new CampaignConfig(c.adAccountId(), c.name(), c.objective(),
                c.platforms(), c.location(), c.ageMin(), c.ageMax(), c.dailyBudgetMinor(),
                "A headline that is clearly longer than forty chars", "x".repeat(300), null, c.cta(),
                c.destinationUrl(), null), CTX);
        assertThat(report.valid()).isTrue();
        assertThat(report.warnings()).extracting(ValidationIssue::field).contains("headline", "primary_text");
    }
}
