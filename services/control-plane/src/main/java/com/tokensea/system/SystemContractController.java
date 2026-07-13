package com.tokensea.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class SystemContractController {
    private static final String MASK = "********";
    private static final Pattern SETTING_KEY = Pattern.compile("[A-Z][A-Z0-9_.-]{1,99}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audits;
    private final String gatewayBase;

    public SystemContractController(JdbcTemplate jdbc, ObjectMapper json, AuditService audits,
                                    @Value("${tokensea.gateway.public-url:http://localhost:39212}") String gatewayBase) {
        this.jdbc=jdbc; this.json=json; this.audits=audits;
        this.gatewayBase=gatewayBase.replaceAll("/+$","");
    }

    public record SettingRequest(String key,String value,String description,Boolean sensitive){}

    @GetMapping("/error-codes")
    public ApiResponse<List<Map<String,Object>>> errorCodes(){ return ApiResponse.ok(jdbc.queryForList("select code,http_status,category,reason_zh message,retry_advice_zh suggestion,retryable,component,updated_at from error_code_registry where active=true order by code")); }

    @GetMapping("/system-settings")
    public ApiResponse<List<Map<String,Object>>> settings(){
        return ApiResponse.ok(jdbc.query("select setting_key,setting_value,description,sensitive,updated_at from platform_setting order by setting_key",(rs,n)->setting(rs.getString("setting_key"),rs.getString("setting_value"),rs.getString("description"),rs.getBoolean("sensitive"),rs.getObject("updated_at"))));
    }

    @PutMapping("/system-settings") @Transactional
    public ApiResponse<Map<String,Object>> putSetting(@RequestBody SettingRequest request, Authentication authentication){
        if(request==null||request.key()==null||!SETTING_KEY.matcher(request.key()).matches()||request.value()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"设置键或设置值无效");
        return saveSetting(request.key(),request,authentication);
    }

    @PutMapping("/system-settings/{key}") @Transactional
    public ApiResponse<Map<String,Object>> putSettingByKey(@PathVariable String key,@RequestBody SettingRequest request,Authentication authentication){
        if(!SETTING_KEY.matcher(key).matches()||request==null||request.value()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"设置键或设置值无效");
        if(request.key()!=null&&!key.equals(request.key())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"路径设置键与请求不一致");
        return saveSetting(key,request,authentication);
    }

    @GetMapping("/audit/sensitive-access")
    public ApiResponse<Map<String,Object>> sensitiveAccess(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false)String tenantId){
        int safePage=Math.max(1,page),safeSize=Math.min(100,Math.max(1,size)),offset=(safePage-1)*safeSize;
        String scope="(? is null or exists(select 1 from user_tenant ut where ut.user_id=s.actor_id and ut.tenant_id=?) or s.object_id=? or exists(select 1 from project p where p.id=s.object_id and p.tenant_id=?) or exists(select 1 from app a where a.id=s.object_id and a.tenant_id=?) or exists(select 1 from api_key k where k.id=s.object_id and k.tenant_id=?))";
        Object[] args={tenantId,tenantId,tenantId,tenantId,tenantId,tenantId};
        Long total=jdbc.queryForObject("select count(*) from sensitive_access_log s where "+scope,Long.class,args);
        List<Map<String,Object>> items=jdbc.queryForList("select s.* from sensitive_access_log s where "+scope+" order by s.created_at desc limit ? offset ?",tenantId,tenantId,tenantId,tenantId,tenantId,tenantId,safeSize,offset);
        return ApiResponse.ok(Map.of("items",items,"total",total==null?0:total,"page",safePage,"size",safeSize));
    }

    @GetMapping("/quickstart/config")
    public ApiResponse<Map<String,Object>> quickstart(Authentication authentication){
        JwtService.Identity identity=identity(authentication);
        List<Map<String,Object>> candidates=jdbc.queryForList("select id,platform_model_name,display_name,visibility_scope from platform_model where status='已发布' order by display_name,platform_model_name");
        List<Map<String,Object>> models=candidates.stream().filter(row->visible(row.get("visibility_scope"),identity)).map(row->Map.<String,Object>of("id",row.get("id"),"model",row.get("platform_model_name"),"displayName",row.get("display_name"))).toList();
        String selected=models.isEmpty()?null:String.valueOf(models.get(0).get("model"));
        Map<String,Object> examples=new LinkedHashMap<>();
        examples.put("chat",Map.of("path","/v1/chat/completions","body",body(selected,false,false)));
        examples.put("stream",Map.of("path","/v1/chat/completions","body",body(selected,true,false)));
        examples.put("embedding",Map.of("path","/v1/embeddings","body",body(selected,false,true)));
        Map<String,Object> result=new LinkedHashMap<>();result.put("gatewayUrl",gatewayBase);result.put("models",models);result.put("headers",Map.of("Authorization","Bearer <Virtual Key>","Content-Type","application/json"));result.put("examples",examples);
        return ApiResponse.ok(result);
    }

    private ApiResponse<Map<String,Object>> saveSetting(String key,SettingRequest request,Authentication authentication){
        List<Map<String,Object>> rows=jdbc.queryForList("select * from platform_setting where setting_key=?",key);Map<String,Object> before=rows.isEmpty()?null:rows.get(0);
        boolean sensitive=request.sensitive()!=null?request.sensitive():before!=null&&Boolean.TRUE.equals(before.get("sensitive"));
        String value=MASK.equals(request.value())&&before!=null?String.valueOf(before.get("setting_value")):request.value();
        jdbc.update("insert into platform_setting(setting_key,setting_value,description,sensitive,updated_at) values(?,?,?,?,now()) on conflict(setting_key) do update set setting_value=excluded.setting_value,description=excluded.description,sensitive=excluded.sensitive,updated_at=now()",key,value,request.description(),sensitive);
        Map<String,Object> after=jdbc.queryForMap("select * from platform_setting where setting_key=?",key);audits.record("SYSTEM_SETTING_UPDATE","PlatformSetting",key,masked(before),masked(after));
        return ApiResponse.ok(setting(key,value,request.description(),sensitive,after.get("updated_at")));
    }

    private Map<String,Object> setting(String key,String value,String description,boolean sensitive,Object updatedAt){Map<String,Object> out=new LinkedHashMap<>();out.put("id",key);out.put("group",key.contains(".")?key.substring(0,key.indexOf('.')):"平台");out.put("key",key);out.put("displayName",description==null||description.isBlank()?key:description);out.put("value",sensitive?MASK:value);out.put("valueType",infer(value));out.put("editable",true);out.put("sensitive",sensitive);out.put("updatedAt",updatedAt);return out;}
    private static String infer(String value){if(value!=null&&value.matches("-?\\d+(\\.\\d+)?"))return"NUMBER";if("true".equalsIgnoreCase(value)||"false".equalsIgnoreCase(value))return"BOOLEAN";return"STRING";}
    private static Map<String,Object> masked(Map<String,Object> value){if(value==null)return null;Map<String,Object> copy=new LinkedHashMap<>(value);if(Boolean.TRUE.equals(copy.get("sensitive")))copy.put("setting_value",MASK);return copy;}
    private boolean visible(Object raw,JwtService.Identity identity){if(identity.roles().contains("ADMIN"))return true;try{List<String> scope=json.readValue(String.valueOf(raw),new TypeReference<>(){});if(scope.isEmpty())return false;Set<String> tenantTypes=new HashSet<>(jdbc.queryForList("select type from tenant where id in (select tenant_id from user_tenant where user_id=? and status='ACTIVE')",String.class,identity.userId()));return scope.stream().anyMatch(v->identity.tenantIds().contains(v)||tenantTypes.contains(v));}catch(Exception e){return false;}}
    private static JwtService.Identity identity(Authentication authentication){if(authentication==null||!(authentication.getPrincipal() instanceof JwtService.Identity value))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"身份无效");return value;}
    private static Map<String,Object> body(String model,boolean stream,boolean embedding){Map<String,Object>b=new LinkedHashMap<>();b.put("model",model);if(embedding)b.put("input",List.of("需要向量化的文本"));else{b.put("messages",List.of(Map.of("role","user","content","你好")));if(stream)b.put("stream",true);}return b;}
}
