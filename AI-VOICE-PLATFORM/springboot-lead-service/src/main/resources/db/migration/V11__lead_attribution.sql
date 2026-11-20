-- Phase 1: campaign-to-lead attribution and the operational CRM columns on leads.
-- The existing leads.status (call lifecycle) and leads.review_status (pending/accepted/rejected) are unchanged;
-- pipeline_status is a separate, operational sales status.

ALTER TABLE leads
  ADD COLUMN pipeline_status VARCHAR(32) NOT NULL DEFAULT 'NEW'
    CHECK (pipeline_status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'SITE_VISIT_SCHEDULED', 'SITE_VISIT_COMPLETED',
                               'NEGOTIATION', 'WON', 'LOST')),
  ADD COLUMN pipeline_updated_at TIMESTAMPTZ,
  ADD COLUMN email VARCHAR(320),
  ADD COLUMN assigned_agent_id UUID,
  ADD COLUMN project_id UUID,
  ADD COLUMN first_contacted_at TIMESTAMPTZ,
  ADD COLUMN lost_reason VARCHAR(500),
  ADD CONSTRAINT fk_leads_agent_same_client FOREIGN KEY (client_id, assigned_agent_id)
    REFERENCES sales_agents (client_id, id),
  ADD CONSTRAINT fk_leads_project_same_client FOREIGN KEY (client_id, project_id)
    REFERENCES client_projects (client_id, id);
CREATE INDEX ix_leads_client_pipeline ON leads (client_id, pipeline_status);
CREATE INDEX ix_leads_client_agent ON leads (client_id, assigned_agent_id);
CREATE INDEX ix_leads_client_email ON leads (client_id, lower(email));

-- Every attribution signal ever seen for a lead (attribution history). Never updated after insert.
CREATE TABLE lead_attribution_touches (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  source VARCHAR(24) NOT NULL CHECK (source IN ('meta_lead_form', 'website', 'landing_page', 'ctwa',
    'whatsapp_referral', 'whatsapp', 'manual', 'ad_popup', 'backend_api', 'unknown')),
  resolution_method VARCHAR(24) NOT NULL CHECK (resolution_method IN
    ('meta_referral', 'click_id', 'campaign_metadata', 'utm', 'registered_ad', 'unknown')),
  campaign_id VARCHAR(128),
  campaign_name VARCHAR(255),
  adset_id VARCHAR(128),
  adset_name VARCHAR(255),
  ad_id VARCHAR(128),
  ad_name VARCHAR(255),
  meta_form_id VARCHAR(128),
  meta_lead_id VARCHAR(128),
  utm_source VARCHAR(255),
  utm_medium VARCHAR(255),
  utm_campaign VARCHAR(255),
  utm_content VARCHAR(255),
  utm_term VARCHAR(255),
  fbclid VARCHAR(255),
  fbc VARCHAR(255),
  fbp VARCHAR(255),
  landing_page TEXT,
  referrer TEXT,
  conversion_event_id UUID REFERENCES conversion_events (id) ON DELETE SET NULL,
  touched_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_lead_touches_lead ON lead_attribution_touches (lead_id, touched_at);
CREATE INDEX ix_lead_touches_client_campaign ON lead_attribution_touches (client_id, campaign_id);

-- First-touch is written once and never overwritten; last-touch moves with each newer touch.
CREATE TABLE lead_attributions (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL UNIQUE REFERENCES leads (id) ON DELETE CASCADE,
  first_touch_id UUID NOT NULL REFERENCES lead_attribution_touches (id),
  last_touch_id UUID NOT NULL REFERENCES lead_attribution_touches (id),
  first_touch_at TIMESTAMPTZ NOT NULL,
  last_touch_at TIMESTAMPTZ NOT NULL,
  touch_count INTEGER NOT NULL DEFAULT 1,
  attribution_source VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_lead_attributions_client ON lead_attributions (client_id, first_touch_at);

-- Backfill: one touch per existing lead from the ad fields it already carries. Nothing is invented:
-- leads without campaign/ad ids are recorded as unknown.
INSERT INTO lead_attribution_touches (id, client_id, lead_id, source, resolution_method, campaign_id, ad_id,
                                      touched_at, created_at)
SELECT gen_random_uuid(), l.client_id, l.id,
       CASE l.source WHEN 'ad_popup' THEN 'ad_popup'
                     WHEN 'ctwa' THEN 'ctwa'
                     WHEN 'whatsapp' THEN CASE WHEN l.referral IS NOT NULL THEN 'whatsapp_referral' ELSE 'whatsapp' END
                     ELSE 'unknown' END,
       CASE WHEN l.ad_id IS NULL AND l.campaign_id IS NULL THEN 'unknown'
            WHEN l.source = 'ctwa' OR l.referral IS NOT NULL THEN 'meta_referral'
            ELSE 'campaign_metadata' END,
       -- WhatsApp referrals store Meta's source_id (an ad id) in campaign_id, so for those the campaign is unknown.
       CASE WHEN l.source = 'ctwa' OR l.referral IS NOT NULL THEN NULL ELSE l.campaign_id END,
       CASE WHEN l.source = 'ctwa' OR l.referral IS NOT NULL THEN coalesce(l.ad_id, l.campaign_id) ELSE l.ad_id END,
       l.created_at, now()
FROM leads l;

INSERT INTO lead_attributions (id, client_id, lead_id, first_touch_id, last_touch_id, first_touch_at,
                               last_touch_at, touch_count, attribution_source, created_at, updated_at)
SELECT gen_random_uuid(), t.client_id, t.lead_id, t.id, t.id, t.touched_at, t.touched_at, 1, t.source, now(), now()
FROM lead_attribution_touches t;
