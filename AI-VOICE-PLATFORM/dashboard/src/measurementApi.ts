import { request } from "./api";
import type {
  Activity, Agent, AgentPerformance, AttributionSummary, CampaignReport, ConversionEvent, CrmLeadDetail, EventPage,
  EventSummary, FollowUp, KpiReport, LeadAttribution, Pipeline, PipelineStatus, Pixel, PixelInput, Project,
  Qualification, SaleListPage, SaleRecord, SiteVisit,
} from "./measurementTypes";

/** Phase 1 endpoints. All go through the authenticated dashboard proxy; no secrets live in the browser. */

import type { DateRange } from "./measurementFormat";
export type { DateRange };

function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

const c = (clientId: string) => `/api/clients/${encodeURIComponent(clientId)}`;
const json = (method: string, body?: unknown): RequestInit => ({ method, body: body === undefined ? undefined : JSON.stringify(body) });

// Tracking
export const listPixels = (clientId: string) => request<Pixel[]>(`${c(clientId)}/tracking/pixels`);
export const createPixel = (clientId: string, body: PixelInput) => request<Pixel>(`${c(clientId)}/tracking/pixels`, json("POST", body));
export const updatePixel = (clientId: string, id: string, body: PixelInput) => request<Pixel>(`${c(clientId)}/tracking/pixels/${id}`, json("PUT", body));
export const deletePixel = (clientId: string, id: string) => request<{ status: string }>(`${c(clientId)}/tracking/pixels/${id}`, json("DELETE"));
export const listEvents = (clientId: string, f: DateRange & { event_name?: string; source?: string; delivery_status?: string; campaign_id?: string; offset?: number }) =>
  request<EventPage>(`${c(clientId)}/tracking/events${qs({ ...f, limit: 50 })}`);
export const getEvent = (clientId: string, id: string) => request<ConversionEvent>(`${c(clientId)}/tracking/events/${id}`);
export const getEventSummary = (clientId: string, r: DateRange) => request<EventSummary>(`${c(clientId)}/tracking/events/summary${qs({ ...r })}`);
export const sendTestEvent = (clientId: string, pixelId: string, eventName = "Lead") =>
  request<{ status: string; event_id: string; delivery_status: string }>(`${c(clientId)}/tracking/events/test`, json("POST", { pixel_id: pixelId, event_name: eventName }));
export const getAdAccounts = (clientId: string) => request<{ ad_account_ids: string[] }>(`${c(clientId)}/measurement/ad-accounts`);
export const setAdAccounts = (clientId: string, ids: string[]) => request<{ ad_account_ids: string[] }>(`${c(clientId)}/measurement/ad-accounts`, json("PUT", { ad_account_ids: ids }));

// Attribution & KPIs
export const getAttributionSummary = (clientId: string, r: DateRange) => request<AttributionSummary>(`${c(clientId)}/attribution/summary${qs({ ...r })}`);
export const getCampaignReport = (clientId: string, r: DateRange & { model: string; group_by: string }) =>
  request<CampaignReport>(`${c(clientId)}/attribution/campaigns${qs({ ...r })}`);
export const getLeadAttribution = (leadId: string) => request<LeadAttribution>(`/api/leads/${leadId}/attribution`);
export const getKpis = (clientId: string, f: DateRange & { campaign_id?: string; agent_id?: string; project_id?: string; ad_account_id?: string }) =>
  request<KpiReport>(`${c(clientId)}/measurement/kpis${qs({ ...f })}`);

// CRM
export const getPipeline = (clientId: string, f: { q?: string; agent_id?: string; project_id?: string; source?: string }) =>
  request<Pipeline>(`${c(clientId)}/crm/pipeline${qs({ ...f })}`);
export const listAgents = (clientId: string) => request<Agent[]>(`${c(clientId)}/crm/agents`);
export const createAgent = (clientId: string, body: { name: string; email?: string; phone_number?: string }) =>
  request<Agent>(`${c(clientId)}/crm/agents`, json("POST", body));
export const listProjects = (clientId: string) => request<Project[]>(`${c(clientId)}/projects`);
export const createProject = (clientId: string, body: { name: string; location?: string; min_price?: number; max_price?: number }) =>
  request<Project>(`${c(clientId)}/projects`, json("POST", body));
export const getPerformance = (clientId: string, r: DateRange) => request<{ agents: AgentPerformance[]; currency: string }>(`${c(clientId)}/crm/performance${qs({ ...r })}`);
export const createManualLead = (clientId: string, body: { phone_number: string; name?: string; email?: string; agent_id?: string; project_id?: string; note?: string }) =>
  request<CrmLeadDetail>(`${c(clientId)}/crm/leads`, json("POST", body));
export const getCrmLead = (leadId: string) => request<CrmLeadDetail>(`/api/leads/${leadId}/crm`);
export const assignLead = (leadId: string, agentId: string, reason?: string) => request<CrmLeadDetail>(`/api/leads/${leadId}/assign`, json("POST", { agent_id: agentId, reason }));
export const changeLeadStatus = (leadId: string, status: PipelineStatus, extra: { reason?: string; lost_reason?: string } = {}) =>
  request<CrmLeadDetail>(`/api/leads/${leadId}/status`, json("PATCH", { status, ...extra }));
export const listActivities = (leadId: string) => request<Activity[]>(`/api/leads/${leadId}/activities`);
export const addActivity = (leadId: string, activity_type: string, body: string) => request<Activity>(`/api/leads/${leadId}/activities`, json("POST", { activity_type, body }));
export const addFollowUp = (leadId: string, body: { due_at: string; channel: string; note?: string }) => request<FollowUp>(`/api/leads/${leadId}/follow-ups`, json("POST", body));
export const updateFollowUp = (id: string, status: "done" | "cancelled") => request<FollowUp>(`/api/follow-ups/${id}`, json("PATCH", { status }));
export const saveQualification = (leadId: string, body: Partial<Qualification> & { project_id?: string }) =>
  request<Qualification>(`/api/leads/${leadId}/qualification`, json("PUT", body));
export const recordLeadConsent = (leadId: string, granted: boolean) => request<CrmLeadDetail>(`/api/leads/${leadId}/tracking-consent`, json("POST", { ad_data_granted: granted }));

// Site visits
export const listSiteVisits = (clientId: string, f: DateRange & { status?: string; agent_id?: string }) =>
  request<{ visits: SiteVisit[]; total: number }>(`${c(clientId)}/site-visits${qs({ ...f })}`);
export const createSiteVisit = (leadId: string, body: { scheduled_at: string; agent_id?: string; project_id?: string; duration_minutes?: number; notes?: string }) =>
  request<SiteVisit>(`/api/leads/${leadId}/site-visits`, json("POST", body));
export const updateSiteVisitStatus = (id: string, body: { status: string; outcome?: string; follow_up_notes?: string; cancellation_reason?: string }) =>
  request<SiteVisit>(`/api/site-visits/${id}/status`, json("PATCH", body));
export const rescheduleSiteVisit = (id: string, scheduled_at: string, reason?: string) =>
  request<SiteVisit>(`/api/site-visits/${id}/reschedule`, json("POST", { scheduled_at, reason }));

// Sales
export const listClientSales = (clientId: string, f: { status?: string; campaign_id?: string; offset?: number }) =>
  request<SaleListPage>(`${c(clientId)}/sales${qs({ ...f, limit: 50 })}`);
export const getSale = (id: string) => request<SaleRecord>(`/api/sales/${id}`);
export const createClientSale = (clientId: string, body: Record<string, unknown>) => request<SaleRecord>(`${c(clientId)}/sales`, json("POST", body));
export const updateSale = (id: string, body: Record<string, unknown>) => request<SaleRecord>(`/api/sales/${id}`, json("PUT", body));
export const confirmSale = (id: string, amount_collected?: number) => request<SaleRecord>(`/api/sales/${id}/confirm`, json("POST", { amount_collected }));
export const cancelSale = (id: string, reason: string) => request<SaleRecord>(`/api/sales/${id}/cancel`, json("POST", { reason }));

export { labelize, rangeForDays, SOURCE_LABELS } from "./measurementFormat";
