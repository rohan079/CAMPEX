ALTER TABLE leads
  ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'pending'
  CHECK (review_status IN ('pending', 'accepted', 'rejected'));

CREATE INDEX ix_leads_client_review_status ON leads (client_id, review_status);
