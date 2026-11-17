CREATE TABLE campex_admin_sessions (
  id UUID PRIMARY KEY,
  token_hash CHAR(64) NOT NULL UNIQUE,
  owner_email VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_campex_admin_sessions_expiry ON campex_admin_sessions (expires_at);

CREATE TABLE meta_oauth_states (
  state_hash CHAR(64) PRIMARY KEY,
  owner_email VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_meta_oauth_states_expiry ON meta_oauth_states (expires_at);

CREATE TABLE meta_connections (
  id UUID PRIMARY KEY,
  owner_email VARCHAR(320) NOT NULL UNIQUE,
  meta_user_id VARCHAR(128) NOT NULL,
  meta_user_name VARCHAR(255) NOT NULL,
  access_token_ciphertext BYTEA NOT NULL,
  access_token_nonce BYTEA NOT NULL,
  token_expires_at TIMESTAMPTZ,
  data_access_expires_at TIMESTAMPTZ,
  granted_scopes TEXT NOT NULL,
  connected_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE meta_connected_assets (
  id UUID PRIMARY KEY,
  connection_id UUID NOT NULL REFERENCES meta_connections(id) ON DELETE CASCADE,
  asset_type VARCHAR(24) NOT NULL CHECK (asset_type IN ('page', 'ad_account')),
  external_id VARCHAR(128) NOT NULL,
  name VARCHAR(255) NOT NULL,
  account_id VARCHAR(128),
  category VARCHAR(255),
  account_status INTEGER,
  currency VARCHAR(8),
  page_token_ciphertext BYTEA,
  page_token_nonce BYTEA,
  UNIQUE (connection_id, asset_type, external_id),
  CHECK ((page_token_ciphertext IS NULL) = (page_token_nonce IS NULL))
);
CREATE INDEX ix_meta_connected_assets_connection ON meta_connected_assets (connection_id, asset_type, name);
