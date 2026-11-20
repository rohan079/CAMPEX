-- Phase 1: site visits and the lead-to-sale lifecycle with an audit trail.

CREATE TABLE site_visits (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  project_id UUID,
  agent_id UUID,
  scheduled_at TIMESTAMPTZ NOT NULL,
  duration_minutes INTEGER NOT NULL DEFAULT 60 CHECK (duration_minutes BETWEEN 15 AND 480),
  status VARCHAR(16) NOT NULL CHECK (status IN
    ('SCHEDULED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'RESCHEDULED')),
  customer_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  customer_confirmed_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  outcome VARCHAR(24) CHECK (outcome IN
    ('interested', 'not_interested', 'negotiation', 'booked', 'follow_up_needed')),
  follow_up_notes TEXT,
  cancellation_reason VARCHAR(500),
  rescheduled_from_id UUID REFERENCES site_visits (id),
  reminder_at TIMESTAMPTZ,
  reminder_sent_at TIMESTAMPTZ,
  created_by VARCHAR(320),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_site_visits_project FOREIGN KEY (client_id, project_id) REFERENCES client_projects (client_id, id),
  CONSTRAINT fk_site_visits_agent FOREIGN KEY (client_id, agent_id) REFERENCES sales_agents (client_id, id)
);
CREATE INDEX ix_site_visits_client_scheduled ON site_visits (client_id, scheduled_at);
CREATE INDEX ix_site_visits_lead ON site_visits (lead_id);
CREATE INDEX ix_site_visits_reminders ON site_visits (reminder_at) WHERE reminder_sent_at IS NULL;

-- Sales: keep `amount` (= booking value) so the existing analytics keep working, and add the lifecycle.
ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_channel_check;
ALTER TABLE sales
  ADD CONSTRAINT ck_sales_channel CHECK (channel IN ('ad', 'calling_agent', 'sales_team', 'referral', 'walk_in', 'other')),
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'BOOKING_PENDING', 'BOOKED',
    'PAYMENT_PENDING', 'PARTIALLY_PAID', 'FULLY_PAID', 'CANCELLED', 'REFUNDED')),
  ADD COLUMN booking_value NUMERIC(14, 2),
  ADD COLUMN amount_collected NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (amount_collected >= 0),
  ADD COLUMN refunded_amount NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
  ADD COLUMN outstanding_amount NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (outstanding_amount >= 0),
  ADD COLUMN confirmed_revenue NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (confirmed_revenue >= 0),
  ADD COLUMN campaign_id VARCHAR(128),
  ADD COLUMN adset_id VARCHAR(128),
  ADD COLUMN project_id UUID,
  ADD COLUMN agent_id UUID,
  ADD COLUMN booking_date DATE,
  ADD COLUMN sale_confirmed_at TIMESTAMPTZ,
  ADD COLUMN confirmed_by VARCHAR(320),
  ADD COLUMN cancellation_reason VARCHAR(500),
  ADD COLUMN legacy_record BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN created_by VARCHAR(320),
  ADD COLUMN updated_at TIMESTAMPTZ,
  ADD COLUMN deleted_at TIMESTAMPTZ,
  ADD CONSTRAINT fk_sales_project FOREIGN KEY (client_id, project_id) REFERENCES client_projects (client_id, id),
  ADD CONSTRAINT fk_sales_agent FOREIGN KEY (client_id, agent_id) REFERENCES sales_agents (client_id, id);

-- Existing sales were typed in by hand without payment detail. Keep their amount as the booking value but do
-- NOT count them as confirmed revenue until someone confirms them.
UPDATE sales s
SET status = 'BOOKED',
    booking_value = s.amount,
    outstanding_amount = s.amount,
    booking_date = cast(s.sold_at AT TIME ZONE 'UTC' AS date),
    legacy_record = TRUE,
    updated_at = s.created_at,
    -- Campaign from the lead's resolved first touch (never the raw leads.campaign_id, which can hold an ad id).
    campaign_id = (SELECT t.campaign_id FROM lead_attributions a
                   JOIN lead_attribution_touches t ON t.id = a.first_touch_id
                   WHERE a.lead_id = s.lead_id),
    adset_id = (SELECT t.adset_id FROM lead_attributions a
                JOIN lead_attribution_touches t ON t.id = a.first_touch_id
                WHERE a.lead_id = s.lead_id);

ALTER TABLE sales ALTER COLUMN booking_value SET NOT NULL;
ALTER TABLE sales ADD CONSTRAINT ck_sales_booking_value CHECK (booking_value > 0);
ALTER TABLE sales ALTER COLUMN updated_at SET NOT NULL;
CREATE INDEX ix_sales_client_status ON sales (client_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ix_sales_client_campaign ON sales (client_id, campaign_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_sales_lead ON sales (lead_id);

CREATE TABLE sale_status_history (
  id UUID PRIMARY KEY,
  sale_id UUID NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  action VARCHAR(24) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20),
  changes JSONB,
  reason VARCHAR(500),
  actor_email VARCHAR(320),
  changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_sale_status_history_sale ON sale_status_history (sale_id, changed_at);
