-- Align the existing Doubao service model with the approved, healthy channel deployment.
update platform_model
set actual_models='["doubao-seed-2-0-lite-260215"]', updated_at=now()
where platform_model_name='doubao-seed-2.0-lite'
  and actual_models='["doubao-seed-2-0-lite-260428"]';
