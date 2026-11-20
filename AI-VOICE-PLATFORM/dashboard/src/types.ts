export type LanguageCode = "en" | "te";
export type LeadReviewStatus = "pending" | "accepted" | "rejected";
export type CampaignPlatform = "facebook" | "instagram" | "whatsapp_business" | "google_ads" | "whatsapp_ads";

export interface LatestCall {
  id: string;
  status: string;
  language: string;
  outcome: string | null;
  started_at: string | null;
}

export interface Lead {
  id: string;
  client_id: string;
  phone_number: string;
  first_name: string | null;
  status: string;
  review_status: LeadReviewStatus;
  preferred_language: string;
  campaign_id: string | null;
  ad_id: string | null;
  ad_headline: string | null;
  ad_body?: string | null;
  source: string;
  outreach_sent_at: string | null;
  replied_at: string | null;
  follow_up_start: string | null;
  follow_up_end: string | null;
  initial_message: string | null;
  created_at: string | null;
  updated_at: string | null;
  latest_call: LatestCall | null;
}

export interface Call {
  id: string;
  lead_id: string;
  lead_name: string | null;
  phone_number: string | null;
  status: string;
  language: string;
  outcome: string | null;
  summary: string | null;
  attempts: number;
  openai_session_id: string | null;
  started_at: string | null;
  ended_at: string | null;
  created_at: string | null;
}

export interface LeadDetail extends Lead {
  calls: Call[];
  language_locked: boolean;
}

export interface DashboardPayload {
  business_name: string;
  languages: { code: string; label: string; native: string }[];
  leads: {
    total: number;
    by_status: Record<string, number>;
    by_language: Record<string, number>;
  };
  calls: {
    total: number;
    by_status: Record<string, number>;
  };
  recent_leads: Lead[];
  recent_calls: Call[];
}

export interface LeadPage {
  leads: Lead[];
  total: number;
  limit: number;
  offset: number;
}

export interface LeadReviewStats {
  pending: number;
  accepted: number;
  rejected: number;
}

export interface AssistImagePayload {
  name: string;
  mime_type: "image/jpeg" | "image/png" | "image/webp";
  data_base64: string;
}

export interface CampaignAssistRequest {
  campaign_name: string;
  objective: "leads" | "traffic" | "sales" | "awareness";
  platforms: CampaignPlatform[];
  language: LanguageCode;
  prompt: string;
  content?: string;
  ad_account_id?: string;
  client_id?: string;
  client_name?: string;
  skill_id?: string;
  images?: AssistImagePayload[];
  mode?: "assist" | "design";
  generate_image?: boolean;
}

export interface GeneratedAssistImage {
  mime_type: "image/png" | "image/jpeg" | "image/webp";
  data_base64: string;
  prompt?: string | null;
}

export interface CampaignAssistResponse {
  reply: string;
  provider: string;
  model: string;
  suggested_copy?: string | null;
  suggested_headline?: string | null;
  suggested_cta?: string | null;
  design_notes?: string | null;
  skill_id?: string | null;
  tools_used?: string[];
  generated_images?: GeneratedAssistImage[];
  image_error?: string | null;
}

export interface CampaignSkillSummary {
  id: string;
  name: string;
  description: string;
  source: "bundled" | "uploaded";
  featured: boolean;
}

export interface CallPage {
  calls: Call[];
  total: number;
  limit: number;
  offset: number;
}

export interface Health {
  status: string;
  environment: string;
}

export interface Client {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  voice_agent_enabled: boolean;
  knowledge_base: string;
  leads: number;
  created_at: string | null;
  meta_app_configured?: boolean;
  meta_app_id?: string | null;
  meta_login_config_id?: string | null;
}

export interface RegisteredAd {
  id: string;
  client_id: string;
  external_id: string;
  name: string | null;
}

export type SaleChannel = "ad" | "calling_agent";

export interface Sale {
  id: string;
  client_id: string;
  lead_id: string | null;
  lead_name: string | null;
  ad_id: string | null;
  channel: SaleChannel;
  amount: number;
  note: string | null;
  sold_at: string;
}

export interface SalePage {
  sales: Sale[];
  total: number;
}

export interface Summary {
  leads: number;
  ad_leads: number;
  calls: number;
  connected_calls: number;
  sales: number;
  revenue: number;
  ad_sales: number;
  ad_revenue: number;
  agent_sales: number;
  agent_revenue: number;
  reach: number;
  lead_to_sale_rate: number;
}

export interface LeadsDay { day: string; leads: number }
export interface SalesDay { day: string; sales: number; revenue: number }
export interface CallsDay { day: string; calls: number; connected: number }

export interface AdRow {
  ad_id: string;
  ad_name: string;
  leads: number;
  sales: number;
  revenue: number;
  agent_sales: number;
  agent_revenue: number;
  reach: number;
  lead_rate: number;
}

export interface LocationReach { location: string; reach: number }
export interface AdLocationReach { ad_id: string; ad_name: string; location: string; reach: number }
export interface AdReachRow {
  ad_id: string;
  ad_name: string;
  reach: number;
  leads: number;
  lead_rate: number;
  top_locations: LocationReach[];
}

export interface ClientAnalytics {
  client_id: string | null;
  client_name: string;
  currency: string;
  from: string;
  to: string;
  days: number;
  summary: Summary;
  leads_by_day: LeadsDay[];
  sales_by_day: SalesDay[];
  ad_sales: { sales: number; revenue: number; by_day: SalesDay[]; by_ad: AdRow[] };
  calling_agent: {
    calls: number;
    connected: number;
    sales: number;
    revenue: number;
    connected_rate: number;
    sale_rate: number;
    calls_by_day: CallsDay[];
    sales_by_day: SalesDay[];
  };
  reach: {
    total_reach: number;
    by_location: LocationReach[];
    by_ad: AdReachRow[];
    by_ad_location: AdLocationReach[];
  };
}

export interface ComparisonRow {
  client_id: string;
  client_name: string;
  leads: number;
  calls: number;
  sales: number;
  revenue: number;
  ad_revenue: number;
  agent_revenue: number;
  reach: number;
}

export interface Comparison {
  currency: string;
  from: string;
  to: string;
  days: number;
  clients: ComparisonRow[];
}

export interface ReachRowInput {
  ad_id: string;
  ad_name?: string;
  location: string;
  reach: number;
  impressions?: number;
  day: string;
}
