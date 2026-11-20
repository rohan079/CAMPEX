-- Phase 1: CRM pipeline — assignment, activities, follow-ups, qualification, status history, scoring rules.

CREATE TABLE lead_assignments (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  agent_id UUID NOT NULL,
  previous_agent_id UUID,
  reason VARCHAR(500),
  assigned_by VARCHAR(320),
  assigned_at TIMESTAMPTZ NOT NULL,
  unassigned_at TIMESTAMPTZ,
  CONSTRAINT fk_lead_assignments_agent FOREIGN KEY (client_id, agent_id) REFERENCES sales_agents (client_id, id)
);
CREATE INDEX ix_lead_assignments_lead ON lead_assignments (lead_id, assigned_at);
CREATE INDEX ix_lead_assignments_agent ON lead_assignments (client_id, agent_id);

CREATE TABLE lead_activities (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  activity_type VARCHAR(24) NOT NULL CHECK (activity_type IN ('note', 'call', 'whatsapp', 'email', 'meeting',
    'status_change', 'assignment', 'follow_up', 'site_visit', 'qualification', 'sale', 'reminder', 'system')),
  body TEXT,
  metadata JSONB,
  agent_id UUID,
  actor_email VARCHAR(320),
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_lead_activities_lead ON lead_activities (lead_id, occurred_at DESC);

CREATE TABLE lead_follow_ups (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  agent_id UUID,
  due_at TIMESTAMPTZ NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'call' CHECK (channel IN ('call', 'whatsapp', 'email', 'meeting', 'other')),
  note VARCHAR(1000),
  status VARCHAR(12) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'cancelled')),
  completed_at TIMESTAMPTZ,
  reminder_sent_at TIMESTAMPTZ,
  created_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_lead_follow_ups_agent FOREIGN KEY (client_id, agent_id) REFERENCES sales_agents (client_id, id)
);
CREATE INDEX ix_lead_follow_ups_due ON lead_follow_ups (client_id, status, due_at);
CREATE INDEX ix_lead_follow_ups_lead ON lead_follow_ups (lead_id);

CREATE TABLE lead_qualifications (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL UNIQUE REFERENCES leads (id) ON DELETE CASCADE,
  budget_min NUMERIC(14, 2) CHECK (budget_min IS NULL OR budget_min >= 0),
  budget_max NUMERIC(14, 2) CHECK (budget_max IS NULL OR budget_max >= 0),
  preferred_location VARCHAR(200),
  property_type VARCHAR(80),
  purchase_timeline VARCHAR(20) CHECK (purchase_timeline IN
    ('IMMEDIATE', 'WITHIN_3_MONTHS', 'WITHIN_6_MONTHS', 'WITHIN_12_MONTHS', 'LATER', 'UNKNOWN')),
  bedrooms SMALLINT CHECK (bedrooms IS NULL OR bedrooms BETWEEN 0 AND 20),
  financing_preference VARCHAR(20) CHECK (financing_preference IN
    ('SELF_FUNDED', 'HOME_LOAN', 'LOAN_PREAPPROVED', 'UNDECIDED')),
  purpose VARCHAR(16) CHECK (purpose IN ('INVESTMENT', 'RESIDENTIAL', 'BOTH', 'UNKNOWN')),
  genuine_interest BOOLEAN,
  qualification_score INTEGER NOT NULL DEFAULT 0 CHECK (qualification_score BETWEEN 0 AND 100),
  score_breakdown JSONB NOT NULL DEFAULT '[]'::jsonb,
  qualification_reason VARCHAR(1000),
  qualified_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_lead_qualifications_budget CHECK (budget_min IS NULL OR budget_max IS NULL OR budget_min <= budget_max)
);

CREATE TABLE lead_status_history (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  reason VARCHAR(500),
  changed_by VARCHAR(320),
  changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_lead_status_history_lead ON lead_status_history (lead_id, changed_at);
CREATE INDEX ix_lead_status_history_client ON lead_status_history (client_id, to_status, changed_at);

-- Per-client weights for the explainable lead score. Absent row = documented defaults.
CREATE TABLE lead_scoring_configs (
  client_id UUID PRIMARY KEY REFERENCES clients (id) ON DELETE CASCADE,
  rules JSONB NOT NULL,
  updated_by VARCHAR(320),
  updated_at TIMESTAMPTZ NOT NULL
);
