-- Phase 3, increment 1: controlled campaign execution. A campaign is drafted, validated, approved against an exact
-- configuration hash, then published (always PAUSED) by Spring. Local workflow status and Meta-reported status are
-- stored separately; a successful API call is never taken to mean "active".

CREATE TABLE campaign_executions (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  ad_account_id VARCHAR(40) NOT NULL,
  name VARCHAR(160) NOT NULL,
  objective VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'READY_FOR_REVIEW', 'PENDING_APPROVAL',
    'APPROVED', 'EXECUTING', 'PUBLISHED_PAUSED', 'ACTIVE', 'FAILED', 'CANCELLED')),
  config JSONB NOT NULL,
  config_version INTEGER NOT NULL DEFAULT 1,
  config_hash VARCHAR(64) NOT NULL,
  last_validation JSONB,
  idempotency_key VARCHAR(100),
  -- Embedded in the Meta campaign name so a timed-out publish can be found again instead of re-created.
  publish_tag VARCHAR(20) NOT NULL UNIQUE,
  publish_attempts INTEGER NOT NULL DEFAULT 0,
  publish_outcome VARCHAR(12) CHECK (publish_outcome IS NULL OR publish_outcome IN ('created', 'partial', 'rejected', 'unknown', 'not_found')),
  meta_campaign_id VARCHAR(64),
  meta_adset_id VARCHAR(64),
  meta_ad_id VARCHAR(64),
  meta_status VARCHAR(30),
  meta_effective_status VARCHAR(40),
  meta_checked_at TIMESTAMPTZ,
  last_error TEXT,
  created_by VARCHAR(320) NOT NULL,
  updated_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  submitted_at TIMESTAMPTZ,
  published_at TIMESTAMPTZ,
  CONSTRAINT uq_campaign_executions_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE INDEX ix_campaign_executions_client ON campaign_executions (client_id, status, updated_at DESC);
CREATE UNIQUE INDEX uq_campaign_executions_meta_campaign ON campaign_executions (meta_campaign_id)
  WHERE meta_campaign_id IS NOT NULL;

-- Every saved configuration, so an approval always points at the exact content that was approved.
CREATE TABLE campaign_execution_versions (
  id UUID PRIMARY KEY,
  execution_id UUID NOT NULL REFERENCES campaign_executions (id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  config JSONB NOT NULL,
  config_hash VARCHAR(64) NOT NULL,
  created_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_campaign_execution_versions UNIQUE (execution_id, version)
);

CREATE TABLE campaign_approvals (
  id UUID PRIMARY KEY,
  execution_id UUID NOT NULL REFERENCES campaign_executions (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  config_version INTEGER NOT NULL,
  config_hash VARCHAR(64) NOT NULL,
  approved_daily_budget_minor BIGINT,
  decision VARCHAR(10) NOT NULL CHECK (decision IN ('approved', 'rejected')),
  decided_by VARCHAR(320) NOT NULL,
  decided_at TIMESTAMPTZ NOT NULL,
  note VARCHAR(1000),
  -- Set when the configuration changes after the decision; an invalidated approval never authorises a publish.
  invalidated_at TIMESTAMPTZ
);
CREATE INDEX ix_campaign_approvals_execution ON campaign_approvals (execution_id, decided_at DESC);

-- Attempt log: what was tried, by whom, and what Meta returned (IDs only; never tokens).
CREATE TABLE campaign_execution_steps (
  id UUID PRIMARY KEY,
  execution_id UUID NOT NULL REFERENCES campaign_executions (id) ON DELETE CASCADE,
  step VARCHAR(20) NOT NULL CHECK (step IN ('create', 'edit', 'validate', 'submit', 'approve', 'reject', 'publish',
    'reconcile', 'retry', 'cancel')),
  outcome VARCHAR(10) NOT NULL CHECK (outcome IN ('ok', 'failed', 'unknown', 'blocked')),
  detail TEXT,
  meta_ids JSONB,
  actor_email VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_campaign_execution_steps_execution ON campaign_execution_steps (execution_id, created_at);

-- In-app notifications, one row per recipient. channel is kept for future email/WhatsApp delivery.
CREATE TABLE notifications (
  id UUID PRIMARY KEY,
  client_id UUID REFERENCES clients (id) ON DELETE CASCADE,
  recipient_email VARCHAR(320) NOT NULL,
  type VARCHAR(40) NOT NULL,
  severity VARCHAR(8) NOT NULL DEFAULT 'info' CHECK (severity IN ('info', 'warning', 'critical')),
  title VARCHAR(200) NOT NULL,
  body VARCHAR(1000),
  entity_type VARCHAR(40),
  entity_id VARCHAR(64),
  channel VARCHAR(12) NOT NULL DEFAULT 'in_app' CHECK (channel IN ('in_app', 'email', 'whatsapp')),
  created_at TIMESTAMPTZ NOT NULL,
  read_at TIMESTAMPTZ
);
CREATE INDEX ix_notifications_recipient ON notifications (recipient_email, read_at, created_at DESC);
