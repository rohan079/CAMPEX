import { request } from "./api";
import type {
  Anomaly, AskResult, AudienceReport, BudgetAnalysis, BudgetPolicy, CreativeDraft, CreativeReport, Experiment,
  GenerateResult, Overview, PerformanceReport, Recommendation, Trends,
} from "./intelligenceTypes";
import type { DateRange } from "./measurementFormat";

/** Phase 2 endpoints. All read-only towards Meta; nothing here changes a live campaign. */

const c = (clientId: string) => `/api/clients/${encodeURIComponent(clientId)}`;
const q = (params: Record<string, string | undefined>) => {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v) s.set(k, v); });
  const text = s.toString();
  return text ? `?${text}` : "";
};
const body = (method: string, value?: unknown): RequestInit => ({ method, body: value === undefined ? undefined : JSON.stringify(value) });

export const getOverview = (clientId: string, r: DateRange, campaignId?: string) =>
  request<Overview>(`${c(clientId)}/performance/overview${q({ ...r, campaign_id: campaignId })}`);
export interface CampaignOption { id: string; name: string | null; status: string | null; ad_account_id: string | null; has_data: boolean }
export const getCampaignOptions = (clientId: string) =>
  request<{ campaigns: CampaignOption[]; note: string | null }>(`${c(clientId)}/performance/campaign-options`);
export const getPerformance = (clientId: string, level: "campaigns" | "adsets" | "ads", r: DateRange, campaignId?: string) =>
  request<PerformanceReport>(`${c(clientId)}/performance/${level}${q({ ...r, campaign_id: campaignId })}`);
export const getTrends = (clientId: string, r: DateRange, campaignId?: string) =>
  request<Trends>(`${c(clientId)}/performance/trends${q({ ...r, campaign_id: campaignId })}`);
export const refreshPerformance = (clientId: string) =>
  request<{ snapshots: number; ad_accounts: string[]; data_notes: { message: string }[] }>(`${c(clientId)}/performance/refresh`, body("POST"));
export const askPerformance = (clientId: string, question: string, r: DateRange, comparison: "previous_period" | "previous_month") =>
  request<AskResult>(`${c(clientId)}/performance/ask`, body("POST", { question, ...r, comparison }));

export const listRecommendations = (clientId: string, status?: string, category?: string) =>
  request<Recommendation[]>(`${c(clientId)}/recommendations${q({ status, category })}`);
export const generateRecommendations = (clientId: string, r: DateRange, explain = true) =>
  request<GenerateResult>(`${c(clientId)}/recommendations/generate`, body("POST", { ...r, explain }));
export const updateRecommendation = (id: string, status: "accepted" | "rejected" | "reviewed", note?: string) =>
  request<{ recommendation: Recommendation; message: string }>(`/api/recommendations/${id}/status`, body("PATCH", { status, note }));

export const getBudgetAnalysis = (clientId: string, r: DateRange) => request<BudgetAnalysis>(`${c(clientId)}/budget/analysis${q({ ...r })}`);
export const getBudgetPolicy = (clientId: string) => request<BudgetPolicy>(`${c(clientId)}/budget/policy`);
export const saveBudgetPolicy = (clientId: string, policy: Partial<BudgetPolicy>) =>
  request<BudgetPolicy>(`${c(clientId)}/budget/policy`, body("PUT", policy));

export const getCreativePerformance = (clientId: string, r: DateRange) => request<CreativeReport>(`${c(clientId)}/creative-performance${q({ ...r })}`);
export const analyzeCreative = (clientId: string, adId: string, r: DateRange) =>
  request<CreativeDraft>(`${c(clientId)}/creative-analysis`, body("POST", { ad_id: adId, ...r }));

export const getAudience = (clientId: string, r: DateRange, breakdown: string) =>
  request<AudienceReport>(`${c(clientId)}/audience-performance${q({ ...r, breakdown })}`);
export const getPlacements = (clientId: string, r: DateRange) => request<AudienceReport>(`${c(clientId)}/placement-performance${q({ ...r })}`);

export const listAnomalies = (clientId: string, status?: string) => request<Anomaly[]>(`${c(clientId)}/anomalies${q({ status })}`);
export const detectAnomalies = (clientId: string) => request<{ detected: number; created: number }>(`${c(clientId)}/anomalies/detect`, body("POST"));
export const updateAnomaly = (id: string, status: "acknowledged" | "resolved" | "dismissed") =>
  request<Anomaly>(`/api/anomalies/${id}/status`, body("PATCH", { status }));
export const explainAnomaly = (id: string) => request<Anomaly>(`/api/anomalies/${id}/explain`, body("POST"));

export const listExperiments = (clientId: string) => request<Experiment[]>(`${c(clientId)}/experiments`);
export const getExperiment = (id: string) => request<Experiment>(`/api/experiments/${id}`);
export const createExperiment = (clientId: string, value: Record<string, unknown>) =>
  request<Experiment>(`${c(clientId)}/experiments`, body("POST", value));
export const startExperiment = (id: string) => request<Experiment>(`/api/experiments/${id}/start`, body("POST"));
export const completeExperiment = (id: string) => request<Experiment>(`/api/experiments/${id}/complete`, body("POST"));

export { completeDays } from "./measurementFormat";
