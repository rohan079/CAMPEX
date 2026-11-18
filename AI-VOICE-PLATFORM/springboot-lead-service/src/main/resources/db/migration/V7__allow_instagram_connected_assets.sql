ALTER TABLE meta_connected_assets
  DROP CONSTRAINT IF EXISTS meta_connected_assets_asset_type_check;

ALTER TABLE meta_connected_assets
  ADD CONSTRAINT ck_meta_connected_assets_type
  CHECK (asset_type IN ('page', 'ad_account', 'instagram'));
