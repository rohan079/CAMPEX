package com.aivoice.lead.service.performance;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Statistics for A/B experiments.
 * <ul>
 *   <li>Rate metrics (CTR = link clicks/impressions, conversion rate = leads/link clicks): two-sided two-proportion
 *       z-test with a pooled standard error.</li>
 *   <li>Cost metrics (CPC, cost per lead / qualified lead / site visit / booking): compares conversions per unit of
 *       spend. Under H0 both variants convert spend at the same rate, so conditional on the total n = x1 + x2,
 *       x1 ~ Binomial(n, s1/(s1+s2)); tested with the normal approximation (needs n ≥ 20).</li>
 * </ul>
 * A winner is declared only when both variants meet the minimum sample and p < 1 − confidence.
 */
public final class ExperimentStats {

    public static final Set<String> RATE_METRICS = Set.of("ctr", "conversion_rate");

    public record Arm(BigDecimal spend, long impressions, long linkClicks, long leads, long qualified, long visits, long bookings) {
    }

    public record Result(String status, String winner, Double pValue, Double zScore, Double controlValue, Double testValue,
                         Double relativeDifference, boolean sampleSufficient, String explanation) {
    }

    private ExperimentStats() {
    }

    /** status: winner | no_reliable_winner | inconclusive (completed without enough data) | insufficient_data (running). */
    public static Result evaluate(String metric, Arm control, Arm test, int minSample, double confidence, boolean completed) {
        long[] c = counts(metric, control);
        long[] t = counts(metric, test);
        double alpha = 1 - confidence;
        boolean rate = RATE_METRICS.contains(metric);
        Double cv = value(metric, control);
        Double tv = value(metric, test);
        Double rel = cv == null || tv == null || cv == 0 ? null : (tv - cv) / cv;
        boolean sample = rate
                ? c[1] >= minSample && t[1] >= minSample && c[0] + t[0] >= 20
                : c[0] >= minSample && t[0] >= minSample && c[0] + t[0] >= 20
                        && control.spend().signum() > 0 && test.spend().signum() > 0;
        if (!sample) {
            String need = rate ? "at least " + minSample + " " + denominator(metric) + " per variant and 20 total "
                    + numerator(metric) : "at least " + minSample + " " + numerator(metric) + " per variant (20 in total)";
            return new Result(completed ? "inconclusive" : "insufficient_data", null, null, null, cv, tv, rel, false,
                    "Not enough data to compare: needs " + need + ". Control has " + c[0] + "/" + c[1] + ", test has "
                            + t[0] + "/" + t[1] + " (" + numerator(metric) + "/" + denominator(metric) + ").");
        }
        double z;
        if (rate) {
            double p1 = (double) c[0] / c[1];
            double p2 = (double) t[0] / t[1];
            double pooled = (double) (c[0] + t[0]) / (c[1] + t[1]);
            double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / c[1] + 1.0 / t[1]));
            z = se == 0 ? 0 : (p2 - p1) / se;
        } else {
            double n = c[0] + t[0];
            double share = test.spend().doubleValue() / (control.spend().doubleValue() + test.spend().doubleValue());
            double se = Math.sqrt(n * share * (1 - share));
            // Positive z: the test variant converts spend into results more efficiently (lower cost).
            z = se == 0 ? 0 : (t[0] - n * share) / se;
        }
        double p = 2 * (1 - normalCdf(Math.abs(z)));
        if (p >= alpha) {
            return new Result("no_reliable_winner", null, p, z, cv, tv, rel, true, String.format(java.util.Locale.ROOT,
                    "The difference is not statistically reliable at %.0f%% confidence (p = %.3f).", confidence * 100, p));
        }
        String winner = z > 0 ? "test" : "control";
        return new Result("winner", winner, p, z, cv, tv, rel, true, String.format(java.util.Locale.ROOT,
                "The %s variant is better on %s at %.0f%% confidence (p = %.4f).", winner, metric.replace('_', ' '),
                confidence * 100, p));
    }

    /** {numerator, denominator} of the metric for one arm. For cost metrics the "denominator" is spend (reported only). */
    static long[] counts(String metric, Arm a) {
        return switch (metric) {
            case "ctr" -> new long[] {a.linkClicks(), a.impressions()};
            case "conversion_rate" -> new long[] {a.leads(), a.linkClicks()};
            case "cpc" -> new long[] {a.linkClicks(), a.spend().longValue()};
            case "cost_per_lead" -> new long[] {a.leads(), a.spend().longValue()};
            case "cost_per_qualified_lead" -> new long[] {a.qualified(), a.spend().longValue()};
            case "cost_per_site_visit" -> new long[] {a.visits(), a.spend().longValue()};
            case "cost_per_booking" -> new long[] {a.bookings(), a.spend().longValue()};
            default -> throw new IllegalArgumentException("Unknown metric " + metric);
        };
    }

    static Double value(String metric, Arm a) {
        long[] c = counts(metric, a);
        if (RATE_METRICS.contains(metric)) return c[1] == 0 ? null : (double) c[0] / c[1];
        return c[0] == 0 ? null : a.spend().doubleValue() / c[0];
    }

    static String numerator(String metric) {
        return switch (metric) {
            case "ctr", "cpc" -> "link clicks";
            case "conversion_rate", "cost_per_lead" -> "leads";
            case "cost_per_qualified_lead" -> "qualified leads";
            case "cost_per_site_visit" -> "site visits";
            default -> "bookings";
        };
    }

    static String denominator(String metric) {
        return switch (metric) {
            case "ctr" -> "impressions";
            case "conversion_rate" -> "link clicks";
            default -> "spend";
        };
    }

    /** Standard normal CDF via the Abramowitz–Stegun 7.1.26 erf approximation (|error| < 1.5e-7). */
    public static double normalCdf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x) / Math.sqrt(2));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
                * Math.exp(-x * x / 2);
        return x >= 0 ? 0.5 * (1 + y) : 0.5 * (1 - y);
    }
}
