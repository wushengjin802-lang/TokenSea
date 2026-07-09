package com.tokensea.runtime;

import com.tokensea.common.ApiResponse;
import com.tokensea.deployment.entity.ModelDeployment;
import com.tokensea.deployment.mapper.ModelDeploymentMapper;
import com.tokensea.model.entity.ModelAsset;
import com.tokensea.model.mapper.ModelAssetMapper;
import com.tokensea.provider.entity.Provider;
import com.tokensea.provider.mapper.ProviderMapper;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeConfigController {
    private final ModelAssetMapper models; private final ModelDeploymentMapper deployments; private final ProviderMapper providers;
    public RuntimeConfigController(ModelAssetMapper models, ModelDeploymentMapper deployments, ProviderMapper providers) {this.models=models;this.deployments=deployments;this.providers=providers;}
    @GetMapping(value="/config.yaml", produces="text/yaml")
    public String configYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("model_list:\n");
        List<ModelDeployment> ds = deployments.selectList(null);
        for (ModelDeployment d: ds) {
            if (!"ACTIVE".equalsIgnoreCase(d.getStatus())) continue;
            ModelAsset m = models.selectById(d.getModelId());
            Provider p = providers.selectById(d.getProviderId());
            if (m == null || p == null || !"ACTIVE".equalsIgnoreCase(m.getStatus()) || !"ACTIVE".equalsIgnoreCase(p.getStatus())) continue;
            sb.append("  - model_name: ").append(m.getAlias()).append("\n");
            sb.append("    litellm_params:\n");
            sb.append("      model: ").append(d.getRuntimeModelName()).append("\n");
            if (p.getBaseUrl()!=null) sb.append("      api_base: ").append(p.getBaseUrl()).append("\n");
            sb.append("      api_key: os.environ/").append("PROVIDER_").append(p.getId().replace('-', '_').toUpperCase()).append("_API_KEY\n");
        }
        sb.append("general_settings:\n  master_key: os.environ/TOKENSEA_RUNTIME_ENGINE_KEY\n");
        sb.append("litellm_settings:\n  request_timeout: 120\n  drop_params: true\n");
        return sb.toString();
    }
    @GetMapping("/config-status")
    public ApiResponse<String> status(){ return ApiResponse.ok("配置导出已启用；生产建议由 CI/CD 或运维脚本拉取后重启运行时底座"); }
}
