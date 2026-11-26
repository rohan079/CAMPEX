import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AnomalyPanel } from "../../src/AnomalyPanel";
import { BudgetPanel } from "../../src/BudgetPanel";
import { CreativePanel } from "../../src/CreativePanel";
import { ExperimentPanel } from "../../src/ExperimentPanel";
import * as api from "../../src/intelligenceApi";
import type { PerfRow, Recommendation } from "../../src/intelligenceTypes";
import { PerformanceOverview } from "../../src/PerformanceIntelligencePage";
import { RecommendationsPanel } from "../../src/RecommendationsPanel";

vi.mock("../../src/intelligenceApi", async () => ({
  completeDays: (await vi.importActual<typeof import("../../src/measurementFormat")>("../../src/measurementFormat")).completeDays,
  getOverview: vi.fn(), getCampaignOptions: vi.fn(async () => ({ campaigns: [], note: null })), getPerformance: vi.fn(), getTrends: vi.fn(), refreshPerformance: vi.fn(), askPerformance: vi.fn(),
  listRecommendations: vi.fn(), generateRecommendations: vi.fn(), updateRecommendation: vi.fn(), getBudgetAnalysis: vi.fn(),
  saveBudgetPolicy: vi.fn(), getCreativePerformance: vi.fn(), analyzeCreative: vi.fn(), getAudience: vi.fn(), getPlacements: vi.fn(),
  listAnomalies: vi.fn(), detectAnomalies: vi.fn(), updateAnomaly: vi.fn(), explainAnomaly: vi.fn(), listExperiments: vi.fn(),
  getExperiment: vi.fn(), createExperiment: vi.fn(), startExperiment: vi.fn(), completeExperiment: vi.fn(),
}));
vi.mock("../../src/experimentExecutionApi", () => ({ getExperimentExecution: vi.fn(), launchExperiment: vi.fn(), followUpExperiment: vi.fn(), linkVariantCreative: vi.fn() }));
vi.mock("../../src/creativeApi", () => ({ listCreatives: vi.fn(async () => []) }));
vi.mock("../../src/actionApi", () => ({ previewAction: vi.fn(async () => ({ id: "act1" })) }));
const m = vi.mocked(api);

function row(over: Partial<PerfRow> = {}): PerfRow {
  return {
    level: "campaign", id: "1001", name: "Villas", campaign_id: "1001", campaign_name: "Villas", adset_id: null, objective: "OUTCOME_LEADS",
    status: "ACTIVE", spend: 9000, impressions: 200000, reach: null, frequency: null, link_clicks: 3000, ctr: 0.015, cpc: 3,
    cpm: 45, meta_leads: 60, meta_cost_per_lead: 150, conversion_rate: 0.02, crm_leads: 10, qualified_leads: 6, qualified_rate: 0.6,
    cost_per_qualified_lead: 1500, site_visits: 2, cost_per_site_visit: 4500, bookings: 1, cost_per_booking: 9000, booking_value: 5000000,
    confirmed_revenue: 200000, roas: 22.2222, flags: [], ...over,
  };
}

const rec: Recommendation = {
  id: "r1", category: "budget", recommendation_type: "increase_budget", campaign_id: "1001", ad_id: null, object_name: "Villas",
  title: "Consider increasing budget for Villas", current_performance: "Spend INR 9000, 6 qualified leads", reasoning: "Below benchmark",
  expected_impact: "Estimate: about 2.8 more qualified leads per week", impact_is_estimate: true, confidence: "medium", risk_level: "medium",
  recommended_action: "Raise the daily budget in Ads Manager", proposed_change: { from: 350, to: 385 }, ai_explanation: null, ai_provider: null,
  status: "pending", analysis_from: "2026-10-23", analysis_to: "2026-11-19", created_at: "2026-11-20T06:00:00Z", expires_at: "2026-11-23T06:00:00Z",
  decided_by: null, decision_note: null,
  evidence: [{ metric: "cost_per_qualified_lead", label: "Cost per qualified lead", value: 1500, unit: "INR", source: "derived", scope: "campaign" },
    { metric: "qualified_leads", label: "Qualified leads", value: 6, unit: "count", source: "crm_verified", scope: "campaign" }],
};

afterEach(cleanup);
beforeEach(() => vi.clearAllMocks());

describe("Performance overview", () => {
  beforeEach(() => {
    m.getOverview.mockResolvedValue({
      current: { client_id: "c1", level: "campaign", from: "2026-10-23", to: "2026-11-19", currency: "INR", attribution_windows: "7d_click,1d_view",
        meta_available: true, data_notes: [{ code: "provisional", severity: "info", message: "Recent days may change." }],
        metric_sources: {}, totals: row({ id: "total", name: "All", spend: 18500, qualified_leads: 7, reach: 30000, roas: 1.25 }),
        unattributed_crm: row(), rows: [row()] },
      previous_totals: row(), previous_from: "2026-09-25", previous_to: "2026-10-22", pct_change: { spend: 0.25, qualified_leads: null },
    });
    m.getTrends.mockResolvedValue({ meta_available: true, data_notes: [], points: [] });
    m.askPerformance.mockResolvedValue({ answer: "Villas leads with 6 qualified leads.", grounded: false, unsupported_numbers: ["57"],
      data_gaps: ["Recent days may change."], provider: "anthropic", model: "claude-opus-5-5", from: "2026-10-23", to: "2026-11-19",
      comparison_from: "2026-09-25", comparison_to: "2026-10-22" });
  });

  it("shows KPIs with provenance, comparisons and data notes", async () => {
    render(<PerformanceOverview clientId="c1" from="2026-10-23" to="2026-11-19" />);
    expect(await screen.findByText("Recent days may change.")).toBeTruthy();
    const kpis = screen.getByLabelText("Performance overview");
    expect(within(kpis).getByText("ROAS").closest("article")?.textContent).toContain("1.25×");
    expect(within(kpis).getByText("Spend").closest("article")?.textContent).toContain("+25% vs previous period");
    expect(within(kpis).getByText("Qualified leads").closest("article")?.textContent).toContain("CRM-verified");
    expect(within(kpis).getByText("Qualified leads").closest("article")?.textContent).toContain("no comparison");
  });

  it("lists campaigns without delivery data and keeps the selection", async () => {
    m.getCampaignOptions.mockResolvedValue({ campaigns: [
      { id: "120200000001", name: "Paused launch", status: "PAUSED", ad_account_id: "act_1", has_data: false }], note: null });
    render(<PerformanceOverview clientId="c1" from="2026-10-23" to="2026-11-19" />);
    const select = await screen.findByLabelText("Campaign filter") as HTMLSelectElement;
    const option = await screen.findByRole("option", { name: "Paused launch · 120200000001 · paused" });
    m.getOverview.mockResolvedValue({ ...(await m.getOverview.mock.results[0].value), current: { ...(await m.getOverview.mock.results[0].value).current, rows: [] } });
    fireEvent.change(select, { target: { value: "120200000001" } });
    await waitFor(() => expect(m.getOverview).toHaveBeenLastCalledWith("c1", { from: "2026-10-23", to: "2026-11-19" }, "120200000001"));
    expect(select.value).toBe("120200000001");
    expect((option as HTMLOptionElement).selected).toBe(true);
    expect(await screen.findByText(/no delivery data for this campaign/)).toBeTruthy();
  });

  it("filters by campaign and warns about ungrounded AI numbers", async () => {
    render(<PerformanceOverview clientId="c1" from="2026-10-23" to="2026-11-19" />);
    fireEvent.change(await screen.findByLabelText("Campaign filter"), { target: { value: "1001" } });
    await waitFor(() => expect(m.getOverview).toHaveBeenLastCalledWith("c1", { from: "2026-10-23", to: "2026-11-19" }, "1001"));
    fireEvent.click(screen.getByRole("button", { name: "Which campaign is performing best?" }));
    expect(await screen.findByText(/not in Campex's data \(57\)/)).toBeTruthy();
    expect(m.askPerformance).toHaveBeenCalledWith("c1", "Which campaign is performing best?", { from: "2026-10-23", to: "2026-11-19" }, "previous_period");
  });
});

describe("Recommendation cards", () => {
  it("show estimates, evidence with sources, and record decisions without changing Meta", async () => {
    m.listRecommendations.mockResolvedValue([rec]);
    m.updateRecommendation.mockResolvedValue({ recommendation: { ...rec, status: "accepted" }, message: "Recorded as accepted. Nothing was changed in Meta." });
    render(<RecommendationsPanel clientId="c1" from="2026-10-23" to="2026-11-19" />);
    const card = await screen.findByRole("article", { name: rec.title });
    expect(within(card).getByText(/Expected impact \(estimate\)/)).toBeTruthy();
    expect(within(card).getByText("Confidence: medium")).toBeTruthy();
    expect(within(card).getByText(/Nothing changes automatically/)).toBeTruthy();
    fireEvent.click(within(card).getByRole("button", { name: "View evidence" }));
    expect(within(card).getByText("CRM-verified")).toBeTruthy();
    expect(within(card).getByText("INR 1,500")).toBeTruthy();
    fireEvent.click(within(card).getByRole("button", { name: "Accept" }));
    await waitFor(() => expect(m.updateRecommendation).toHaveBeenCalledWith("r1", "accepted"));
    expect(await screen.findByText(/Nothing was changed in Meta/)).toBeTruthy();
  });

  it("prepares a reviewed Meta change from a budget recommendation", async () => {
    const actions = await import("../../src/actionApi");
    m.listRecommendations.mockResolvedValue([rec]);
    render(<RecommendationsPanel clientId="c1" from="2026-10-23" to="2026-11-19" />);
    const card = await screen.findByRole("article", { name: rec.title });
    fireEvent.click(within(card).getByRole("button", { name: "Prepare change in Meta" }));
    await waitFor(() => expect(actions.previewAction).toHaveBeenCalledWith("c1", { recommendation_id: "r1" }));
    expect(await screen.findByText(/Approve and apply it in Optimization actions/)).toBeTruthy();
  });

  it("generates recommendations for the selected period", async () => {
    m.listRecommendations.mockResolvedValue([]);
    m.generateRecommendations.mockResolvedValue({ findings: 3, created: 2, skipped_duplicates: 1, ai_explained: false,
      data_notes: [{ code: "meta_unavailable", severity: "warning", message: "No Meta connection." }], recommendations: [] });
    render(<RecommendationsPanel clientId="c1" from="2026-10-23" to="2026-11-19" />);
    fireEvent.click(await screen.findByRole("button", { name: "Generate recommendations" }));
    expect(await screen.findByText(/2 new recommendation\(s\) from 3 finding\(s\); 1 already open/)).toBeTruthy();
    expect(screen.getByText("No Meta connection.")).toBeTruthy();
  });
});

describe("Budget analysis", () => {
  it("shows decisions, proposed budgets and saves guardrails", async () => {
    m.getBudgetAnalysis.mockResolvedValue({ currency: "INR", meta_available: true,
      policy: { daily_spend_limit: 735, monthly_spend_limit: null, max_change_pct: 20, min_qualified_leads: 5, target_cost_per_qualified_lead: 2000,
        target_roas: null, recommendation_ttl_hours: 72, configured: true },
      total_daily_budget: 700, projected_daily_budget: 735,
      campaigns: [{ campaign_id: "1001", name: "Villas", objective: "OUTCOME_LEADS", status: "ACTIVE", budget_level: "campaign", daily_budget: 350,
        lifetime_budget: null, spend: 9000, utilization: 0.92, qualified_leads: 6, cost_per_qualified_lead: 1500, bookings: 1, cost_per_booking: 9000,
        roas: 1.2, revenue_share: 1, peer_cost_per_qualified_lead: 2000, decision: "increase", proposed_daily_budget: 385,
        reason: "Capped by the client's spending limit." }],
      notes: ["Budget utilization is an estimate."], data_notes: [] });
    m.saveBudgetPolicy.mockResolvedValue({} as never);
    render(<BudgetPanel clientId="c1" from="2026-10-23" to="2026-11-19" />);
    expect(await screen.findByText("Increase")).toBeTruthy();
    expect(screen.getByText(/Capped by the client's spending limit/)).toBeTruthy();
    expect(screen.getByText("92%")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Max budget change (%)"), { target: { value: "10" } });
    fireEvent.submit(screen.getByRole("form", { name: "Budget guardrails" }));
    await waitFor(() => expect(m.saveBudgetPolicy).toHaveBeenCalledWith("c1", expect.objectContaining({ max_change_pct: 10, daily_spend_limit: 735 })));
  });
});

describe("Creative comparison", () => {
  it("marks best/worst and fatigue and saves generated variants as drafts", async () => {
    const creative = (id: string, rank: "best" | "worst" | null, fatigue = "ok") => ({ ad_id: id, ad_name: `Ad ${id}`, campaign_id: "1001", status: "ACTIVE",
      headline: `Headline ${id}`, primary_text: "Text", cta: "LEARN_MORE", image_url: null, format: "photo", metrics: row({ id }), rank,
      fatigue: { status: fatigue, reason: fatigue === "fatigued" ? "Link CTR fell from 2% to 1%" : "No fatigue signal", ctr_recent: null, ctr_previous: null, frequency_recent: null } });
    m.getCreativePerformance.mockResolvedValue({ currency: "INR", meta_available: true, ranking_metric: "cost_per_qualified_lead",
      creatives: [creative("3001", "best"), creative("3002", "worst", "fatigued")], by_headline: [], by_cta: [], by_image: [], drafts: [], data_notes: [] });
    m.analyzeCreative.mockResolvedValue({ id: "d1", ad_id: "3002", observation: "Clicks without qualified leads.", status: "draft",
      ai_provider: "anthropic", created_at: "", suggestions: { headlines: ["H1", "H2", "H3"], primary_texts: ["P1", "P2", "P3"], cta: "LEARN_MORE",
        audience_messaging: "Families", concepts: ["C1"], image_prompts: ["Sunlit villa"], unverified_numbers: ["45 lakh"] } });
    render(<CreativePanel clientId="c1" from="2026-10-23" to="2026-11-19" />);
    expect(await screen.findByText("Best performer")).toBeTruthy();
    expect(screen.getByText("Lowest performer")).toBeTruthy();
    expect(screen.getByText("Fatigue")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Generate draft variants" })[1]);
    const draft = await screen.findByRole("region", { name: "Creative draft" });
    expect(within(draft).getByText("DRAFT · NOT PUBLISHED")).toBeTruthy();
    expect(within(draft).getByText(/45 lakh/)).toBeTruthy();
    expect(m.analyzeCreative).toHaveBeenCalledWith("c1", "3002", { from: "2026-10-23", to: "2026-11-19" });
  });
});

describe("Anomaly alerts", () => {
  it("show severity and change, and resolve with cooldown", async () => {
    m.listAnomalies.mockResolvedValue([{ id: "a1", campaign_id: "1001", campaign_name: "Villas", metric: "ctr", severity: "CRITICAL",
      previous_value: 0.0105, current_value: 0.002, pct_change: -0.81, z_score: -5.1, detection_reason: "Ctr fell 81%",
      suggested_action: "Check creative fatigue", ai_explanation: null, status: "open", occurrences: 2, last_detected_at: "2026-11-20T04:15:00Z" }]);
    m.updateAnomaly.mockResolvedValue({} as never);
    render(<AnomalyPanel clientId="c1" />);
    const card = await screen.findByRole("article", { name: "CRITICAL ctr" });
    expect(within(card).getByText(/1\.05% → 0\.20%/)).toBeTruthy();
    expect(within(card).getByText("(-81%)")).toBeTruthy();
    expect(within(card).getByText("Seen 2×")).toBeTruthy();
    fireEvent.click(within(card).getByRole("button", { name: "Resolve" }));
    await waitFor(() => expect(m.updateAnomaly).toHaveBeenCalledWith("a1", "resolved"));
  });
});

describe("Experiment management", () => {
  it("creates an experiment and shows a statistically reported result", async () => {
    const experiment = { id: "e1", name: "Headline test", hypothesis: "Price helps", variable: "headline", primary_metric: "ctr",
      min_sample_size: 1000, confidence_level: 0.95, status: "completed" as const, start_date: "2026-11-01", end_date: "2026-11-19",
      outcome: "no_reliable_winner" as const, winner_variant_id: null,
      result: { status: "no_reliable_winner", winner: null, p_value: 0.4123, control_value: 0.02, test_value: 0.0205,
        explanation: "The difference is not statistically reliable at 95% confidence (p = 0.412)." },
      result_summary: "Keep the control.", data_notes: [],
      variants: [{ id: "v1", role: "control" as const, name: "A", ad_id: "3001", adset_id: null, metrics: { spend: 4500, impressions: 100000, link_clicks: 2000, leads: 30, qualified_leads: 5 } },
        { id: "v2", role: "test" as const, name: "B", ad_id: "3002", adset_id: null, metrics: { spend: 4500, impressions: 100000, link_clicks: 2050, leads: 31, qualified_leads: 5 } }] };
    m.listExperiments.mockResolvedValue([experiment]);
    m.getExperiment.mockResolvedValue(experiment);
    m.createExperiment.mockResolvedValue({ ...experiment, id: "e2", status: "draft", outcome: null, result: null });
    render(<ExperimentPanel clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Results" }));
    const result = await screen.findByRole("region", { name: "Experiment result" });
    expect(within(result).getByText("No reliable winner")).toBeTruthy();
    expect(within(result).getByText("0.4123")).toBeTruthy();
    const form = screen.getByRole("form", { name: "New experiment" });
    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "CTA test" } });
    fireEvent.change(within(form).getByLabelText("Hypothesis"), { target: { value: "Book now beats learn more" } });
    fireEvent.change(within(form).getByLabelText("Control ad id"), { target: { value: "3001" } });
    fireEvent.change(within(form).getByLabelText("Test ad id"), { target: { value: "3002" } });
    fireEvent.submit(form);
    await waitFor(() => expect(m.createExperiment).toHaveBeenCalledWith("c1", expect.objectContaining({
      name: "CTA test", primary_metric: "cost_per_qualified_lead", min_sample_size: 30,
      control: { name: "Control", ad_id: "3001" }, test: { name: "Test", ad_id: "3002" } })));
  });
});
