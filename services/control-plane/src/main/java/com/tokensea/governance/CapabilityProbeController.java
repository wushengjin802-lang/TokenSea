package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.mapper.ProviderInstanceMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/channel-model-deployments")
public class CapabilityProbeController {
    private final JdbcTemplate jdbc;private final ProviderInstanceMapper instances;private final ProviderConnectionService connections;private final TransactionTemplate transactions;private final ObjectMapper json;private final AuditService audits;
    public CapabilityProbeController(JdbcTemplate jdbc,ProviderInstanceMapper instances,ProviderConnectionService connections,TransactionTemplate transactions,ObjectMapper json,AuditService audits){this.jdbc=jdbc;this.instances=instances;this.connections=connections;this.transactions=transactions;this.json=json;this.audits=audits;}
    public record ProbeRequest(String capabilityCode){}
    @PostMapping("/{id}/probe")
    public ApiResponse<Map<String,Object>> probe(@PathVariable String id,@RequestBody ProbeRequest request,Authentication authentication){
        if(request==null||!Set.of("CHAT","STREAM","EMBEDDING").contains(request.capabilityCode()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"探测类型无效");
        Map<String,Object> deployment=one("select * from channel_model_deployment where id=?",id);if(!"APPROVED".equals(deployment.get("review_status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"渠道部署尚未审核通过");ProviderInstance instance=instances.selectById(String.valueOf(deployment.get("provider_instance_id")));if(instance==null||!connections.matchesVerifiedTarget(instance))throw new ResponseStatusException(HttpStatus.CONFLICT,"渠道未通过有效连接验证");
        String requestId=UUID.randomUUID().toString().replace("-","");ProviderConnectionService.CapabilityProbeResult result=connections.probeCapability(instance,String.valueOf(deployment.get("provider_model_name")),request.capabilityCode());
        return ApiResponse.ok(transactions.execute(tx->{String validation=UUID.randomUUID().toString().replace("-","");String status=result.success()?"PASSED":"FAILED";jdbc.update("insert into capability_validation(id,deployment_id,capability_code,test_type,request_summary,response_summary,status,evidence_ref,latency_ms,validated_by,probe_endpoint,http_status,stream_verified,probe_request_id) values(?,?,?,'LIVE_PROBE',cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?,?,?)",validation,id,request.capabilityCode(),write(Map.of("model",deployment.get("provider_model_name"))),write(summary(result)),status,"probe:"+requestId,result.latencyMs(),actor(authentication),result.endpoint(),result.httpStatus(),result.streamVerified(),requestId);jdbc.update("update channel_model_deployment set routing_status=case when exists(select 1 from capability_validation where deployment_id=? and test_type='LIVE_PROBE' and status='PASSED') then 'ELIGIBLE' else 'INELIGIBLE' end,updated_at=now() where id=?",id,id);Map<String,Object> saved=one("select * from capability_validation where id=?",validation);audits.record("CAPABILITY_LIVE_PROBE","CapabilityValidation",validation,null,saved);return saved;}));
    }
    private Map<String,Object> summary(ProviderConnectionService.CapabilityProbeResult r){Map<String,Object> value=new LinkedHashMap<>();value.put("success",r.success());value.put("errorCode",r.errorCode());value.put("error",r.error());value.put("responseBytes",r.responseBytes());return value;}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"渠道部署不存在");return rows.get(0);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String actor(Authentication a){return a!=null&&a.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM";}
}
