package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.common.ApiResponse;
import com.tokensea.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequestMapping("/api/model-snapshots")
public class SnapshotAccessController {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final AuditService audits;
 public SnapshotAccessController(JdbcTemplate jdbc,ObjectMapper json,AuditService audits){this.jdbc=jdbc;this.json=json;this.audits=audits;}
 @GetMapping("/{id}/raw") @Transactional public ApiResponse<Map<String,Object>> raw(@PathVariable String id,@RequestParam String reason,Authentication a){if(reason==null||reason.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"查看原始快照必须填写理由");List<Map<String,Object>>rows=jdbc.queryForList("select s.id,s.provider_instance_id,s.source_endpoint,s.http_status,s.checksum,s.raw_payload,s.discovered_at,d.field_sources from provider_model_snapshot s left join channel_model_deployment d on d.source_snapshot_id=s.id where s.id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"快照不存在");Map<String,Object> row=new LinkedHashMap<>(rows.get(0));Object raw=sanitize(row.get("raw_payload"));row.put("raw_payload",raw);String actor=a!=null&&a.getPrincipal() instanceof JwtService.Identity i?i.userId():"SYSTEM",log=UUID.randomUUID().toString().replace("-","");jdbc.update("insert into sensitive_access_log(id,actor_id,object_type,object_id,reason,fields_viewed) values(?,?,?,?,?,cast(? as jsonb))",log,actor,"ProviderModelSnapshot",id,reason,"[\"raw_payload\",\"field_sources\"]");audits.record("SNAPSHOT_RAW_VIEW","ProviderModelSnapshot",id,null,Map.of("reason",reason,"accessLogId",log));return ApiResponse.ok(row);}
 private Object sanitize(Object raw){try{Object value=raw instanceof String?json.readValue(String.valueOf(raw),Object.class):raw;return walk(value);}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"快照内容无法安全解析");}}
 private Object walk(Object v){if(v instanceof Map<?,?>m){Map<String,Object>out=new LinkedHashMap<>();m.forEach((k,x)->{String key=String.valueOf(k);out.put(key,key.toLowerCase().matches(".*(key|secret|token|password|authorization).*" )?"********":walk(x));});return out;}if(v instanceof List<?>l)return l.stream().limit(5000).map(this::walk).toList();return v;}
}
