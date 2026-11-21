import { request } from "./api";

/** Phase 3 experiment execution: readiness from Meta, approved launch, progress, follow-up on a reliable winner. */

export interface ExperimentCheck { code: string; severity: "error" | "warning" | "info"; message: string; variant_role: string | null }

export interface ExperimentExecution {
  experiment_id: string;
  experiment_status: "draft" | "running" | "completed" | "cancelled";
  outcome: string | null;
  phase: "setup" | "awaiting_activation" | "measuring" | "completed" | string;
  ready: boolean;
  checks: ExperimentCheck[];
  variants: { variant_id: string; role: "control" | "test"; name: string; level: "ad" | "adset"; object_id: string; object_name: string | null;
    status: string | null; effective_status: string | null; campaign_id: string | null; creative_asset_id: string | null;
    creative_version: number | null; creative_name: string | null }[];
  actions: { action_id: string; purpose: "activate" | "pause_loser"; variant_role: string | null; action_type: string; status: string;
    verification: string | null; created_at: string }[];
  progress: { start_date: string | null; planned_end_date: string | null; days_elapsed: number | null; days_planned: number | null;
    min_sample_size: number; interim: Record<string, unknown> | null; sample_reached: boolean; duration_reached: boolean; guidance: string | null };
  allowed_actions: ("launch" | "follow_up")[];
}

const e = (id: string) => `/api/experiments/${encodeURIComponent(id)}`;
export const getExperimentExecution = (id: string) => request<ExperimentExecution>(`${e(id)}/execution`);
export const launchExperiment = (id: string) => request<ExperimentExecution>(`${e(id)}/launch`, { method: "POST" });
export const followUpExperiment = (id: string) => request<ExperimentExecution>(`${e(id)}/follow-up`, { method: "POST" });
export const linkVariantCreative = (id: string, variantId: string, creativeAssetId: string | null) =>
  request<ExperimentExecution>(`${e(id)}/variants/${encodeURIComponent(variantId)}/creative`,
    { method: "PUT", body: JSON.stringify({ creative_asset_id: creativeAssetId }) });
