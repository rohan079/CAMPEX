-- Phase 2: performance intelligence. Reuses clients, client_ad_accounts, leads/CRM, conversion_events and
-- audit_events (decision audit trail) from Phase 1.

-- Daily Meta-reported delivery per campaign/ad set/ad. Needed for trends, anomaly baselines and fatigue; period
-- totals (reach, frequency) are always fetched from Meta directly because reach is not additive across days.
CREATE TABLE performance_snapshots (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  ad_account_id VARCHAR(40) NOT NULL,
  level VARCHAR(10) NOT NULL CHECK (level IN ('campaign', 'adset', 'ad')),
  object_id VARCHAR(64) NOT NULL,
  campaign_id VARCHAR(64),
  adset_id VARCHAR(64),
  name VARCHAR(255),
  day DATE NOT NULL,
  spend NUMERIC(14, 2) NOT NULL DEFAULT 0,
  impressions BIGINT NOT NULL DEFAULT 0,
  reach BIGINT,
  frequency NUMERIC(10, 4),
  clicks BIGINT NOT NULL DEFAULT 0,
  link_clicks BIGINT NOT NULL DEFAULT 0,
  meta_leads BIGINT NOT NULL DEFAULT 0,
  currency VARCHAR(8),
  attribution_setting VARCHAR(40) NOT NULL,
  fetched_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_performance_snapshots UNIQUE (client_id, level, object_id, day)
);
CREATE INDEX ix_performance_snapshots_client_day ON performance_snapshots (client_id, level, day);
CREATE INDEX ix_performance_snapshots_campaign ON performance_snapshots (client_id, campaign_id, day);

-- Client-defined spending guardrails for budget recommendations (Phase 2 never changes Meta budgets itself).
CREATE TABLE client_budget_policies (
  client_id UUID PRIMARY KEY REFERENCES clients (id) ON DELETE CASCADE,
  daily_spend_limit NUMERIC(14, 2) CHECK (daily_spend_limit IS NULL OR daily_spend_limit > 0),
  monthly_spend_limit NUMERIC(14, 2) CHECK (monthly_spend_limit IS NULL OR monthly_spend_limit > 0),
  max_change_pct INTEGER NOT NULL DEFAULT 20 CHECK (max_change_pct BETWEEN 1 AND 100),
  min_qualified_leads INTEGER NOT NULL DEFAULT 5 CHECK (min_qualified_leads >= 1),
  target_cost_per_qualified_lead NUMERIC(14, 2),
  target_roas NUMERIC(10, 4),
  recommendation_ttl_hours INTEGER NOT NULL DEFAULT 72 CHECK (recommendation_ttl_hours BETWEEN 1 AND 720),
  updated_by VARCHAR(320),
  updated_at TIMESTAMPTZ NOT NULL
);

-- Evidence-based recommendations. Every numeric claim lives in recommendation_evidence with its provenance.
CREATE TABLE performance_recommendations (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  category VARCHAR(16) NOT NULL CHECK (category IN ('optimization', 'budget', 'creative', 'audience', 'anomaly', 'experiment')),
  recommendation_type VARCHAR(40) NOT NULL,
  campaign_id VARCHAR(64),
  adset_id VARCHAR(64),
  ad_id VARCHAR(64),
  object_name VARCHAR(255),
  title VARCHAR(300) NOT NULL,
  current_performance TEXT NOT NULL,
  reasoning TEXT NOT NULL,
  expected_impact TEXT NOT NULL,
  impact_is_estimate BOOLEAN NOT NULL DEFAULT TRUE,
  confidence VARCHAR(8) NOT NULL CHECK (confidence IN ('low', 'medium', 'high')),
  risk_level VARCHAR(8) NOT NULL CHECK (risk_level IN ('low', 'medium', 'high')),
  recommended_action TEXT NOT NULL,
  proposed_change JSONB,
  ai_explanation TEXT,
  ai_provider VARCHAR(40),
  ai_model VARCHAR(80),
  status VARCHAR(12) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'accepted', 'rejected', 'reviewed', 'expired')),
  dedup_key VARCHAR(200) NOT NULL,
  analysis_from DATE NOT NULL,
  analysis_to DATE NOT NULL,
  calculated_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  decided_by VARCHAR(320),
  decided_at TIMESTAMPTZ,
  decision_note VARCHAR(1000)
);
-- At most one open recommendation per finding: duplicates are prevented at the database level.
CREATE UNIQUE INDEX uq_performance_recommendations_open
  ON performance_recommendations (client_id, dedup_key) WHERE status = 'pending';
CREATE INDEX ix_performance_recommendations_client ON performance_recommendations (client_id, status, created_at DESC);

CREATE TABLE recommendation_evidence (
  id UUID PRIMARY KEY,
  recommendation_id UUID NOT NULL REFERENCES performance_recommendations (id) ON DELETE CASCADE,
  metric VARCHAR(60) NOT NULL,
  label VARCHAR(120) NOT NULL,
  value NUMERIC(20, 6),
  unit VARCHAR(16) NOT NULL,
  source VARCHAR(20) NOT NULL CHECK (source IN
    ('meta_reported', 'campex_tracked', 'crm_verified', 'manual_financial', 'derived', 'estimated')),
  scope VARCHAR(80) NOT NULL,
  period_from DATE NOT NULL,
  period_to DATE NOT NULL
);
CREATE INDEX ix_recommendation_evidence_rec ON recommendation_evidence (recommendation_id);

-- Deterministically detected anomalies (AI only explains them).
CREATE TABLE campaign_anomalies (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  campaign_id VARCHAR(64),
  campaign_name VARCHAR(255),
  metric VARCHAR(40) NOT NULL,
  severity VARCHAR(8) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  previous_value NUMERIC(20, 6),
  current_value NUMERIC(20, 6),
  pct_change NUMERIC(12, 4),
  z_score NUMERIC(12, 4),
  baseline_from DATE,
  baseline_to DATE,
  current_from DATE,
  current_to DATE,
  detection_reason TEXT NOT NULL,
  suggested_action TEXT NOT NULL,
  ai_explanation TEXT,
  status VARCHAR(12) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged', 'resolved', 'dismissed')),
  dedup_key VARCHAR(200) NOT NULL,
  occurrences INTEGER NOT NULL DEFAULT 1,
  detected_at TIMESTAMPTZ NOT NULL,
  last_detected_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ,
  resolved_by VARCHAR(320),
  cooldown_until TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_campaign_anomalies_open
  ON campaign_anomalies (client_id, dedup_key) WHERE status IN ('open', 'acknowledged');
CREATE INDEX ix_campaign_anomalies_client ON campaign_anomalies (client_id, status, detected_at DESC);

-- AI creative analyses: observation from deterministic metrics + generated drafts. Never published automatically.
CREATE TABLE creative_analyses (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  ad_id VARCHAR(64) NOT NULL,
  campaign_id VARCHAR(64),
  observation TEXT NOT NULL,
  input_metrics JSONB NOT NULL,
  suggestions JSONB NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'used', 'discarded')),
  ai_provider VARCHAR(40),
  ai_model VARCHAR(80),
  created_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_creative_analyses_client ON creative_analyses (client_id, created_at DESC);

CREATE TABLE campaign_experiments (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  campaign_id VARCHAR(64),
  name VARCHAR(200) NOT NULL,
  hypothesis TEXT NOT NULL,
  variable VARCHAR(20) NOT NULL CHECK (variable IN ('image', 'headline', 'primary_text', 'cta', 'landing_page', 'audience')),
  primary_metric VARCHAR(30) NOT NULL CHECK (primary_metric IN ('ctr', 'cpc', 'cost_per_lead',
    'cost_per_qualified_lead', 'conversion_rate', 'cost_per_site_visit', 'cost_per_booking')),
  min_sample_size INTEGER NOT NULL CHECK (min_sample_size >= 1),
  confidence_level NUMERIC(4, 3) NOT NULL DEFAULT 0.95 CHECK (confidence_level IN (0.90, 0.95, 0.99)),
  status VARCHAR(12) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'running', 'completed', 'cancelled')),
  start_date DATE,
  end_date DATE,
  outcome VARCHAR(24) CHECK (outcome IN ('winner', 'no_reliable_winner', 'inconclusive')),
  winner_variant_id UUID,
  result JSONB,
  result_summary TEXT,
  created_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  CONSTRAINT ck_campaign_experiments_dates CHECK (start_date IS NULL OR end_date IS NULL OR start_date <= end_date)
);
CREATE INDEX ix_campaign_experiments_client ON campaign_experiments (client_id, status);

CREATE TABLE experiment_variants (
  id UUID PRIMARY KEY,
  experiment_id UUID NOT NULL REFERENCES campaign_experiments (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  role VARCHAR(8) NOT NULL CHECK (role IN ('control', 'test')),
  name VARCHAR(160) NOT NULL,
  ad_id VARCHAR(64),
  adset_id VARCHAR(64),
  description TEXT,
  creative_analysis_id UUID REFERENCES creative_analyses (id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_experiment_variants_target CHECK (ad_id IS NOT NULL OR adset_id IS NOT NULL),
  CONSTRAINT uq_experiment_variants_role UNIQUE (experiment_id, role)
);
