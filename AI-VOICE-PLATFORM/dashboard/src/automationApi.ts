import { request } from "./api";

/** Phase 3 automation rules. Rules monitor, notify or prepare changes; they never raise budgets or activate. */

export type RuleType = "spend_over_limit" | "ctr_decline" | "cpl_above_threshold" | "delivery_issue";
export type Level = "MONITOR" | "RECOMMEND" | "APPROVAL_REQUIRED" | "PREAPPROVED";

export interface RuleInput {
  name: string;
  rule_type: RuleType;
  campaign_id?: string | null;
  threshold?: number | null;
  observation_days?: number;
  min_sample?: number;
  frequency?: "hourly" | "daily";
  severity?: "info" | "warning" | "critical";
  notify_team?: boolean;
  execution_level?: Level;
  max_auto_actions_per_day?: number;
  cooldown_hours?: number;
  enabled?: boolean;
}

export interface Rule extends Required<Omit<RuleInput, "campaign_id" | "threshold">> {
  id: string;
  client_id: string;
  campaign_id: string | null;
  threshold: number | null;
  proposed_action: "pause" | null;
  last_evaluated_at: string | null;
  last_outcome: string | null;
  created_by: string;
  updated_at: string;
}

export interface RuleExecution {
  id: string;
  rule_id: string;
  rule_name: string;
  object_id: string | null;
  object_name: string | null;
  outcome: "triggered" | "not_triggered" | "insufficient_data" | "cooldown" | "error";
  result: "recorded" | "notified" | "action_prepared" | "action_applied" | "action_blocked" | null;
  observed: Record<string, unknown>;
  message: string | null;
  action_id: string | null;
  triggered_by: string;
  evaluated_at: string;
}

const post = (value?: unknown): RequestInit => ({ method: "POST", body: value === undefined ? undefined : JSON.stringify(value) });
const c = (clientId: string) => `/api/clients/${encodeURIComponent(clientId)}`;
const r = (id: string) => `/api/automation-rules/${encodeURIComponent(id)}`;

export const getAutomationSettings = () => request<{ scheduler_enabled: boolean; preapproved_actions_enabled: boolean }>("/api/automation/settings");
export const listRules = (clientId: string) => request<Rule[]>(`${c(clientId)}/automation-rules`);
export const createRule = (clientId: string, body: RuleInput) => request<Rule>(`${c(clientId)}/automation-rules`, post(body));
export const updateRule = (id: string, body: RuleInput) => request<Rule>(r(id), { method: "PUT", body: JSON.stringify(body) });
export const setRuleEnabled = (id: string, enabled: boolean) => request<Rule>(`${r(id)}/${enabled ? "enable" : "disable"}`, post());
export const runRule = (id: string) => request<RuleExecution[]>(`${r(id)}/run`, post());
export const listRuleExecutions = (clientId: string) => request<RuleExecution[]>(`${c(clientId)}/automation-executions`);
