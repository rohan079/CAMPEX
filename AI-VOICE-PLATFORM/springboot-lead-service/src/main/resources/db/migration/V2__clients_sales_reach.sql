-- Multi-client support: every lead belongs to a client (an advertiser we run marketing for).
CREATE TABLE clients (
  id UUID PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  slug VARCHAR(80) NOT NULL UNIQUE,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL
);

-- Existing leads (from before clients existed) belong to this client.
-- Its name and description come from BUSINESS_NAME / BUSINESS_DESCRIPTION.
INSERT INTO clients (id, name, slug, description, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default client', 'default', NULL, now());

ALTER TABLE leads ADD COLUMN client_id UUID REFERENCES clients (id);
UPDATE leads SET client_id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE leads ALTER COLUMN client_id SET NOT NULL;
CREATE INDEX ix_leads_client_created ON leads (client_id, created_at);

-- Maps a Meta ad id to the client that owns it, so click-to-WhatsApp leads land on the right client.
CREATE TABLE ads (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  external_id VARCHAR(128) NOT NULL UNIQUE,
  name VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_ads_client ON ads (client_id);

-- A sale made by one of two channels: straight from the ad, or closed by the calling agent.
-- lead_id is SET NULL on lead deletion so revenue history survives an opt-out.
CREATE TABLE sales (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID REFERENCES leads (id) ON DELETE SET NULL,
  ad_id VARCHAR(128),
  channel VARCHAR(20) NOT NULL CHECK (channel IN ('ad', 'calling_agent')),
  amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
  note VARCHAR(500),
  sold_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_sales_client_sold ON sales (client_id, sold_at);

-- How many people an ad reached in a location on a day. Imported (for example from Meta Ads Manager).
CREATE TABLE ad_reach (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  ad_id VARCHAR(128) NOT NULL,
  ad_name VARCHAR(200),
  location VARCHAR(120) NOT NULL,
  reach INTEGER NOT NULL CHECK (reach >= 0),
  impressions INTEGER CHECK (impressions >= 0),
  day DATE NOT NULL,
  UNIQUE (client_id, ad_id, location, day)
);
CREATE INDEX ix_ad_reach_client_day ON ad_reach (client_id, day);
