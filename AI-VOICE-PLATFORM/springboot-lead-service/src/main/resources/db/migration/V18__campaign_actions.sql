-- Phase 3, increment 4: controlled optimization actions. A change to a live Meta object is previewed (before-state
-- read from Meta, proposed after-state, deterministic guardrails), approved by a person, executed by Spring, then
-- verified by reading Meta again. Before, proposed and actual states are all kept.

CREATE TABLE campaign_actions (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  recommendation_id UUID REFERENCES performance_recommendations (id) ON DELETE SET NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'recommendation', 'automation')),
  action_type VARCHAR(20) NOT NULL CHECK (action_type IN ('pause', 'resume', 'set_daily_budget')),
  level VARCHAR(10) NOT NULL CHECK (level IN ('campaign', 'adset', 'ad')),
  object_id VARCHAR(32) NOT NULL,
  object_name VARCHAR(255),
  ad_account_id VARCHAR(40) NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'APPROVED', 'EXECUTING', 'SUCCEEDED',
    'FAILED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
  before_state JSONB NOT NULL,
  proposed_after JSONB NOT NULL,
  actual_after JSONB,
  verification VARCHAR(12) CHECK (verification IS NULL OR verification IN ('verified', 'mismatch', 'unverified')),
  guardrails JSONB NOT NULL,
  reason VARCHAR(2000),
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_by VARCHAR(320) NOT NULL,
  approved_by VARCHAR(320),
  approved_at TIMESTAMPTZ,
  approval_note VARCHAR(1000),
  executed_by VARCHAR(320),
  executed_at TIMESTAMPTZ,
  verified_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_campaign_actions_client ON campaign_actions (client_id, status, created_at DESC);
-- One open change per Meta object, so two previews cannot race each other.
CREATE UNIQUE INDEX uq_campaign_actions_open_object ON campaign_actions (client_id, level, object_id)
  WHERE status IN ('PROPOSED', 'APPROVED', 'EXECUTING');

CREATE TABLE campaign_action_events (
  id UUID PRIMARY KEY,
  action_id UUID NOT NULL REFERENCES campaign_actions (id) ON DELETE CASCADE,
  event VARCHAR(20) NOT NULL,
  outcome VARCHAR(10) NOT NULL CHECK (outcome IN ('ok', 'failed', 'unknown', 'blocked')),
  detail TEXT,
  actor_email VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_campaign_action_events_action ON campaign_action_events (action_id, created_at);
