package com.tokensea.route.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.PlatformModel;
import com.tokensea.price.entity.ModelPrice;
import com.tokensea.price.mapper.ModelPriceMapper;
import com.tokensea.route.entity.RoutePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RouteCandidateValidator {
    private final ModelPriceMapper prices; private final ObjectMapper json; private final String budgetCurrency;private final JdbcTemplate jdbc;
    public RouteCandidateValidator(ModelPriceMapper prices,ObjectMapper json,@Value("${tokensea.budget-currency:CNY}") String currency,JdbcTemplate jdbc){this.prices=prices;this.json=json;this.budgetCurrency=currency.toUpperCase(Locale.ROOT);this.jdbc=jdbc;}

    public void validate(PlatformModel model,RoutePolicy route,boolean requireActive){
        if(model==null||route==null||!model.getPlatformModelName().equals(route.getModelAlias())||(requireActive&&!"ACTIVE".equals(route.getStatus())))
            conflict("路由策略不存在、未生效或不属于当前服务模型");
        Set<String> allowed=mappings(model);Map<String,Object> config=object(route.getConfig());Object raw=config.get("candidates");
        if(!(raw instanceof List<?>))conflict("路由候选不能为空");
        List<?> candidates=(List<?>)raw;
        if(candidates.isEmpty())conflict("路由候选不能为空");
        OffsetDateTime now=OffsetDateTime.now();
        for(Object item:candidates){
            if(!(item instanceof Map<?,?>))conflict("路由候选格式无效");
            Map<?,?> candidate=(Map<?,?>)item;
            String provider=text(candidate.get("providerInstanceId")),actual=text(candidate.get("actualModel"));
            if(!allowed.contains(provider+'\u0000'+actual))conflict("路由候选不属于服务模型映射");
            Integer eligible=jdbc.queryForObject("select count(*) from channel_model_deployment d where d.provider_instance_id=? and d.provider_model_name=? and d.review_status='APPROVED' and d.routing_status='ELIGIBLE' and exists(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED')",Integer.class,provider,actual);
            if(eligible==null||eligible==0)conflict("候选渠道部署未审核或缺少主动能力验证");
            Integer governed=jdbc.queryForObject("select count(*) from price_version p join channel_model_deployment d on d.id=p.deployment_id where d.provider_instance_id=? and d.provider_model_name=? and p.price_layer='CHANNEL_ACTUAL' and p.status='ACTIVE' and p.currency=? and p.effective_from<=now() and (p.effective_to is null or p.effective_to>now())",Integer.class,provider,actual,budgetCurrency);
            if(governed!=null&&governed>0)continue;
            String priceId=text(candidate.get("priceVersionId"));if(priceId.isBlank())priceId=model.getPricePolicyId();
            ModelPrice price=priceId==null?null:prices.selectById(priceId);
            if(price==null||!"ACTIVE".equals(price.getStatus())||!model.getId().equals(price.getPlatformModelId())
                    ||(price.getProviderInstanceId()!=null&&!provider.equals(price.getProviderInstanceId()))
                    ||!budgetCurrency.equals(price.getCurrency())||price.getEffectiveFrom()==null||price.getEffectiveFrom().isAfter(now)
                    ||(price.getEffectiveTo()!=null&&!price.getEffectiveTo().isAfter(now)))conflict("候选渠道缺少当前生效且适用的价格版本");
        }
    }
    private Set<String> mappings(PlatformModel model){try{List<String> providers=json.readValue(model.getProviderInstanceIds(),new TypeReference<>(){});List<String> actual=json.readValue(model.getActualModels(),new TypeReference<>(){});if(providers.isEmpty()||actual.isEmpty()||providers.size()!=1&&providers.size()!=actual.size())conflict("服务模型映射无效");Set<String> result=new HashSet<>();for(int i=0;i<actual.size();i++)result.add((providers.size()==1?providers.get(0):providers.get(i))+'\u0000'+actual.get(i));return result;}catch(ResponseStatusException e){throw e;}catch(Exception e){conflict("服务模型映射无效");return Set.of();}}
    private Map<String,Object> object(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){conflict("路由配置必须是 JSON 对象");return Map.of();}}
    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static void conflict(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
