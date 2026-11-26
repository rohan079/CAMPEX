/** Phase 3 campaign execution API shapes (snake_case, as served by Spring). */

export type ExecutionStatus = "DRAFT" | "READY_FOR_REVIEW" | "PENDING_APPROVAL" | "APPROVED" | "EXECUTING"
  | "PUBLISHED_PAUSED" | "ACTIVE" | "FAILED" | "CANCELLED";

export type ExecutionAction = "edit" | "validate" | "submit" | "approve" | "reject" | "publish" | "retry"
  | "reconcile" | "cancel" | "revise" | "ad_retry" | "ad_check" | "activate" | "pause";

/** A place from Meta's location search. */
export interface MetaLocation {
  key: string;
  type: "city" | "region" | "country";
  name: string;
  region?: string | null;
  country_name?: string | null;
  label: string;
}

export type AdStatus = "not_requested" | "pending" | "uploading" | "processing" | "creating" | "created" | "failed" | "skipped";

export interface CampaignConfig {
  ad_account_id: string;
  name: string;
  objective: "leads" | "traffic" | "sales" | "awareness";
  platforms: string[];
  location: string;
  /** Meta's key for the picked place; publish targets it exactly. Absent when the location was typed. */
  location_key?: string | null;
  location_type?: "city" | "region" | "country" | null;
  age_min: number;
  age_max: number;
  daily_budget_minor: number;
  headline: string;
  primary_text: string;
  description?: string | null;
  cta: string;
  destination_url?: string | null;
  page_id?: string | null;
  creative_asset_id?: string | null;
  creative_version?: number | null;
  /** More ads in the same ad set (up to 5 besides the main creative), each with its own approved copy. */
  extra_creatives?: { asset_id: string; version: number }[] | null;
}

/** One ad Campex creates in the campaign's ad set; position 1 is the main creative. */
export interface ExecutionAd {
  position: number;
  creative_asset_id: string;
  creative_version: number;
  status: AdStatus;
  error: string | null;
  meta_ad_id: string | null;
}

export interface ValidationIssue { field: string; code: string; message: string }
export interface ValidationReport { valid: boolean; errors: ValidationIssue[]; warnings: ValidationIssue[] }

export interface ExecutionSummary {
  id: string;
  client_id: string;
  name: string;
  objective: string;
  ad_account_id: string;
  status: ExecutionStatus;
  meta_status: string | null;
  meta_effective_status: string | null;
  meta_campaign_id: string | null;
  publish_outcome: "created" | "partial" | "rejected" | "unknown" | "not_found" | null;
  last_error: string | null;
  config_version: number;
  created_by: string;
  created_at: string;
  updated_at: string;
  published_at: string | null;
}

export interface ApprovalRecord {
  id: string;
  config_version: number;
  decision: "approved" | "rejected";
  decided_by: string;
  decided_at: string;
  note: string | null;
  approved_daily_budget_minor: number | null;
  valid: boolean;
}

export interface ExecutionStep {
  step: string;
  outcome: "ok" | "failed" | "unknown" | "blocked";
  detail: string | null;
  meta_ids: Record<string, string | null> | null;
  actor: string;
  at: string;
}

export interface ExecutionDetail {
  execution: ExecutionSummary;
  config: CampaignConfig;
  config_hash: string;
  last_validation: ValidationReport | null;
  publish_tag: string;
  publish_attempts: number;
  meta_adset_id: string | null;
  meta_checked_at: string | null;
  versions: { version: number; config_hash: string; config: Record<string, unknown>; created_by: string; created_at: string }[];
  approvals: ApprovalRecord[];
  steps: ExecutionStep[];
  allowed_actions: ExecutionAction[];
  ad_status: AdStatus;
  ad_error: string | null;
  meta_ad_id: string | null;
  meta_video_id: string | null;
  meta_creative_id: string | null;
  ads?: ExecutionAd[];
}

export interface AppNotification {
  id: string;
  client_id: string | null;
  type: string;
  severity: "info" | "warning" | "critical";
  title: string;
  body: string | null;
  entity_type: string | null;
  entity_id: string | null;
  created_at: string;
  read_at: string | null;
}
