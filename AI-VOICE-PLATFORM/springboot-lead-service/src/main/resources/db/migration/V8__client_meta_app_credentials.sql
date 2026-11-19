-- Per-client Meta Business app credentials so Connect Meta can authorize the client's app
-- instead of only the platform-wide META_APP_ID / META_APP_SECRET.
ALTER TABLE clients
  ADD COLUMN meta_app_id VARCHAR(64),
  ADD COLUMN meta_app_secret_ciphertext BYTEA,
  ADD COLUMN meta_app_secret_nonce BYTEA,
  ADD COLUMN meta_login_config_id VARCHAR(128);

ALTER TABLE clients
  ADD CONSTRAINT clients_meta_secret_pair_chk
  CHECK ((meta_app_secret_ciphertext IS NULL) = (meta_app_secret_nonce IS NULL));

ALTER TABLE meta_oauth_states
  ADD COLUMN client_id UUID;

ALTER TABLE meta_connections
  ADD COLUMN client_id UUID,
  ADD COLUMN meta_app_id VARCHAR(64);
