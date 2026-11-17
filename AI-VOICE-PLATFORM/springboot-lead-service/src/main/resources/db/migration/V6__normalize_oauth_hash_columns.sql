ALTER TABLE campex_admin_sessions
  ALTER COLUMN token_hash TYPE VARCHAR(64) USING btrim(token_hash);

ALTER TABLE meta_oauth_states
  ALTER COLUMN state_hash TYPE VARCHAR(64) USING btrim(state_hash);
