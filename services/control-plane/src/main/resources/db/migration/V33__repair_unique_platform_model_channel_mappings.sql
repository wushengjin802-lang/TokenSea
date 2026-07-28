-- Repair legacy single-channel service-model mappings only when one deployment matches unambiguously.
WITH broken_single_mapping AS (
    SELECT p.id, p.actual_models::jsonb ->> 0 AS actual_model
    FROM platform_model p
    WHERE jsonb_array_length(p.provider_instance_ids::jsonb) = 1
      AND jsonb_array_length(p.actual_models::jsonb) = 1
      AND NOT EXISTS (
          SELECT 1
          FROM channel_model_deployment d
          WHERE d.provider_instance_id = p.provider_instance_ids::jsonb ->> 0
            AND d.provider_model_name = p.actual_models::jsonb ->> 0
            AND d.discovery_status <> 'MISSING_CONFIRMED'
      )
), unique_deployment AS (
    SELECT b.id, min(d.provider_instance_id) AS provider_instance_id
    FROM broken_single_mapping b
    JOIN channel_model_deployment d
      ON d.provider_model_name = b.actual_model
     AND d.discovery_status <> 'MISSING_CONFIRMED'
    GROUP BY b.id
    HAVING count(DISTINCT d.provider_instance_id) = 1
)
UPDATE platform_model p
SET provider_instance_ids = jsonb_build_array(u.provider_instance_id)::text,
    updated_at = now()
FROM unique_deployment u
WHERE p.id = u.id;
