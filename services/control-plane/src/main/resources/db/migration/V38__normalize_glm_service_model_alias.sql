-- Service model aliases are used by routing and gateway requests, so they must not contain spaces.
-- Repair the historical GLM alias and update mutable scope/configuration references.
UPDATE platform_model
SET platform_model_name = 'GLM-5.2',
    display_name = 'GLM-5.2'
WHERE platform_model_name = 'GLM -5.2';

UPDATE route_policy
SET model_alias = 'GLM-5.2'
WHERE model_alias = 'GLM -5.2';

UPDATE api_key
SET model_scope = replace(model_scope, 'GLM -5.2', 'GLM-5.2')
WHERE model_scope LIKE '%GLM -5.2%';

UPDATE tenant
SET model_scope = replace(model_scope, 'GLM -5.2', 'GLM-5.2')
WHERE model_scope LIKE '%GLM -5.2%';
