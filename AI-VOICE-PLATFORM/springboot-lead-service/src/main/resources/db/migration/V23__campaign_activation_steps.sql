-- Campaigns can be activated (and paused again) from Campex after an explicit, confirmed action.
ALTER TABLE campaign_execution_steps DROP CONSTRAINT IF EXISTS campaign_execution_steps_step_check;
ALTER TABLE campaign_execution_steps ADD CONSTRAINT campaign_execution_steps_step_check CHECK (step IN ('create', 'edit',
  'validate', 'submit', 'approve', 'reject', 'publish', 'reconcile', 'retry', 'cancel', 'revise', 'ad_media', 'ad_creative',
  'ad_create', 'activate', 'pause'));
