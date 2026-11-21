-- Phase 3, increment 3: Creative Studio. Every edit is a new immutable version; an approval names the exact version
-- whose copy and artwork were reviewed. Generated artwork is stored here (never a temporary provider URL).

-- Image bytes, stored once and referenced by any number of versions. Kept behind CreativeImageStore so it can move to
-- object storage without touching versions.
CREATE TABLE creative_images (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  mime_type VARCHAR(20) NOT NULL CHECK (mime_type IN ('image/png', 'image/jpeg', 'image/webp')),
  bytes BYTEA NOT NULL,
  size_bytes INTEGER NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 8388608),
  sha256 VARCHAR(64) NOT NULL,
  source VARCHAR(12) NOT NULL CHECK (source IN ('ai_generated', 'uploaded')),
  prompt TEXT,
  model VARCHAR(80),
  created_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_creative_images_client ON creative_images (client_id, sha256);

CREATE TABLE creative_assets (
  id UUID PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  name VARCHAR(160) NOT NULL,
  ad_type VARCHAR(30) NOT NULL DEFAULT 'property_launch',
  status VARCHAR(16) NOT NULL DEFAULT 'GENERATED' CHECK (status IN ('GENERATED', 'EDITING', 'REVIEW_PENDING', 'APPROVED',
    'REJECTED', 'ARCHIVED')),
  current_version INTEGER NOT NULL DEFAULT 1,
  approved_version INTEGER,
  created_by VARCHAR(320) NOT NULL,
  updated_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_creative_assets_client ON creative_assets (client_id, status, updated_at DESC);

CREATE TABLE creative_versions (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES creative_assets (id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  image_id UUID REFERENCES creative_images (id),
  headline VARCHAR(80),
  primary_text VARCHAR(1500),
  description VARCHAR(255),
  cta VARCHAR(20),
  -- Verified facts the copy may use (client-supplied). Generated artwork never counts as a factual claim.
  facts JSONB NOT NULL DEFAULT '[]'::jsonb,
  claim_check JSONB,
  change_note VARCHAR(300),
  created_by VARCHAR(320) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_creative_versions UNIQUE (asset_id, version)
);

CREATE TABLE creative_approvals (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES creative_assets (id) ON DELETE CASCADE,
  client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  decision VARCHAR(10) NOT NULL CHECK (decision IN ('approved', 'rejected')),
  claims_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
  decided_by VARCHAR(320) NOT NULL,
  decided_at TIMESTAMPTZ NOT NULL,
  note VARCHAR(1000)
);
CREATE INDEX ix_creative_approvals_asset ON creative_approvals (asset_id, decided_at DESC);
