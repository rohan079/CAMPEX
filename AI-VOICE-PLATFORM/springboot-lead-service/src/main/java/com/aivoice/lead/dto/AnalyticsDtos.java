package com.aivoice.lead.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The numbers behind the dashboard's charts. Days with no activity are present with zeros. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Summary(
            long leads,
            long adLeads,
            long calls,
            long connectedCalls,
            long sales,
            BigDecimal revenue,
            long adSales,
            BigDecimal adRevenue,
            long agentSales,
            BigDecimal agentRevenue,
            long reach,
            double leadToSaleRate) {
    }

    public record LeadsDay(LocalDate day, long leads) {
    }

    public record SalesDay(LocalDate day, long sales, BigDecimal revenue) {
    }

    public record CallsDay(LocalDate day, long calls, long connected) {
    }

    public record AdRow(
            String adId,
            String adName,
            long leads,
            long sales,
            BigDecimal revenue,
            long agentSales,
            BigDecimal agentRevenue,
            long reach,
            double leadRate) {
    }

    public record AdSales(long sales, BigDecimal revenue, List<SalesDay> byDay, List<AdRow> byAd) {
    }

    public record CallingAgent(
            long calls,
            long connected,
            long sales,
            BigDecimal revenue,
            double connectedRate,
            double saleRate,
            List<CallsDay> callsByDay,
            List<SalesDay> salesByDay) {
    }

    public record LocationReach(String location, long reach) {
    }

    public record AdLocationReach(String adId, String adName, String location, long reach) {
    }

    public record AdReachRow(String adId, String adName, long reach, long leads, double leadRate,
                             List<LocationReach> topLocations) {
    }

    public record Reach(long totalReach, List<LocationReach> byLocation, List<AdReachRow> byAd,
                        List<AdLocationReach> byAdLocation) {
    }

    public record ClientAnalytics(
            String clientId,
            String clientName,
            String currency,
            LocalDate from,
            LocalDate to,
            int days,
            Summary summary,
            List<LeadsDay> leadsByDay,
            List<SalesDay> salesByDay,
            AdSales adSales,
            CallingAgent callingAgent,
            Reach reach) {
    }

    public record ClientComparisonRow(
            String clientId,
            String clientName,
            long leads,
            long calls,
            long sales,
            BigDecimal revenue,
            BigDecimal adRevenue,
            BigDecimal agentRevenue,
            long reach) {
    }

    public record Comparison(String currency, LocalDate from, LocalDate to, int days,
                             List<ClientComparisonRow> clients) {
    }
}
