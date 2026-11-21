-- Phase 3, increment 6: experiment execution. Variants can carry an approved creative; launching an experiment and
-- acting on its result go through Optimization actions (approved, applied once, verified), linked here.

ALTER TABLE experiment_variants ADD COLUMN creative_asset_id UUID REFERENCES creative_assets (id) ON DELETE SET NULL;
ALTER TABLE experiment_variants ADD COLUMN creative_version INTEGER;

CREATE TABLE experiment_executions (
  id UUID PRIMARY KEY,
  experiment_id UUID NOT NULL REFERENCES campaign_experiments (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  variant_id UUID REFERENCES experiment_variants (id) ON DELETE CASCADE,
  purpose VARCHAR(12) NOT NULL CHECK (purpose IN ('activate', 'pause_loser')),
  action_id UUID NOT NULL REFERENCES campaign_actions (id) ON DELETE CASCADE,
  created_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_experiment_executions_experiment ON experiment_executions (experiment_id, created_at);
