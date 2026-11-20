/** Phase 1 (measurement foundation) API shapes. Snake_case mirrors the Spring JSON. */

export type MetricSource = "meta_reported" | "campex_tracked" | "crm_verified" | "manual_financial" | "derived";

export interface Pixel {
  id: string;
  client_id: string;
  meta_pixel_id: string;
  name: string;
  allowed_domains: string[];
  enabled: boolean;
  enabled_events: string[];
  forward_custom_events: boolean;
  consent_mode: "required" | "not_required";
  test_event_code: string | null;
  capi_token_configured: boolean;
  capi_dispatch_enabled: boolean;
  created_at: string;
  updated_at: string;
}

export interface PixelInput {
  meta_pixel_id: string;
  name: string;
  allowed_domains: string[];
  enabled: boolean;
  enabled_events: string[];
  forward_custom_events: boolean;
  consent_mode: "required" | "not_required";
  test_event_code?: string;
  capi_access_token?: string;
  clear_capi_token?: boolean;
}

export interface Delivery {
  attempt_number: number;
  http_status: number | null;
  events_received: number | null;
  fbtrace_id: string | null;
  error_code: number | null;
  error_message: string | null;
  retryable: boolean;
  outcome: string;
  attempted_at: string;
}

export interface ConversionEvent {
  id: string;
  event_id: string;
  event_name: string;
  event_category: string;
  meta_event_name: string | null;
  lead_id: string | null;
  visitor_id: string | null;
  campaign_id: string | null;
  ad_id: string | null;
  event_source: string;
  event_time: string;
  received_at: string;
  event_source_url: string | null;
  identifiers_present: string[];
  fbc_present: boolean;
  fbp_present: boolean;
  consent_status: string;
  meta_delivery_status: string;
  delivery_attempts: number;
  last_error: string | null;
  duplicate_count: number;
  test: boolean;
  deliveries: Delivery[] | null;
}

export interface EventPage { events: ConversionEvent[]; total: number; limit: number; offset: number }
export interface NameCount { name: string; count: number }

export interface EventSummary {
  received: number;
  sent_to_meta: number;
  failed: number;
  pending: number;
  skipped_no_consent: number;
  not_forwarded: number;
  deduplicated: number;
  delivery_success_rate: number | null;
  by_event_name: NameCount[];
  by_source: NameCount[];
  by_delivery_status: NameCount[];
  by_day: { day: string; received: number; sent: number; failed: number }[];
  capi_dispatch_enabled: boolean;
}

export interface Touch {
  id: string;
  source: string;
  resolution_method: string;
  campaign_id: string | null;
  campaign_name: string | null;
  adset_id: string | null;
  ad_id: string | null;
  ad_name: string | null;
  utm_source: string | null;
  utm_campaign: string | null;
  fbclid_present: boolean;
  fbc_present: boolean;
  landing_page: string | null;
  touched_at: string;
}

export interface LeadAttribution {
  lead_id: string;
  attribution_source: string;
  touch_count: number;
  first_touch: Touch | null;
  last_touch: Touch | null;
  history: Touch[];
}

export interface AttributionSummary {
  total_leads: number;
  attributed_leads: number;
  unknown_leads: number;
  unknown_percentage: number;
  first_last_differ: number;
  by_source: NameCount[];
  by_method: NameCount[];
}

export interface CampaignRow {
  key: string;
  campaign_id: string | null;
  label: string;
  leads: number;
  qualified_leads: number;
  site_visits_scheduled: number;
  site_visits_completed: number;
  bookings: number;
  booking_value: number;
  confirmed_revenue: number;
  amount_collected: number;
  meta_spend: number | null;
  meta_impressions: number | null;
  cost_per_lead: number | null;
  cost_per_qualified_lead: number | null;
  cost_per_site_visit: number | null;
  cost_per_booking: number | null;
  roas: number | null;
  roi: number | null;
}

export interface CampaignReport {
  model: "first_touch" | "last_touch";
  group_by: "campaign" | "adset" | "ad";
  currency: string;
  meta_spend_available: boolean;
  meta_spend_note: string | null;
  rows: CampaignRow[];
}

export const PIPELINE_STATUSES = [
  "NEW", "CONTACTED", "QUALIFIED", "SITE_VISIT_SCHEDULED", "SITE_VISIT_COMPLETED", "NEGOTIATION", "WON", "LOST",
] as const;
export type PipelineStatus = (typeof PIPELINE_STATUSES)[number];

export interface LeadCard {
  id: string;
  name: string;
  phone_number: string;
  pipeline_status: PipelineStatus;
  review_status: string;
  source: string;
  attribution_source: string;
  campaign_label: string | null;
  assigned_agent_id: string | null;
  assigned_agent_name: string | null;
  project_id: string | null;
  qualification_score: number | null;
  next_follow_up_at: string | null;
  created_at: string;
}

export interface Pipeline { columns: { status: PipelineStatus; count: number; leads: LeadCard[] }[]; total: number }

export interface Agent { id: string; name: string; email: string | null; phone_number: string | null; active: boolean; open_leads: number }
export interface Project { id: string; name: string; location: string | null; min_price: number | null; max_price: number | null; active: boolean }

export interface ScoreFactor { factor: string; label: string; weight: number; points: number; matched: boolean; reason: string }

export interface Qualification {
  budget_min: number | null;
  budget_max: number | null;
  preferred_location: string | null;
  property_type: string | null;
  purchase_timeline: string | null;
  bedrooms: number | null;
  financing_preference: string | null;
  purpose: string | null;
  genuine_interest: boolean | null;
  qualification_score: number;
  score_breakdown: ScoreFactor[];
  qualification_reason: string | null;
}

export interface FollowUp { id: string; lead_id: string; agent_id: string | null; due_at: string; channel: string; note: string | null; status: string; overdue: boolean }
export interface Activity { id: string; activity_type: string; body: string | null; actor_email: string | null; occurred_at: string }

export interface CrmLeadDetail {
  id: string;
  client_id: string;
  name: string | null;
  phone_number: string;
  email: string | null;
  source: string;
  review_status: string;
  pipeline_status: PipelineStatus;
  allowed_next_statuses: PipelineStatus[];
  lost_reason: string | null;
  assigned_agent_id: string | null;
  assigned_agent_name: string | null;
  project_id: string | null;
  created_at: string;
  response_time_minutes: number | null;
  qualification: Qualification | null;
  follow_ups: FollowUp[];
  status_history: { from_status: string | null; to_status: string; reason: string | null; changed_by: string | null; changed_at: string }[];
  duplicates: { id: string; name: string | null; phone_number: string; pipeline_status: string }[];
  tracking_consent: string;
}

export interface AgentPerformance {
  agent_id: string;
  agent_name: string;
  assigned_leads: number;
  contacted_leads: number;
  qualified_leads: number;
  site_visits_scheduled: number;
  site_visits_completed: number;
  won_leads: number;
  bookings: number;
  confirmed_revenue: number;
  avg_first_response_minutes: number | null;
  overdue_follow_ups: number;
}

export type VisitStatus = "SCHEDULED" | "CONFIRMED" | "COMPLETED" | "CANCELLED" | "NO_SHOW" | "RESCHEDULED";

export interface SiteVisit {
  id: string;
  lead_id: string;
  lead_name: string | null;
  lead_phone: string | null;
  project_id: string | null;
  project_name: string | null;
  agent_id: string | null;
  agent_name: string | null;
  scheduled_at: string;
  duration_minutes: number;
  status: VisitStatus;
  customer_confirmed: boolean;
  completed_at: string | null;
  outcome: string | null;
  follow_up_notes: string | null;
  cancellation_reason: string | null;
  rescheduled_from_id: string | null;
}

export type SaleStatus = "NEW" | "BOOKING_PENDING" | "BOOKED" | "PAYMENT_PENDING" | "PARTIALLY_PAID" | "FULLY_PAID" | "CANCELLED" | "REFUNDED";

export interface SaleHistory { action: string; from_status: string | null; to_status: string | null; changes: Record<string, unknown> | null; reason: string | null; actor_email: string | null; changed_at: string }

export interface SaleRecord {
  id: string;
  lead_id: string | null;
  lead_name: string | null;
  campaign_id: string | null;
  adset_id: string | null;
  ad_id: string | null;
  project_id: string | null;
  project_name: string | null;
  agent_id: string | null;
  agent_name: string | null;
  channel: string;
  status: SaleStatus;
  booking_value: number;
  amount_collected: number;
  outstanding_amount: number;
  refunded_amount: number;
  confirmed_revenue: number;
  booking_date: string | null;
  sale_confirmed_at: string | null;
  cancellation_reason: string | null;
  note: string | null;
  legacy_record: boolean;
  history: SaleHistory[] | null;
}

export interface SaleListPage {
  sales: SaleRecord[];
  total: number;
  totals: { count: number; bookings: number; booking_value: number; amount_collected: number; outstanding: number; refunded: number; confirmed_revenue: number };
}

export interface Metric { key: string; label: string; value: number | null; unit: string; source: MetricSource; note: string | null }

export interface KpiReport {
  currency: string;
  meta_spend_available: boolean;
  meta_spend_note: string | null;
  ad_account_ids: string[];
  metrics: Metric[];
  funnel: { stage: string; count: number; source: MetricSource }[];
  tracked_events: { event_name: string; count: number }[];
}
