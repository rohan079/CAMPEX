import type {
  CallPage,
  CampaignAssistRequest,
  CampaignAssistResponse,
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body) headers.set("Content-Type", "application/json");
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  } catch {
    throw new Error("Cannot reach the CAMPEX API. Check the dashboard deployment and backend status, then refresh.");
  }
  if (!response.ok) {
    let message = response.statusText;
    try {
      const body = JSON.parse(await response.text()) as { detail?: unknown };
      if (typeof body.detail === "string") message = body.detail;
    } catch {
      message = response.statusText;
    }
    throw new ApiError(message || `Request failed (${response.status})`, response.status);
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

export interface MetaConnectionStatus {
  connected: boolean;
  expired?: boolean;
  meta_user_name?: string | null;
  connected_at?: string | null;
  token_expires_at?: string | null;
  pages: MetaPage[];
  ad_accounts: MetaAdAccount[];
}

export function getMetaConnection(): Promise<MetaConnectionStatus> {
  return request<MetaConnectionStatus>("/api/oauth/meta/status");
}

export function disconnectMeta(): Promise<void> {
  return request<void>("/api/oauth/meta/connection", { method: "DELETE" });
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
