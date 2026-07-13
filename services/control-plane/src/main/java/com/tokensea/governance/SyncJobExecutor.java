package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class SyncJobExecutor {
    private final JdbcTemplate jdbc; private final ModelDiscoveryController discovery; private final ObjectMapper json;
    private final String owner=UUID.randomUUID().toString();private final Set<String> allowedHosts;private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public SyncJobExecutor(JdbcTemplate jdbc,ModelDiscoveryController discovery,ObjectMapper json,@Value("${tokensea.sync.public-reference-hosts:}") String allowedHosts){this.jdbc=jdbc;this.discovery=discovery;this.json=json;this.allowedHosts=Arrays.stream(allowedHosts.split(",")).map(String::trim).filter(v->!v.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());}

    @Scheduled(fixedDelayString="${tokensea.sync.poll-ms:15000}")
    public void poll(){enqueueScheduled();claimAndExecute();}

    private void enqueueScheduled(){
        List<Map<String,Object>> due=jdbc.queryForList("select id,schedule_expression from data_source where status='ACTIVE' and sync_mode='SCHEDULED' and next_run_at is not null and next_run_at<=now() and not exists(select 1 from sync_job where data_source_id=data_source.id and status in ('PENDING','RUNNING')) limit 20");
        for(Map<String,Object> source:due){String sourceId=String.valueOf(source.get("id"));jdbc.update("insert into sync_job(id,data_source_id,job_type,trigger_type,status,scheduled_for) values(?,?,'FULL_SYNC','SCHEDULED','PENDING',now())",id(),sourceId);jdbc.update("update data_source set next_run_at=?,updated_at=now() where id=?",nextRun(String.valueOf(source.get("schedule_expression"))),sourceId);}
    }

    private void claimAndExecute(){
        List<Map<String,Object>> pending=jdbc.queryForList("select j.id,j.data_source_id,s.source_type,s.provider_instance_id,s.endpoint,s.config from sync_job j join data_source s on s.id=j.data_source_id where j.status='PENDING' and j.scheduled_for<=now() order by j.created_at limit 1");
        if(pending.isEmpty())return;Map<String,Object> job=pending.get(0);String jobId=String.valueOf(job.get("id"));
        if(jdbc.update("update sync_job set status='RUNNING',started_at=now(),lock_owner=?,heartbeat_at=now(),updated_at=now() where id=? and status='PENDING'",owner,jobId)!=1)return;
        String sourceId=String.valueOf(job.get("data_source_id"));List<Map<String,Object>> log=new ArrayList<>();
        try{
            log.add(Map.of("at",OffsetDateTime.now().toString(),"event","STARTED"));
            int read,changed;String type=String.valueOf(job.get("source_type"));
            if("PROVIDER_API".equals(type)){if(job.get("provider_instance_id")==null)throw new IllegalStateException("供应商接口数据源未绑定渠道");var summary=discovery.discover(String.valueOf(job.get("provider_instance_id"))).data();log.add(Map.of("at",OffsetDateTime.now().toString(),"event","DISCOVERY_COMPLETED","snapshotId",summary.snapshotId()));read=summary.discovered();changed=summary.deploymentsCreated()+summary.diffsCreated()+summary.missingCount();}
            else if("PUBLIC_REFERENCE".equals(type)){List<Map<String,Object>> records=publicRecords(String.valueOf(job.get("endpoint")));read=records.size();changed=upsertReferences(records,String.valueOf(job.get("endpoint")));log.add(Map.of("at",OffsetDateTime.now().toString(),"event","PUBLIC_REFERENCE_IMPORTED","records",read));}
            else if("FILE_IMPORT".equals(type)){List<Map<String,Object>> records=fileRecords(job.get("config"));read=records.size();changed=upsertReferences(records,"受控文件导入");log.add(Map.of("at",OffsetDateTime.now().toString(),"event","CONTROLLED_FILE_IMPORTED","records",read));}
            else throw new IllegalStateException("不受支持的数据源类型: "+type);
            jdbc.update("update sync_job set status='SUCCEEDED',records_read=?,records_changed=?,completed_at=now(),execution_log=cast(? as jsonb),heartbeat_at=now(),updated_at=now() where id=?",read,changed,write(log),jobId);
            jdbc.update("update data_source set last_sync_at=now(),last_sync_status='SUCCEEDED',last_sync_error=null,updated_at=now() where id=?",sourceId);
        }catch(Exception e){
            log.add(Map.of("at",OffsetDateTime.now().toString(),"event","FAILED","message",safe(e.getMessage())));
            jdbc.update("update sync_job set status='FAILED',error_code='SYNC_EXECUTION_FAILED',error_message=?,completed_at=now(),execution_log=cast(? as jsonb),heartbeat_at=now(),updated_at=now() where id=?",safe(e.getMessage()),write(log),jobId);
            jdbc.update("update data_source set last_sync_at=now(),last_sync_status='FAILED',last_sync_error=?,updated_at=now() where id=?",safe(e.getMessage()),sourceId);
        }
    }
    private List<Map<String,Object>> publicRecords(String endpoint)throws Exception{URI uri=URI.create(endpoint);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||!allowedHosts.contains(uri.getHost().toLowerCase()))throw new IllegalStateException("公共参考来源必须使用已批准的 HTTPS 主机");HttpResponse<byte[]>response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofByteArray());if(response.statusCode()!=200)throw new IllegalStateException("公共参考来源返回 HTTP "+response.statusCode());if(response.body().length>2_000_000)throw new IllegalStateException("公共参考来源响应超过 2MB 限制");return jsonRecords(new String(response.body(),StandardCharsets.UTF_8));}
    private List<Map<String,Object>> fileRecords(Object config)throws Exception{Map<?,?>cfg=json.readValue(String.valueOf(config),Map.class);Object rawContent=cfg.get("content"),rawFormat=cfg.get("format");String content=rawContent==null?"":String.valueOf(rawContent),format=rawFormat==null?"JSON":String.valueOf(rawFormat).toUpperCase();if(content.getBytes(StandardCharsets.UTF_8).length>2_000_000)throw new IllegalStateException("导入内容超过 2MB 限制");if("JSON".equals(format))return jsonRecords(content);if(!"CSV".equals(format))throw new IllegalStateException("仅允许 JSON 或 CSV 导入");String[]lines=content.split("\\R");if(lines.length<2)return List.of();String[]headers=lines[0].split(",",-1);List<Map<String,Object>>out=new ArrayList<>();for(int i=1;i<lines.length&&i<=5001;i++){String[]values=lines[i].split(",",-1);Map<String,Object>row=new LinkedHashMap<>();for(int j=0;j<headers.length&&j<values.length;j++)row.put(headers[j].trim(),values[j].trim());out.add(row);}return out;}
    private List<Map<String,Object>> jsonRecords(String content)throws Exception{Object parsed=json.readValue(content,Object.class);Object list=parsed instanceof Map<?,?>m?m.get("models"):parsed;if(!(list instanceof List<?>rows))throw new IllegalStateException("JSON 必须是数组或包含 models 数组");if(rows.size()>5000)throw new IllegalStateException("单次最多导入 5000 条");List<Map<String,Object>>out=new ArrayList<>();for(Object row:rows){if(!(row instanceof Map<?,?>m))throw new IllegalStateException("模型记录必须是对象");Map<String,Object>copy=new LinkedHashMap<>();m.forEach((k,v)->copy.put(String.valueOf(k),v));out.add(copy);}return out;}
    private int upsertReferences(List<Map<String,Object>> records,String source){int changed=0;for(Map<String,Object>r:records){String name=text(r,"canonicalName","canonical_name","id","name"),display=text(r,"displayName","display_name","name","id");if(name==null||display==null)throw new IllegalStateException("公共模型记录缺少 canonicalName/displayName");changed+=jdbc.update("insert into public_model_reference(id,canonical_name,display_name,vendor,family,capability_claims,context_length,source_type,source_ref,source_confidence) values(?,?,?,?,?,cast(? as jsonb),?,'SYNC_IMPORT',?,1) on conflict(canonical_name) do update set display_name=excluded.display_name,vendor=excluded.vendor,family=excluded.family,capability_claims=excluded.capability_claims,context_length=excluded.context_length,source_ref=excluded.source_ref,version=public_model_reference.version+1,updated_at=now()",id(),name,display,r.get("vendor"),r.get("family"),write(r.getOrDefault("capabilities",List.of())),integer(r.get("contextLength")),source);}return changed;}
    private static String text(Map<String,Object>r,String...keys){for(String key:keys){Object v=r.get(key);if(v!=null&&!String.valueOf(v).isBlank())return String.valueOf(v);}return null;}private static Integer integer(Object v){try{return v==null?null:Integer.valueOf(String.valueOf(v));}catch(Exception e){return null;}}
    private OffsetDateTime nextRun(String expression){try{return OffsetDateTime.now().plus(Duration.parse(expression));}catch(Exception e){return OffsetDateTime.now().plusHours(24);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){return "[]";}}
    private static String safe(String value){if(value==null)return "未知错误";return value.length()>1000?value.substring(0,1000):value;}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
