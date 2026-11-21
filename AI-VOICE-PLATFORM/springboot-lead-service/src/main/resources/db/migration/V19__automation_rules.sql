-- Phase 3, increment 5: automation rules. Rules evaluate stored daily Meta snapshots (and, for delivery issues, Meta's
-- reported status) and then, by level: MONITOR (record only), RECOMMEND (notify), APPROVAL_REQUIRED (prepare a change
-- that a person must approve), PREAPPROVED (apply a narrow, reversible, spend-reducing change; off by default and
-- additionally gated by a server flag).

CREATE TABLE automation_rules (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  name VARCHAR(160) NOT NULL,
  rule_type VARCHAR(24) NOT NULL CHECK (rule_type IN ('spend_over_limit', 'ctr_decline', 'cpl_above_threshold', 'delivery_issue')),
  campaign_id VARCHAR(32),
  threshold NUMERIC(14, 4),
  observation_days INTEGER NOT NULL DEFAULT 7 CHECK (observation_days BETWEEN 1 AND 28),
  min_sample INTEGER NOT NULL DEFAULT 0 CHECK (min_sample >= 0),
  frequency VARCHAR(8) NOT NULL DEFAULT 'daily' CHECK (frequency IN ('hourly', 'daily')),
  severity VARCHAR(8) NOT NULL DEFAULT 'warning' CHECK (severity IN ('info', 'warning', 'critical')),
  notify BOOLEAN NOT NULL DEFAULT TRUE,
  execution_level VARCHAR(20) NOT NULL DEFAULT 'MONITOR' CHECK (execution_level IN ('MONITOR', 'RECOMMEND', 'APPROVAL_REQUIRED', 'PREAPPROVED')),
  -- Only 'pause' can be prepared or pre-approved: it is reversible and stops spend. Budget increases and activation are
  -- never automated.
  proposed_action VARCHAR(10) CHECK (proposed_action IS NULL OR proposed_action IN ('pause')),
  max_auto_actions_per_day INTEGER NOT NULL DEFAULT 1 CHECK (max_auto_actions_per_day BETWEEN 1 AND 5),
  cooldown_hours INTEGER NOT NULL DEFAULT 24 CHECK (cooldown_hours BETWEEN 1 AND 168),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  last_evaluated_at TIMESTAMPTZ,
  created_by VARCHAR(320) NOT NULL,
  updated_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_automation_rules_action CHECK (execution_level NOT IN ('APPROVAL_REQUIRED', 'PREAPPROVED') OR proposed_action IS NOT NULL)
);
CREATE INDEX ix_automation_rules_client ON automation_rules (client_id, enabled);

CREATE TABLE automation_executions (
  id UUID PRIMARY KEY,
  rule_id UUID NOT NULL REFERENCES automation_rules (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  object_id VARCHAR(32),
  object_name VARCHAR(255),
  outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('triggered', 'not_triggered', 'insufficient_data', 'cooldown', 'error')),
  result VARCHAR(20) CHECK (result IS NULL OR result IN ('recorded', 'notified', 'action_prepared', 'action_applied', 'action_blocked')),
  observed JSONB,
  message VARCHAR(2000),
  action_id UUID REFERENCES campaign_actions (id) ON DELETE SET NULL,
  triggered_by VARCHAR(320) NOT NULL,
  evaluated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_automation_executions_rule ON automation_executions (rule_id, object_id, evaluated_at DESC);
CREATE INDEX ix_automation_executions_client ON automation_executions (client_id, evaluated_at DESC);
