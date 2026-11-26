-- Video creatives and Meta ad creation. Video files live on disk (campex.media.dir), referenced here; a version
-- of a video creative points at its video and at a thumbnail image (creative_images), which Meta requires.
-- After a campaign is published (paused), Campex creates the ad itself: upload media → ad creative → ad (PAUSED).

CREATE TABLE creative_media (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  kind VARCHAR(8) NOT NULL CHECK (kind IN ('video')),
  storage VARCHAR(12) NOT NULL CHECK (storage IN ('filesystem')),
  storage_key VARCHAR(300) NOT NULL UNIQUE,
  mime_type VARCHAR(20) NOT NULL CHECK (mime_type IN ('video/mp4', 'video/quicktime')),
  size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
  sha256 VARCHAR(64) NOT NULL,
  duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
  original_name VARCHAR(255),
  created_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_creative_media_client ON creative_media (client_id);

ALTER TABLE creative_assets ADD COLUMN media_type VARCHAR(8) NOT NULL DEFAULT 'image' CHECK (media_type IN ('image', 'video'));
ALTER TABLE creative_versions ADD COLUMN video_id UUID REFERENCES creative_media (id);

ALTER TABLE campaign_executions ADD COLUMN ad_status VARCHAR(16) NOT NULL DEFAULT 'not_requested'
  CHECK (ad_status IN ('not_requested', 'pending', 'uploading', 'processing', 'creating', 'created', 'failed', 'skipped'));
ALTER TABLE campaign_executions ADD COLUMN meta_video_id VARCHAR(64);
ALTER TABLE campaign_executions ADD COLUMN meta_image_hash VARCHAR(128);
ALTER TABLE campaign_executions ADD COLUMN meta_creative_id VARCHAR(64);
ALTER TABLE campaign_executions ADD COLUMN ad_error TEXT;
ALTER TABLE campaign_executions ADD COLUMN ad_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE campaign_executions ADD COLUMN ad_next_check_at TIMESTAMPTZ;
CREATE INDEX ix_campaign_executions_ad_pending ON campaign_executions (ad_status, ad_next_check_at)
  WHERE ad_status IN ('pending', 'uploading', 'processing', 'creating');

ALTER TABLE campaign_execution_steps DROP CONSTRAINT IF EXISTS campaign_execution_steps_step_check;
ALTER TABLE campaign_execution_steps ADD CONSTRAINT campaign_execution_steps_step_check CHECK (step IN ('create', 'edit',
  'validate', 'submit', 'approve', 'reject', 'publish', 'reconcile', 'retry', 'cancel', 'revise', 'ad_media', 'ad_creative', 'ad_create'));
