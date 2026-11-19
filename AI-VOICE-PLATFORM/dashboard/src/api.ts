import { auth } from "./firebase";
import type {
  CallPage,
  CampaignAssistRequest,
  CampaignAssistResponse,
  CampaignSkillSummary,
  LeadReviewStats,
  LeadReviewStatus,
  Client,
  ClientAnalytics,
  Comparison,
  DashboardPayload,
  Health,
  LanguageCode,
  LeadDetail,
  LeadPage,
  ReachRowInput,
  RegisteredAd,
  Sale,
  SaleChannel,
  SalePage,
} from "./types";

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}

async function authHeaders(init?: RequestInit): Promise<Headers> {
  const headers = new Headers(init?.headers);
  if (init?.body) headers.set("Content-Type", "application/json");
  // Wait for Firebase to finish restoring/redirect login before reading currentUser.
  await auth.authStateReady();
  const user = auth.currentUser;
  if (!user) {
    throw new ApiError("Sign in required", 401);
  }
  headers.set("Authorization", `Bearer ${await user.getIdToken()}`);
  return headers;
}

function publicErrorMessage(status: number, raw?: string): string {
  if (status === 401) return "Sign in required";
  if (status === 403) return "You don't have access to this action";
  if (status === 404) return "The requested item was not found";
  if (status === 429) return "Too many requests. Try again shortly.";
  if (status >= 500) return "Something went wrong. Try again in a moment.";
  const candidate = raw?.trim();
  if (
    candidate
    && candidate.length < 160
    && !/exception|stack|trace|sql|token|secret|api[_ ]?key|oauth|firebase|jwks|bearer|traceback|\/Users\/|java\.|org\.|at \w+\./i.test(candidate)
  ) {
    return candidate;
  }
  return "Request could not be completed. Try again.";
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = await authHeaders(init);
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  } catch {
    throw new Error("Cannot reach Campex right now. Refresh and try again.");
  }
  if (!response.ok) {
    let raw: string | undefined;
    try {
      const body = JSON.parse(await response.text()) as { detail?: unknown; message?: unknown };
      if (typeof body.detail === "string") raw = body.detail;
      else if (typeof body.message === "string") raw = body.message;
    } catch {
      // ignore parse errors
    }
    throw new ApiError(publicErrorMessage(response.status, raw), response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export interface MetaPage {
  id: string;
  name: string;
  category?: string | null;
}

export interface MetaAdAccount {
  id: string;
  name: string;
  account_id?: string | null;
  account_status?: number | null;
  currency?: string | null;
}

export interface MetaInstagramAccount {
  id: string;
  name: string;
  username?: string | null;
  page_id?: string | null;
}

export interface MetaConnectionStatus {
  connected: boolean;
  expired?: boolean;
  meta_user_name?: string | null;
  connected_at?: string | null;
  token_expires_at?: string | null;
  pages: MetaPage[];
  ad_accounts: MetaAdAccount[];
  instagram_accounts?: MetaInstagramAccount[];
}

export function getMetaConnection(): Promise<MetaConnectionStatus> {
  return request<MetaConnectionStatus>("/api/oauth/meta/status");
}

export function disconnectMeta(): Promise<void> {
  return request<void>("/api/oauth/meta/connection", { method: "DELETE" });
}

export interface MetaCampaignPublishRequest {
  campaign_name: string;
  objective: "leads" | "traffic" | "sales" | "awareness";
  platforms: Array<"facebook" | "instagram" | "whatsapp_ads">;
  location: string;
  age_min: number;
  age_max: number;
  headline: string;
  body: string;
  cta: string;
  ad_account_id?: string;
  page_id?: string;
  daily_budget_minor?: number;
  confirmed: boolean;
}

export interface MetaCampaignPublishResponse {
  campaign_id: string;
  ad_set_id: string;
  ad_id?: string | null;
  ad_account_id: string;
  status: string;
  ads_manager_url: string;
  warning?: string | null;
}

export function publishMetaCampaign(body: MetaCampaignPublishRequest): Promise<MetaCampaignPublishResponse> {
  return request<MetaCampaignPublishResponse>("/api/meta/campaigns/publish", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export interface MetaMcpTool {
  name: string;
  description: string;
  read_only: boolean;
}

export interface MetaAdsDashboard {
  connected: boolean;
  tools: MetaMcpTool[];
  businesses: Array<Record<string, unknown>>;
  ad_accounts: Array<Record<string, unknown>>;
  pages: Array<Record<string, unknown>>;
  instagram_accounts: Array<Record<string, unknown>>;
  selected_ad_account_id?: string | null;
  account_details?: Record<string, unknown> | null;
  campaigns: Array<Record<string, unknown>>;
  ad_sets: Array<Record<string, unknown>>;
  ads: Array<Record<string, unknown>>;
  account_insights: Array<Record<string, unknown>>;
  date_preset: string;
}

export function getMetaAdsDashboard(params?: {
  adAccountId?: string;
  datePreset?: string;
}): Promise<MetaAdsDashboard> {
  const query = new URLSearchParams();
  if (params?.adAccountId) query.set("ad_account_id", params.adAccountId);
  if (params?.datePreset) query.set("date_preset", params.datePreset);
  const suffix = query.toString() ? `?${query}` : "";
  return request<MetaAdsDashboard>(`/api/meta/ads-dashboard${suffix}`);
}

export function getHealth(): Promise<Health> {
  return request<Health>("/api/health");
}

export function getDashboard(clientId?: string): Promise<DashboardPayload> {
  return request<DashboardPayload>(`/api/dashboard${clientId ? `?client_id=${clientId}` : ""}`);
}

export function getLeads(params: {
  clientId?: string;
  status?: string;
  language?: string;
  reviewStatus?: LeadReviewStatus | "";
  q?: string;
  limit?: number;
  offset?: number;
}): Promise<LeadPage> {
  const search = new URLSearchParams();
  if (params.clientId) search.set("client_id", params.clientId);
  if (params.status) search.set("status", params.status);
  if (params.language) search.set("language", params.language);
  if (params.reviewStatus) search.set("review_status", params.reviewStatus);
  if (params.q) search.set("q", params.q);
  if (params.limit !== undefined) search.set("limit", String(params.limit));
  if (params.offset !== undefined) search.set("offset", String(params.offset));
  const query = search.toString();
  return request<LeadPage>(`/api/leads${query ? `?${query}` : ""}`);
}

export function getLeadReviewStats(clientId?: string): Promise<LeadReviewStats> {
  const query = clientId ? `?client_id=${encodeURIComponent(clientId)}` : "";
  return request<LeadReviewStats>(`/api/leads/review-stats${query}`);
}

export function updateLeadReview(id: string, reviewStatus: LeadReviewStatus): Promise<LeadDetail> {
  return request<LeadDetail>(`/api/leads/${id}/review`, {
    method: "PATCH",
    body: JSON.stringify({ review_status: reviewStatus }),
  });
}

export function assistCampaign(body: CampaignAssistRequest): Promise<CampaignAssistResponse> {
  return request<CampaignAssistResponse>("/api/ai/campaign-assist", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function listCampaignSkills(): Promise<CampaignSkillSummary[]> {
  return request<CampaignSkillSummary[]>("/api/ai/campaign-skills");
}

export function uploadCampaignSkill(body: {
  markdown: string;
  name?: string;
  description?: string;
}): Promise<CampaignSkillSummary> {
  return request<CampaignSkillSummary>("/api/ai/campaign-skills", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function deleteCampaignSkill(skillId: string): Promise<void> {
  return request<void>(`/api/ai/campaign-skills/${encodeURIComponent(skillId)}`, {
    method: "DELETE",
  });
}

export function getCalls(params: { clientId?: string; status?: string; language?: string }): Promise<CallPage> {
  const search = new URLSearchParams();
  if (params.clientId) search.set("client_id", params.clientId);
  if (params.status) search.set("status", params.status);
  if (params.language) search.set("language", params.language);
  const query = search.toString();
  return request<CallPage>(`/api/calls${query ? `?${query}` : ""}`);
}

export function getLead(id: string): Promise<LeadDetail> {
  return request<LeadDetail>(`/api/leads/${id}`);
}

export function updateLeadLanguage(id: string, language: LanguageCode): Promise<LeadDetail> {
  return request<LeadDetail>(`/api/leads/${id}/language`, {
    method: "PATCH",
    body: JSON.stringify({ language }),
  });
}

export function getClients(): Promise<Client[]> {
  return request<Client[]>("/api/clients");
}

export function createClient(body: {
  name: string;
  description?: string;
  voice_agent_enabled: boolean;
  knowledge_base: string;
}): Promise<Client> {
  return request<Client>("/api/clients", { method: "POST", body: JSON.stringify(body) });
}

export function updateClientVoiceSettings(
  clientId: string,
  body: { voice_agent_enabled: boolean; knowledge_base: string },
): Promise<Client> {
  return request<Client>(`/api/clients/${clientId}/voice-settings`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

export function getClientAds(clientId: string): Promise<RegisteredAd[]> {
  return request<RegisteredAd[]>(`/api/clients/${clientId}/ads`);
}

export function registerAd(clientId: string, body: { external_id: string; name?: string }): Promise<RegisteredAd> {
  return request<RegisteredAd>(`/api/clients/${clientId}/ads`, { method: "POST", body: JSON.stringify(body) });
}

export function importReach(clientId: string, rows: ReachRowInput[]): Promise<{ imported: number }> {
  return request<{ imported: number }>(`/api/clients/${clientId}/ad-reach`, {
    method: "POST",
    body: JSON.stringify({ rows }),
  });
}

export function getAnalytics(clientId: string | undefined, days: number): Promise<ClientAnalytics> {
  const search = new URLSearchParams({ days: String(days) });
  if (clientId) search.set("client_id", clientId);
  return request<ClientAnalytics>(`/api/analytics?${search.toString()}`);
}

export function getComparison(days: number): Promise<Comparison> {
  return request<Comparison>(`/api/analytics/clients?days=${days}`);
}

export function getSales(params: { clientId?: string; channel?: SaleChannel; limit?: number }): Promise<SalePage> {
  const search = new URLSearchParams();
  if (params.clientId) search.set("client_id", params.clientId);
  if (params.channel) search.set("channel", params.channel);
  search.set("limit", String(params.limit ?? 20));
  return request<SalePage>(`/api/sales?${search.toString()}`);
}

export function recordSale(body: {
  client_id?: string;
  lead_id?: string;
  ad_id?: string;
  channel: SaleChannel;
  amount: number;
  note?: string;
}): Promise<Sale> {
  return request<Sale>("/api/sales", { method: "POST", body: JSON.stringify(body) });
}

export function deleteSale(id: string): Promise<{ status: string }> {
  return request<{ status: string }>(`/api/sales/${id}`, { method: "DELETE" });
}
