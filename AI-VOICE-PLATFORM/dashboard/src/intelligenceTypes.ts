/** Phase 2 performance intelligence API shapes (snake_case mirrors the Spring JSON). */

export interface DataNote { code: string; severity: "info" | "warning"; message: string }

export interface PerfRow {
  level: string; id: string; name: string; campaign_id: string | null; campaign_name: string | null; adset_id: string | null;
  objective: string | null; status: string | null; spend: number; impressions: number; reach: number | null;
  frequency: number | null; link_clicks: number; ctr: number | null; cpc: number | null; cpm: number | null;
  meta_leads: number; meta_cost_per_lead: number | null; conversion_rate: number | null; crm_leads: number;
  qualified_leads: number; qualified_rate: number | null; cost_per_qualified_lead: number | null; site_visits: number;
  cost_per_site_visit: number | null; bookings: number; cost_per_booking: number | null; booking_value: number;
  confirmed_revenue: number; roas: number | null; flags: string[];
}

export interface PerformanceReport {
  client_id: string; level: string; from: string; to: string; currency: string; attribution_windows: string;
  meta_available: boolean; data_notes: DataNote[]; metric_sources: Record<string, string>; totals: PerfRow;
  unattributed_crm: PerfRow; rows: PerfRow[];
}

export interface Overview {
  current: PerformanceReport; previous_totals: PerfRow; previous_from: string; previous_to: string;
  pct_change: Record<string, number | null>;
}

export interface TrendPoint {
  day: string; spend: number; impressions: number; link_clicks: number; ctr: number | null; cpc: number | null;
  cpm: number | null; meta_leads: number; crm_leads: number; qualified_leads: number; provisional: boolean;
}
export interface Trends { meta_available: boolean; data_notes: DataNote[]; points: TrendPoint[] }

export interface Evidence { metric: string; label: string; value: number | null; unit: string; source: string; scope: string }

export interface Recommendation {
  id: string; category: string; recommendation_type: string; campaign_id: string | null; ad_id: string | null;
  object_name: string | null; title: string; current_performance: string; reasoning: string; expected_impact: string;
  impact_is_estimate: boolean; confidence: "low" | "medium" | "high"; risk_level: "low" | "medium" | "high";
  recommended_action: string; proposed_change: Record<string, unknown> | null; ai_explanation: string | null;
  ai_provider: string | null; status: "pending" | "accepted" | "rejected" | "reviewed" | "expired";
  analysis_from: string; analysis_to: string; created_at: string; expires_at: string; decided_by: string | null;
  decision_note: string | null; evidence: Evidence[];
}

export interface GenerateResult {
  findings: number; created: number; skipped_duplicates: number; ai_explained: boolean; data_notes: DataNote[];
  recommendations: Recommendation[];
}

export interface BudgetLine {
  campaign_id: string; name: string; objective: string | null; status: string | null; budget_level: string;
  daily_budget: number | null; lifetime_budget: number | null; spend: number; utilization: number | null;
  qualified_leads: number; cost_per_qualified_lead: number | null; bookings: number; cost_per_booking: number | null;
  roas: number | null; revenue_share: number | null; peer_cost_per_qualified_lead: number | null; decision: string;
  proposed_daily_budget: number | null; reason: string;
}

export interface BudgetPolicy {
  daily_spend_limit: number | null; monthly_spend_limit: number | null; max_change_pct: number; min_qualified_leads: number;
  target_cost_per_qualified_lead: number | null; target_roas: number | null; recommendation_ttl_hours: number; configured: boolean;
}

export interface BudgetAnalysis {
  currency: string; meta_available: boolean; policy: BudgetPolicy; total_daily_budget: number;
  projected_daily_budget: number; campaigns: BudgetLine[]; notes: string[]; data_notes: DataNote[];
}

export interface Fatigue { status: string; reason: string; ctr_recent: number | null; ctr_previous: number | null; frequency_recent: number | null }
export interface CreativeRow {
  ad_id: string; ad_name: string; campaign_id: string | null; status: string | null; headline: string | null;
  primary_text: string | null; cta: string | null; image_url: string | null; format: string | null; metrics: PerfRow;
  fatigue: Fatigue; rank: "best" | "worst" | null;
}
export interface GroupRow { dimension: string; value: string; ads: number; metrics: PerfRow }
export interface CreativeDraft {
  id: string; ad_id: string; observation: string; suggestions: {
    headlines?: string[]; primary_texts?: string[]; cta?: string; audience_messaging?: string; concepts?: string[];
    image_prompts?: string[]; unverified_numbers?: string[];
  }; status: string; ai_provider: string | null; created_at: string;
}
export interface CreativeReport {
  currency: string; meta_available: boolean; ranking_metric: string; creatives: CreativeRow[]; by_headline: GroupRow[];
  by_cta: GroupRow[]; by_image: GroupRow[]; drafts: CreativeDraft[]; data_notes: DataNote[];
}

export interface SegmentRow {
  segment: Record<string, string>; label: string; spend: number; impressions: number; link_clicks: number; ctr: number | null;
  cpc: number | null; cpm: number | null; meta_leads: number; meta_cost_per_lead: number | null; spend_share: number | null;
  lead_share: number | null; flags: string[];
}
export interface CrmSegment {
  dimension: string; segment: string | null; leads: number; qualified_leads: number; site_visits: number; bookings: number;
  qualified_rate: number | null; site_visit_rate: number | null; booking_rate: number | null;
}
export interface AudienceReport {
  breakdown: string; currency: string; meta_available: boolean; segments: SegmentRow[]; crm_segments: CrmSegment[];
  location_fit: Record<string, Record<string, number>>; insights: { type: string; message: string; evidence: string[] }[];
  data_notes: DataNote[];
}

export interface Anomaly {
  id: string; campaign_id: string | null; campaign_name: string | null; metric: string; severity: "INFO" | "WARNING" | "CRITICAL";
  previous_value: number | null; current_value: number | null; pct_change: number | null; z_score: number | null;
  detection_reason: string; suggested_action: string; ai_explanation: string | null;
  status: "open" | "acknowledged" | "resolved" | "dismissed"; occurrences: number; last_detected_at: string;
}

export interface ExperimentVariant {
  id: string; role: "control" | "test"; name: string; ad_id: string | null; adset_id: string | null;
  metrics: Record<string, number> | null;
}
export interface Experiment {
  id: string; name: string; hypothesis: string; variable: string; primary_metric: string; min_sample_size: number;
  confidence_level: number; status: "draft" | "running" | "completed" | "cancelled"; start_date: string | null;
  end_date: string | null; outcome: "winner" | "no_reliable_winner" | "inconclusive" | null; winner_variant_id: string | null;
  result: Record<string, unknown> | null; result_summary: string | null; variants: ExperimentVariant[]; data_notes: DataNote[];
}

export interface AskResult {
  answer: string; grounded: boolean; unsupported_numbers: string[]; data_gaps: string[]; provider: string; model: string;
  from: string; to: string; comparison_from: string; comparison_to: string;
}
