-- Phase 1 / Measurement foundation: tenant access, sales agents, property projects, generic audit trail.

-- Who may access a client's data. Platform admins (CAMPEX_PLATFORM_ADMIN_EMAILS) bypass this table.
CREATE TABLE client_members (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  user_email VARCHAR(320) NOT NULL,
  role VARCHAR(16) NOT NULL CHECK (role IN ('owner', 'manager', 'agent', 'viewer')),
  created_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_client_members_client_email UNIQUE (client_id, user_email)
);
CREATE INDEX ix_client_members_email ON client_members (user_email);

-- People who work leads for a client. An agent may or may not also be a dashboard user (email).
CREATE TABLE sales_agents (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  name VARCHAR(160) NOT NULL,
  email VARCHAR(320),
  phone_number VARCHAR(24),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  -- Lets child tables enforce "the agent belongs to the same client" with a composite foreign key.
  CONSTRAINT uq_sales_agents_client_id UNIQUE (client_id, id)
);
CREATE UNIQUE INDEX uq_sales_agents_client_email ON sales_agents (client_id, lower(email)) WHERE email IS NOT NULL;

-- Real-estate projects/properties a client sells. Pricing and location feed explainable lead scoring.
CREATE TABLE client_projects (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  name VARCHAR(200) NOT NULL,
  location VARCHAR(200),
  property_types VARCHAR(200),
  min_price NUMERIC(14, 2) CHECK (min_price IS NULL OR min_price >= 0),
  max_price NUMERIC(14, 2) CHECK (max_price IS NULL OR max_price >= 0),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_client_projects_price_range CHECK (min_price IS NULL OR max_price IS NULL OR min_price <= max_price),
  CONSTRAINT uq_client_projects_client_id UNIQUE (client_id, id)
);
CREATE UNIQUE INDEX uq_client_projects_client_name ON client_projects (client_id, lower(name));

-- Append-only audit trail for critical configuration changes (tracking pixels, scoring rules, membership).
CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  client_id UUID REFERENCES clients (id) ON DELETE CASCADE,
  actor_email VARCHAR(320),
  entity_type VARCHAR(40) NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  action VARCHAR(40) NOT NULL,
  changes JSONB,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_events_client_created ON audit_events (client_id, created_at);
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id);
