-- Several ads in one ad set: each attached creative becomes its own paused ad, tracked separately so a retry
-- continues per ad and never uploads or creates anything twice. campaign_executions.ad_status stays as the summary.
CREATE TABLE campaign_execution_ads (
  id UUID PRIMARY KEY,
  execution_id UUID NOT NULL REFERENCES campaign_executions (id) ON DELETE CASCADE,
  position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 5),
  creative_asset_id UUID NOT NULL,
  creative_version INTEGER NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'uploading', 'processing', 'creating', 'created', 'failed')),
  meta_image_hash VARCHAR(128),
  meta_video_id VARCHAR(64),
  meta_creative_id VARCHAR(64),
  meta_ad_id VARCHAR(64),
  error TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_check_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_campaign_execution_ads_position UNIQUE (execution_id, position)
);
CREATE INDEX ix_campaign_execution_ads_active ON campaign_execution_ads (status, next_check_at)
  WHERE status IN ('pending', 'uploading', 'processing', 'creating');

-- Ads already in progress or done move over as the first ad of their campaign.
INSERT INTO campaign_execution_ads (id, execution_id, position, creative_asset_id, creative_version, status, meta_image_hash,
  meta_video_id, meta_creative_id, meta_ad_id, error, attempts, next_check_at, created_at, updated_at)
SELECT gen_random_uuid(), e.id, 0, (e.config ->> 'creative_asset_id')::uuid, (e.config ->> 'creative_version')::int, e.ad_status,
  e.meta_image_hash, e.meta_video_id, e.meta_creative_id, CASE WHEN e.ad_status = 'created' THEN e.meta_ad_id END, e.ad_error,
  e.ad_attempts, e.ad_next_check_at, e.updated_at, e.updated_at
FROM campaign_executions e
WHERE e.ad_status IN ('pending', 'uploading', 'processing', 'creating', 'created', 'failed')
  AND e.config ->> 'creative_asset_id' IS NOT NULL AND e.config ->> 'creative_version' IS NOT NULL;
