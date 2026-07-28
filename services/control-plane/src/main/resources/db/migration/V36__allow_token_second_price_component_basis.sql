-- Cache storage prices are normalized as Token·second components.
-- The application already supports TOKEN_SECOND; keep the database constraint aligned.
ALTER TABLE provider_price_component
  DROP CONSTRAINT IF EXISTS ck_provider_price_component_basis;

ALTER TABLE provider_price_component
  ADD CONSTRAINT ck_provider_price_component_basis
  CHECK(unit_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE','TOKEN_SECOND'));
