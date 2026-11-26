/** Phase 3 Creative Studio API shapes. */

export type CreativeStatus = "GENERATED" | "EDITING" | "REVIEW_PENDING" | "APPROVED" | "REJECTED" | "ARCHIVED";
export type CreativeAction = "edit" | "submit" | "approve" | "reject" | "archive";

export const AD_TYPES: { id: string; label: string }[] = [
  { id: "property_launch", label: "Property launch" },
  { id: "apartment", label: "Apartment" },
  { id: "villa", label: "Villa" },
  { id: "location", label: "Project location" },
  { id: "amenities", label: "Amenities" },
  { id: "investment", label: "Investment-focused" },
  { id: "site_visit", label: "Site visit" },
  { id: "limited_offer", label: "Limited-period offer" },
  { id: "other", label: "Other" },
];

export interface ClaimCheck { unsupported_numbers: string[]; flagged_phrases: { text: string; reason: string }[] }

export interface CopyBrief {
  ad_type: string;
  product: string;
  audience?: string;
  location?: string;
  objective?: string;
  language?: "en" | "te" | "hi" | "kn" | "ta";
  tone?: string;
  offer?: string;
  cta?: string;
  facts: string[];
}

export interface CopySuggestions {
  headlines: string[];
  primary_texts: string[];
  descriptions: string[];
  cta_recommendations: { cta: string; reason: string }[];
  concepts: { title: string; visual: string; angle: string }[];
  claim_check: ClaimCheck;
}

export interface CreativeSummary {
  id: string;
  client_id: string;
  name: string;
  ad_type: string;
  status: CreativeStatus;
  current_version: number;
  approved_version: number | null;
  image_id: string | null;
  headline: string | null;
  created_by: string;
  updated_at: string;
  media_type: "image" | "video";
  video_id: string | null;
  video_duration_ms: number | null;
}

export interface CreativeVersion {
  version: number;
  image_id: string | null;
  image_source: "ai_generated" | "uploaded" | null;
  image_prompt: string | null;
  headline: string | null;
  primary_text: string | null;
  description: string | null;
  cta: string | null;
  facts: string[];
  claim_check: ClaimCheck | null;
  change_note: string | null;
  created_by: string;
  created_at: string;
  video_id: string | null;
  video_duration_ms: number | null;
}

export interface CreativeDetail {
  asset: CreativeSummary;
  versions: CreativeVersion[];
  approvals: { id: string; version: number; decision: "approved" | "rejected"; claims_acknowledged: boolean; decided_by: string; decided_at: string; note: string | null }[];
  allowed_actions: CreativeAction[];
}

export interface CreativeEdit {
  name?: string;
  headline?: string;
  primary_text?: string;
  description?: string;
  cta?: string;
  facts?: string[];
  image_base64?: string;
  image_mime?: string;
  change_note?: string;
}

/** Meta ad image shapes: 9:16 Stories/Reels (1080x1920), 4:5 feed (1080x1350), 1:1 square (1080x1080). */
export type AdAspect = "9:16" | "4:5" | "1:1";
export const AD_ASPECTS: { id: AdAspect; label: string; hint: string }[] = [
  { id: "9:16", label: "9:16", hint: "Stories & Reels · 1080×1920" },
  { id: "4:5", label: "4:5", hint: "Feed · 1080×1350" },
  { id: "1:1", label: "1:1", hint: "Square · 1080×1080" },
];
