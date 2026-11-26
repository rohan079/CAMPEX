import { request, requestBlob, uploadForm } from "./api";
import type { AdAspect } from "./creativeTypes";
import type { CopyBrief, CopySuggestions, CreativeDetail, CreativeEdit, CreativeSummary } from "./creativeTypes";


/** Phase 3 Creative Studio endpoints. Generated artwork is stored by Campex; nothing here touches Meta. */

const c = (clientId: string) => `/api/clients/${encodeURIComponent(clientId)}/creative`;
const a = (id: string) => `/api/creative/assets/${encodeURIComponent(id)}`;
const post = (value?: unknown): RequestInit => ({ method: "POST", body: value === undefined ? undefined : JSON.stringify(value) });

export const generateCopy = (clientId: string, brief: CopyBrief) => request<CopySuggestions>(`${c(clientId)}/copy`, post(brief));
export const generateArtwork = (clientId: string, body: { name: string; ad_type: string; prompt: string; facts: string[];
  reference_base64?: string; reference_mime?: string; aspect?: AdAspect }) => request<CreativeDetail>(`${c(clientId)}/generate`, post(body));
export const uploadArtwork = (clientId: string, body: { name: string; ad_type: string; image_base64: string; mime_type: string; facts: string[]; aspect?: AdAspect }) =>
  request<CreativeDetail>(`${c(clientId)}/assets`, post(body));
export const listCreatives = (clientId: string, status?: string) =>
  request<CreativeSummary[]>(`${c(clientId)}/assets${status ? `?status=${encodeURIComponent(status)}` : ""}`);
export const getCreative = (id: string) => request<CreativeDetail>(a(id));
export const editCreative = (id: string, body: CreativeEdit) => request<CreativeDetail>(a(id), { method: "PUT", body: JSON.stringify(body) });
/** Re-cuts the current artwork to an exact Meta size as a new version (goes back to review). */
export const reformatCreative = (id: string, aspect: AdAspect) => request<CreativeDetail>(`${a(id)}/reformat`, post({ aspect }));
export const submitCreative = (id: string) => request<CreativeDetail>(`${a(id)}/submit`, post());
export const approveCreative = (id: string, claimsAcknowledged: boolean, note?: string) =>
  request<CreativeDetail>(`${a(id)}/approve`, post({ claims_acknowledged: claimsAcknowledged, note }));
export const rejectCreative = (id: string, note: string) => request<CreativeDetail>(`${a(id)}/reject`, post({ note }));
export const archiveCreative = (id: string) => request<CreativeDetail>(`${a(id)}/archive`, post());
export const creativeImage = (imageId: string) => requestBlob(`/api/creative/images/${encodeURIComponent(imageId)}`);

export const getMediaLimits = () => request<{ video_enabled: boolean; max_video_seconds: number; max_video_mb: number }>("/api/creative/media-limits");
export const creativeVideo = (mediaId: string) => requestBlob(`/api/creative/videos/${encodeURIComponent(mediaId)}`);

export function uploadVideoCreative(clientId: string, input: { name: string; adType: string; facts: string[]; video: File; thumbnail: Blob },
  onProgress?: (fraction: number) => void) {
  const form = new FormData();
  form.append("name", input.name);
  form.append("ad_type", input.adType);
  input.facts.forEach((f) => form.append("facts", f));
  form.append("video", input.video, input.video.name);
  form.append("thumbnail", input.thumbnail, "thumbnail.png");
  return uploadForm<CreativeDetail>(`${c(clientId)}/videos`, form, onProgress);
}

/** Reads a File as base64 (without the data: prefix). */
export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ""));
    reader.onerror = () => reject(new Error("Could not read the file"));
    reader.readAsDataURL(file);
  });
}
