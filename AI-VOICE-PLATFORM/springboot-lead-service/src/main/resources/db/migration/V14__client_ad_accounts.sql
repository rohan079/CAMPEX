-- Phase 1: which Meta ad accounts belong to a client, so Meta-reported spend is attributed to the right tenant.
CREATE TABLE client_ad_accounts (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  ad_account_id VARCHAR(40) NOT NULL CHECK (ad_account_id ~ '^act_[0-9]{3,32}$'),
  created_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_client_ad_accounts UNIQUE (client_id, ad_account_id)
);
CREATE INDEX ix_client_ad_accounts_account ON client_ad_accounts (ad_account_id);
