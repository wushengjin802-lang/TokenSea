package com.tokensea.governance.pricing.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class BuiltInReferenceSourceReconciler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BuiltInReferenceSourceCatalog catalog;

    public BuiltInReferenceSourceReconciler(JdbcTemplate jdbc,
                                            ObjectMapper json,
                                            BuiltInReferenceSourceCatalog catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
    }

    @Transactional
    public int reconcile() {
        int changed = 0;
        for (BuiltInReferenceSourceCatalog.ReferenceSourceDefinition source : catalog.sources()) {
            String hosts = write(source.officialHosts());
            OffsetDateTime nextRun = source.onlineSync() ? OffsetDateTime.now() : null;
            int updated = jdbc.update("""
                update provider_price_source set
                  name=?,source_class='PUBLIC_REFERENCE',adapter_code=?,auth_mode='NONE',endpoint=?,
                  official_hosts=cast(? as jsonb),schedule_expression=?,auto_publish=true,
                  max_auto_change_ratio=0.3000,confirmation_runs=1,status='ACTIVE',
                  next_run_at=case when ? then coalesce(next_run_at,now()) else null end,
                  parser_version='1.0.0',fetch_mode='STRUCTURED_HTTP',source_priority=?,price_nature='ORIGINAL',
                  connector_code=?,data_scope='REFERENCE_DATASET',trust_level='COMMUNITY_REFERENCE',
                  publish_policy='MANUAL_ONLY',schema_version='price-record-v1',credential_ref=null,
                  credential_purpose='NONE',mapping_profile='DEFAULT',document_type='JSON',
                  extraction_mode='DETERMINISTIC',minimum_confidence=1.00000,require_manual_review=false,
                  managed_by='SYSTEM',source_purpose='REFERENCE',publish_target='PUBLIC_REFERENCE_ONLY',
                  stale_after_hours=?,last_error=null,updated_by='SYSTEM',updated_at=now()
                where id=?
                """, source.name(), source.adapterCode(), source.endpoint(), hosts,
                    source.scheduleExpression(), source.onlineSync(), source.sourcePriority(),
                    source.connectorCode(), source.staleAfterHours(), source.id());
            if (updated == 0) {
                jdbc.update("""
                    insert into provider_price_source(
                      id,name,source_class,adapter_code,auth_mode,endpoint,official_hosts,region,default_currency,
                      schedule_expression,auto_publish,max_auto_change_ratio,confirmation_runs,config,status,next_run_at,
                      parser_version,fetch_mode,source_priority,price_nature,connector_code,data_scope,trust_level,
                      publish_policy,schema_version,credential_purpose,mapping_profile,document_type,extraction_mode,
                      minimum_confidence,require_manual_review,max_document_pages,max_document_bytes,
                      managed_by,source_purpose,publish_target,stale_after_hours,created_by,updated_by)
                    values(?,?,'PUBLIC_REFERENCE',?,'NONE',?,cast(? as jsonb),'global','USD',?,true,0.3000,1,
                      '{"referenceOnly":true}','ACTIVE',?,'1.0.0','STRUCTURED_HTTP',?,'ORIGINAL',?,
                      'REFERENCE_DATASET','COMMUNITY_REFERENCE','MANUAL_ONLY','price-record-v1','NONE','DEFAULT',
                      'JSON','DETERMINISTIC',1.00000,false,1,20000000,'SYSTEM','REFERENCE',
                      'PUBLIC_REFERENCE_ONLY',?,'SYSTEM','SYSTEM')
                    """, source.id(), source.name(), source.adapterCode(), source.endpoint(), hosts,
                        source.scheduleExpression(), nextRun, source.sourcePriority(), source.connectorCode(),
                        source.staleAfterHours());
            }
            changed += 1;
        }
        return changed;
    }

    @Transactional
    public int pauseAll() {
        return jdbc.update("""
            update provider_price_source set status='PAUSED',next_run_at=null,
              last_error=null,updated_by='SYSTEM',updated_at=now()
            where managed_by='SYSTEM' and source_purpose='REFERENCE'
            """);
    }

    public List<String> onlineSourceIds() {
        return catalog.sources().stream()
                .filter(BuiltInReferenceSourceCatalog.ReferenceSourceDefinition::onlineSync)
                .map(BuiltInReferenceSourceCatalog.ReferenceSourceDefinition::id)
                .toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("内置参考价格源配置序列化失败", exception);
        }
    }
}
