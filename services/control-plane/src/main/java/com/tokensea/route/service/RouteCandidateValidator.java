package com.tokensea.route.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.price.mapper.ModelPriceMapper;
import com.tokensea.route.entity.RoutePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RouteCandidateValidator {
    private final ObjectMapper json; private final JdbcTemplate jdbc;
    public RouteCandidateValidator(ModelPriceMapper ignored,ObjectMapper json,String ignoredCurrency,JdbcTemplate jdbc){this.json=json;this.jdbc=jdbc;}
    public RouteCandidateValidator(ModelPriceMapper ignored,ObjectMapper json,String ignoredCurrency,JdbcTemplate jdbc,Object ignoredEffectivePrices){this(json,jdbc);}
    @Autowired
    public RouteCandidateValidator(ObjectMapper json,JdbcTemplate jdbc){this.json=json;this.jdbc=jdbc;}

    public void validate(PlatformModel model,RoutePolicy route,boolean requireActive){
        if(model==null||route==null||!model.getPlatformModelName().equals(route.getModelAlias())||(requireActive&&!"ACTIVE".equals(route.getStatus())))
            conflict("路由策略不存在、未生效或不属于当前服务模型");
        Set<String> allowed=mappingPairs(model);Map<String,Object> config=object(route.getConfig());Object raw=config.get("candidates");
        if(!(raw instanceof List<?>))conflict("路由候选不能为空");
        List<?> candidates=(List<?>)raw;
        if(candidates.isEmpty())conflict("路由候选不能为空");
        for(Object item:candidates){
            if(!(item instanceof Map<?,?>))conflict("路由候选格式无效");
            Map<?,?> candidate=(Map<?,?>)item;
            String provider=text(candidate.get("providerInstanceId")),actual=text(candidate.get("actualModel"));
            if(!allowed.contains(provider+'\u0000'+actual))conflict("路由候选不属于服务模型映射");
            List<Map<String,Object>> deployments=jdbc.queryForList("""
                select d.id,p.region from channel_model_deployment d join provider_instance p on p.id=d.provider_instance_id
                where d.provider_instance_id=? and d.provider_model_name=? and d.production_status='APPROVED'
                  and d.health_status='HEALTHY' and d.discovery_status<>'MISSING_CONFIRMED'
                  and d.routing_status='ELIGIBLE' and (select v.status from capability_validation v
                    where v.deployment_id=d.id and v.test_type='LIVE_PROBE' order by v.validated_at desc limit 1)='PASSED'
                order by d.updated_at desc limit 1
                """,provider,actual);
            if(deployments.isEmpty())conflict("候选渠道部署尚未通过真实探测和管理员生产确认");
        }
    }
    /**
     * Resolves every actual model against the selected channels' deployments.  The arrays on a
     * platform model are selections, not positional pairs: one channel may legitimately provide
     * several actual models.
     */
    public Set<String> mappingPairs(PlatformModel model){
        try {
            List<String> providers=json.readValue(model.getProviderInstanceIds(),new TypeReference<>(){});
            List<String> actual=json.readValue(model.getActualModels(),new TypeReference<>(){});
            if (providers.isEmpty() || actual.isEmpty()) conflict("服务模型映射无效");
            Set<String> selectedProviders=new LinkedHashSet<>(providers);
            Set<String> selectedActual=new LinkedHashSet<>(actual);
            if (selectedProviders.size()!=providers.size() || selectedActual.size()!=actual.size()) conflict("服务模型映射无效");
            Set<String> usedProviders=new LinkedHashSet<>();
            Set<String> result=new LinkedHashSet<>();
            for (String actualModel : actual) {
                List<String> matches=new ArrayList<>();
                for (String provider : providers) {
                    Integer count=jdbc.queryForObject("""
                            select count(*) from channel_model_deployment
                             where provider_instance_id=? and lower(provider_model_name)=lower(?)
                               and discovery_status<>'MISSING_CONFIRMED'
                            """, Integer.class, provider, actualModel);
                    if (count != null && count > 0) matches.add(provider);
                }
                if (matches.size()!=1) conflict("服务模型映射无效");
                String provider=matches.getFirst();
                usedProviders.add(provider);
                result.add(provider+'\u0000'+actualModel);
            }
            if (!usedProviders.equals(selectedProviders)) conflict("服务模型映射无效");
            return result;
        } catch(ResponseStatusException e){throw e;
        } catch(Exception e){conflict("服务模型映射无效");return Set.of();}
    }
    private Map<String,Object> object(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){conflict("路由配置必须是 JSON 对象");return Map.of();}}
    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static void conflict(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
