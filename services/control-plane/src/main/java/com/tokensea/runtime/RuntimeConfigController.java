package com.tokensea.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.PlatformModelMapper;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.OffsetDateTime;
import org.yaml.snakeyaml.Yaml;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeConfigController {
    private final PlatformModelMapper platformModels;
    private final ProviderInstanceMapper providerInstances;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public RuntimeConfigController(PlatformModelMapper platformModels, ProviderInstanceMapper providerInstances, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.platformModels = platformModels;
        this.providerInstances = providerInstances;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/config.yaml", produces = "text/yaml")
    public String configYaml() {
        List<Map<String, Object>> modelList = new ArrayList<>();
        for (PlatformModel model : platformModels.selectList(null)) {
            if (!"已发布".equals(model.getStatus())) continue;
            List<String> instanceIds = stringList(model.getProviderInstanceIds());
            List<String> actualModels = stringList(model.getActualModels());
            if (instanceIds.isEmpty() || actualModels.isEmpty()) continue;
            for (int index = 0; index < actualModels.size(); index++) {
                String instanceId = instanceIds.size() == 1 ? instanceIds.get(0) :
                        (index < instanceIds.size() ? instanceIds.get(index) : null);
                if (instanceId == null) continue;
                ProviderInstance instance = providerInstances.selectById(instanceId);
                if (!routable(instance) || !liveProbePassed(instanceId, actualModels.get(index))) continue;
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("model", runtimeModel(instance, actualModels.get(index)));
                params.put("api_base", instance.getApiBase());
                if (!"无需 Key".equals(instance.getKeyStatus())) {
                    params.put("api_key", "os.environ/" + secretEnv(instance.getId()));
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("model_name", model.getPlatformModelName()); entry.put("litellm_params", params);
                modelList.add(entry);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model_list", modelList);
        root.put("general_settings", Map.of("master_key", "os.environ/TOKENSEA_RUNTIME_ENGINE_KEY"));
        root.put("litellm_settings", Map.of("request_timeout", 120, "drop_params", true));
        return new Yaml().dump(root);
    }

    @GetMapping("/config-status")
    public ApiResponse<String> status() {
        return ApiResponse.ok("运行时配置仅包含已发布且通过真实连接测试的服务模型；密钥通过环境变量注入");
    }

    private List<String> stringList(String value) {
        try {
            if (value == null) return List.of();
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean routable(ProviderInstance instance) {
        return instance != null && instance.getApiBase() != null && !instance.getApiBase().isBlank()
                && ("启用".equals(instance.getStatus()) || "已启用".equals(instance.getStatus()))
                && "成功".equals(instance.getLastConnectionTestStatus())
                && instance.getLastConnectionTestAt() != null
                && instance.getLastConnectionTestAt().isAfter(OffsetDateTime.now().minusMinutes(30))
                && ("无需 Key".equals(instance.getKeyStatus()) || "已配置".equals(instance.getKeyStatus()) || "已托管".equals(instance.getKeyStatus()));
    }

    private boolean liveProbePassed(String instanceId, String actualModel) {
        Integer count = jdbc.queryForObject("select count(*) from channel_model_deployment d where d.provider_instance_id=? and d.provider_model_name=? and d.review_status='APPROVED' and d.routing_status='ELIGIBLE' and exists(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED')", Integer.class, instanceId, actualModel);
        return count != null && count > 0;
    }

    private static String runtimeModel(ProviderInstance instance, String model) {
        if (model.contains("/")) return model;
        String style = instance.getApiStyle() == null ? "" : instance.getApiStyle().toLowerCase();
        if (style.contains("anthropic")) return "anthropic/" + model;
        if (style.contains("gemini")) return "gemini/" + model;
        if (style.contains("azure")) return "azure/" + model;
        return "openai/" + model;
    }

    private static String secretEnv(String id) {
        return "PROVIDER_INSTANCE_" + id.replace('-', '_').toUpperCase() + "_API_KEY";
    }
}
