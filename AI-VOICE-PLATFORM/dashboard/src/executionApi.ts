import { request } from "./api";
import type { AppNotification, CampaignConfig, ExecutionDetail, ExecutionSummary, MetaLocation, ValidationReport } from "./executionTypes";

/** Phase 3 execution endpoints. Publishing always creates PAUSED objects and requires a recorded approval. */

const post = (value?: unknown, headers?: Record<string, string>): RequestInit =>
  ({ method: "POST", body: value === undefined ? undefined : JSON.stringify(value), headers });
const e = (id: string) => `/api/campaign-executions/${encodeURIComponent(id)}`;

export const listExecutions = (clientId: string, status?: string) =>
  request<ExecutionSummary[]>(`/api/clients/${encodeURIComponent(clientId)}/campaign-executions${status ? `?status=${encodeURIComponent(status)}` : ""}`);
export const createExecution = (clientId: string, config: CampaignConfig, idempotencyKey: string) =>
  request<ExecutionDetail>(`/api/clients/${encodeURIComponent(clientId)}/campaign-executions`,
    post(config, { "Idempotency-Key": idempotencyKey }));
export const getExecution = (id: string) => request<ExecutionDetail>(e(id));
export const updateExecution = (id: string, config: CampaignConfig) =>
  request<ExecutionDetail>(e(id), { method: "PUT", body: JSON.stringify(config) });
export const validateExecution = (id: string) => request<ValidationReport>(`${e(id)}/validate`, post());
export const submitExecution = (id: string) => request<ExecutionDetail>(`${e(id)}/submit`, post());
export const approveExecution = (id: string, note?: string) => request<ExecutionDetail>(`${e(id)}/approve`, post({ note }));
export const rejectExecution = (id: string, note: string) => request<ExecutionDetail>(`${e(id)}/reject`, post({ note }));
export const publishExecution = (id: string) => request<ExecutionDetail>(`${e(id)}/publish`, post({ confirm: true }));
export const reviseExecution = (id: string) => request<ExecutionDetail>(`${e(id)}/revise`, post());
export const searchMetaLocations = (clientId: string, q: string) =>
  request<{ locations: MetaLocation[] }>(`/api/clients/${encodeURIComponent(clientId)}/meta/locations?q=${encodeURIComponent(q)}`);
export const retryExecution = (id: string) => request<ExecutionDetail>(`${e(id)}/retry`, post());
export const reconcileExecution = (id: string) => request<ExecutionDetail>(`${e(id)}/reconcile`, post());
export const cancelExecution = (id: string) => request<ExecutionDetail>(`${e(id)}/cancel`, post());

export const retryAd = (id: string) => request<ExecutionDetail>(`${e(id)}/ad/retry`, post());
export const checkAd = (id: string) => request<ExecutionDetail>(`${e(id)}/ad/check`, post());

export const listNotifications = (unread = false) =>
  request<{ items: AppNotification[]; unread_count: number }>(`/api/notifications${unread ? "?unread=true" : ""}`);
export const markNotificationRead = (id: string) => request<{ ok: boolean }>(`/api/notifications/${encodeURIComponent(id)}/read`, post());
export const markAllNotificationsRead = () => request<{ updated: number }>("/api/notifications/read-all", post());
