-- Keep the display-only route name on platform_model consistent with its bound route record.
UPDATE platform_model pm
SET route_policy = rp.name,
    updated_at = now()
FROM route_policy rp
WHERE pm.route_policy_id = rp.id
  AND pm.route_policy IS DISTINCT FROM rp.name;

-- A route that is not bound to any service model cannot receive gateway traffic.
-- Retire duplicate active routes for aliases that already have a bound route, while retaining history.
UPDATE route_policy rp
SET status = 'RETIRED',
    updated_at = now()
WHERE rp.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM platform_model pm WHERE pm.route_policy_id = rp.id
  )
  AND EXISTS (
      SELECT 1 FROM platform_model pm
      WHERE pm.platform_model_name = rp.model_alias
        AND pm.route_policy_id IS NOT NULL
  );
