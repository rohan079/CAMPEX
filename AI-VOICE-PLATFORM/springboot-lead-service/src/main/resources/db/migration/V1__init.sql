CREATE TABLE leads (
  id UUID PRIMARY KEY,
  phone_number VARCHAR(24) NOT NULL,
  whatsapp_user_id VARCHAR(64),
  first_name VARCHAR(120),
  initial_message TEXT,
  campaign_id VARCHAR(128),
  ad_id VARCHAR(128),
  referral JSONB,
  ad_headline TEXT,
  ad_body TEXT,
  source VARCHAR(32) NOT NULL DEFAULT 'whatsapp',
  status VARCHAR(32) NOT NULL DEFAULT 'awaiting_consent',
  preferred_language VARCHAR(8) NOT NULL DEFAULT 'en',
  outreach_sent_at TIMESTAMPTZ,
  replied_at TIMESTAMPTZ,
  follow_up_start DATE,
  follow_up_end DATE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_leads_phone_number ON leads (phone_number);
CREATE INDEX ix_leads_status ON leads (status);
CREATE INDEX ix_leads_preferred_language ON leads (preferred_language);
CREATE INDEX ix_leads_follow_up ON leads (status, follow_up_start);

CREATE TABLE lead_consents (
  id UUID PRIMARY KEY,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  consent_type VARCHAR(40) NOT NULL,
  purpose VARCHAR(200) NOT NULL,
  evidence_text TEXT NOT NULL,
  source_message_id VARCHAR(128) NOT NULL UNIQUE,
  disclosure_version VARCHAR(40) NOT NULL,
  consented_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_lead_consents_lead_id ON lead_consents (lead_id);

CREATE TABLE whatsapp_events (
  id BIGSERIAL PRIMARY KEY,
  message_id VARCHAR(128) NOT NULL UNIQUE,
  sender VARCHAR(24) NOT NULL,
  message_text TEXT,
  received_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE voice_calls (
  id UUID PRIMARY KEY,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  openai_session_id VARCHAR(128) UNIQUE,
  status VARCHAR(32) NOT NULL DEFAULT 'queued',
  language VARCHAR(8) NOT NULL DEFAULT 'en',
  outcome VARCHAR(80),
  summary TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_voice_calls_lead_id ON voice_calls (lead_id);
CREATE INDEX ix_voice_calls_status ON voice_calls (status, created_at);
CREATE INDEX ix_voice_calls_language ON voice_calls (language);
