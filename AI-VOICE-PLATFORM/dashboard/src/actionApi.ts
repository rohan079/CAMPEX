import { request } from "./api";

/** Phase 3 optimization actions: preview → approve → execute → verify. */

export type ActionStatus = "PROPOSED" | "APPROVED" | "EXECUTING" | "SUCCEEDED" | "FAILED" | "REJECTED" | "EXPIRED" | "CANCELLED";

export interface CampaignAction {
  id: string;
  client_id: string;
  recommendation_id: string | null;
  source: "manual" | "recommendation" | "automation";
  action_type: "pause" | "resume" | "set_daily_budget";
  level: "campaign" | "adset" | "ad";
  object_id: string;
  object_name: string | null;
  ad_account_id: string;
  status: ActionStatus;
  before_state: Record<string, unknown>;
  proposed_after: Record<string, unknown>;
  actual_after: Record<string, unknown> | null;
  verification: "verified" | "mismatch" | "unverified" | null;
  guardrails: { code: string; severity: "error" | "warning"; message: string }[];
  reason: string | null;
  attempts: number;
  last_error: string | null;
  created_by: string;
  approved_by: string | null;
  approved_at: string | null;
  approval_note: string | null;
  executed_by: string | null;
  executed_at: string | null;
  verified_at: string | null;
  expires_at: string;
  created_at: string;
  events: { event: string; outcome: string; detail: string | null; actor: string; at: string }[];
  allowed_actions: ("approve" | "reject" | "cancel" | "execute")[];
}

export interface PreviewInput {
  recommendation_id?: string;
  action_type?: CampaignAction["action_type"];
  level?: CampaignAction["level"];
  object_id?: string;
  daily_budget_minor?: number;
  reason?: string;
}

const post = (value?: unknown): RequestInit => ({ method: "POST", body: value === undefined ? undefined : JSON.stringify(value) });
const a = (id: string) => `/api/campaign-actions/${encodeURIComponent(id)}`;

export const previewAction = (clientId: string, body: PreviewInput) =>
  request<CampaignAction>(`/api/clients/${encodeURIComponent(clientId)}/campaign-actions/preview`, post(body));
export const listActions = (clientId: string) => request<CampaignAction[]>(`/api/clients/${encodeURIComponent(clientId)}/campaign-actions`);
export const getAction = (id: string) => request<CampaignAction>(a(id));
export const approveAction = (id: string, note?: string) => request<CampaignAction>(`${a(id)}/approve`, post({ note }));
export const rejectAction = (id: string, note?: string) => request<CampaignAction>(`${a(id)}/reject`, post({ note }));
export const cancelAction = (id: string) => request<CampaignAction>(`${a(id)}/cancel`, post());
export const executeAction = (id: string) => request<CampaignAction>(`${a(id)}/execute`, post({ confirm: true }));
