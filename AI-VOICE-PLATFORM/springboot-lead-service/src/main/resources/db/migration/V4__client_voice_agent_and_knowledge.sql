-- Existing clients keep their previously configured call behavior. New client creation is opt-in in the UI.
ALTER TABLE clients
  ADD COLUMN voice_agent_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN knowledge_base TEXT NOT NULL DEFAULT '';
