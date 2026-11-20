-- Phase 1: Meta Pixel configuration, visitor consent, conversion events (outbox) and Meta CAPI delivery attempts.

CREATE TABLE tracking_pixels (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  meta_pixel_id VARCHAR(32) NOT NULL CHECK (meta_pixel_id ~ '^[0-9]{5,32}$'),
  name VARCHAR(160) NOT NULL,
  -- Comma-separated hostnames allowed to send browser events (e.g. "example.com,www.example.com").
  allowed_domains TEXT NOT NULL DEFAULT '',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  -- Comma-separated Campex event names this pixel accepts and forwards.
  enabled_events TEXT NOT NULL,
  -- Forward Campex internal CRM events (QualifiedLead, SiteVisitCompleted) to Meta as *custom* events.
  forward_custom_events BOOLEAN NOT NULL DEFAULT FALSE,
  consent_mode VARCHAR(16) NOT NULL DEFAULT 'required' CHECK (consent_mode IN ('required', 'not_required')),
  test_event_code VARCHAR(64),
  -- Conversions API access token (Events Manager / system user). AES-256-GCM; never returned to the browser.
  capi_token_ciphertext BYTEA,
  capi_token_nonce BYTEA,
  created_by VARCHAR(320),
  updated_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ,
  CONSTRAINT ck_tracking_pixels_token_pair CHECK ((capi_token_ciphertext IS NULL) = (capi_token_nonce IS NULL)),
  CONSTRAINT uq_tracking_pixels_client_id UNIQUE (client_id, id)
);
CREATE UNIQUE INDEX uq_tracking_pixels_client_meta_pixel
  ON tracking_pixels (client_id, meta_pixel_id) WHERE deleted_at IS NULL;

-- Consent decisions captured by the website SDK (or recorded by staff). Latest row per visitor wins.
CREATE TABLE tracking_consents (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  pixel_id UUID REFERENCES tracking_pixels (id) ON DELETE SET NULL,
  visitor_id VARCHAR(64) NOT NULL,
  analytics_storage VARCHAR(8) NOT NULL CHECK (analytics_storage IN ('granted', 'denied')),
  ad_storage VARCHAR(8) NOT NULL CHECK (ad_storage IN ('granted', 'denied')),
  ad_user_data VARCHAR(8) NOT NULL CHECK (ad_user_data IN ('granted', 'denied')),
  source VARCHAR(16) NOT NULL CHECK (source IN ('banner', 'api', 'crm')),
  policy_version VARCHAR(40),
  recorded_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_tracking_consents_visitor ON tracking_consents (client_id, visitor_id, recorded_at DESC);
CREATE INDEX ix_tracking_consents_created ON tracking_consents (created_at);

CREATE TABLE conversion_events (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  -- External event id shared by browser pixel and server events; Meta deduplicates on (event_name, event_id).
  event_id VARCHAR(100) NOT NULL,
  event_name VARCHAR(50) NOT NULL,
  event_category VARCHAR(20) NOT NULL CHECK (event_category IN ('meta_standard', 'campex_internal')),
  -- Name actually sent to Meta (standard or custom); NULL when the event is never sent.
  meta_event_name VARCHAR(50),
  lead_id UUID REFERENCES leads (id) ON DELETE SET NULL,
  visitor_id VARCHAR(64),
  session_id VARCHAR(64),
  pixel_id UUID REFERENCES tracking_pixels (id) ON DELETE SET NULL,
  campaign_id VARCHAR(128),
  adset_id VARCHAR(128),
  ad_id VARCHAR(128),
  event_source VARCHAR(20) NOT NULL CHECK (event_source IN
    ('website', 'landing_page', 'whatsapp', 'manual_crm', 'backend_api', 'meta_webhook')),
  event_time TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  event_source_url TEXT,
  referrer_url TEXT,
  -- Non-identifying metadata: custom_data, UTM parameters, landing page.
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  -- SHA-256 hashes of normalized identifiers (em, ph, external_id, ...). Raw identifiers are never stored here.
  user_data_hashes JSONB NOT NULL DEFAULT '{}'::jsonb,
  fbc VARCHAR(255),
  fbp VARCHAR(255),
  fbclid VARCHAR(255),
  -- Needed by Meta for website events; purged by the retention job after tracking.pii-retention-days.
  client_ip_address VARCHAR(64),
  client_user_agent VARCHAR(512),
  consent_status VARCHAR(16) NOT NULL CHECK (consent_status IN ('granted', 'denied', 'not_required', 'unknown')),
  meta_delivery_status VARCHAR(24) NOT NULL CHECK (meta_delivery_status IN
    ('not_applicable', 'skipped_no_consent', 'disabled', 'pending', 'sending', 'sent', 'retry', 'failed')),
  delivery_attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  locked_until TIMESTAMPTZ,
  last_error VARCHAR(500),
  sent_at TIMESTAMPTZ,
  dedup_key VARCHAR(200) NOT NULL,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  is_test BOOLEAN NOT NULL DEFAULT FALSE,
  test_event_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_conversion_events_dedup UNIQUE (client_id, dedup_key)
);
CREATE INDEX ix_conversion_events_client_time ON conversion_events (client_id, event_time DESC);
CREATE INDEX ix_conversion_events_dispatch ON conversion_events (meta_delivery_status, next_attempt_at)
  WHERE meta_delivery_status IN ('pending', 'retry', 'sending');
CREATE INDEX ix_conversion_events_lead ON conversion_events (lead_id);
CREATE INDEX ix_conversion_events_visitor ON conversion_events (client_id, visitor_id, event_time DESC);
CREATE INDEX ix_conversion_events_campaign ON conversion_events (client_id, campaign_id);

-- One row per Conversions API attempt for an event: the audit of what Meta answered.
CREATE TABLE meta_event_deliveries (
  id UUID PRIMARY KEY,
  conversion_event_id UUID NOT NULL REFERENCES conversion_events (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  attempt_number INTEGER NOT NULL,
  http_status INTEGER,
  events_received INTEGER,
  fbtrace_id VARCHAR(128),
  error_code INTEGER,
  error_subcode INTEGER,
  error_message VARCHAR(500),
  retryable BOOLEAN NOT NULL,
  outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('sent', 'retry', 'failed')),
  response_metadata JSONB,
  attempted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_meta_event_deliveries_event ON meta_event_deliveries (conversion_event_id, attempt_number);
