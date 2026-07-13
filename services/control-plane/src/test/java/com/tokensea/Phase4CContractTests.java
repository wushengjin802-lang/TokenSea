package com.tokensea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import com.tokensea.governance.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Phase4CContractTests {
    @Test void activeProbeRejectsClientSelectedUnsupportedCapability(){
        CapabilityProbeController controller=new CapabilityProbeController(mock(JdbcTemplate.class),null,null,null,new ObjectMapper(),mock(AuditService.class));
        assertThrows(ResponseStatusException.class,()->controller.probe("deployment",new CapabilityProbeController.ProbeRequest("CLIENT_ASSERTED"),null));
    }

    @Test void budgetApprovalRejectionWritesRuleLifecycleState(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AuditService audits=mock(AuditService.class);GovernanceController controller=new GovernanceController(jdbc,new ObjectMapper(),audits,mock(GovernanceApprovalService.class));
        when(jdbc.queryForList(startsWith("select * from approval_request"),eq("approval"))).thenReturn(List.of(new HashMap<>(Map.of("id","approval","status","PENDING","resource_type","BUDGET_RULE","resource_id","rule"))));
        when(jdbc.queryForMap(startsWith("select * from approval_request"),eq("approval"))).thenReturn(new HashMap<>(Map.of("id","approval","status","REJECTED")));
        controller.reject("approval",new GovernanceController.ApprovalDecisionRequest("额度依据不足"),null);
        verify(jdbc).update(contains("budget_rule set approval_status='REJECTED'"),eq("rule"));
    }

    @Test void reconciliationRequiresCompleteBillClassificationInputs(){
        GovernanceController controller=new GovernanceController(mock(JdbcTemplate.class),new ObjectMapper(),mock(AuditService.class),mock(GovernanceApprovalService.class));
        var incomplete=new GovernanceController.ReconciliationRequest("provider",LocalDate.now(),LocalDate.now(),"CNY",BigDecimal.TEN,null,null,null,"bill",null);
        assertThrows(ResponseStatusException.class,()->controller.reconcile(incomplete));
    }

    @Test void budgetRollbackRestoresSnapshotFields(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);AuditService audits=mock(AuditService.class);GovernanceApprovalService service=new GovernanceApprovalService(jdbc,new ObjectMapper(),audits);
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("scope_type","TENANT");snapshot.put("scope_id","t1");snapshot.put("currency","CNY");snapshot.put("amount_limit",100);snapshot.put("warning_threshold_percent",80);snapshot.put("over_limit_action","BLOCK");snapshot.put("degrade_model_alias",null);snapshot.put("status","ACTIVE");snapshot.put("approval_status","APPROVED");snapshot.put("effective_from",null);snapshot.put("effective_to",null);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("resource_type","BUDGET_RULE","resource_id","rule","snapshot",write(snapshot))),List.of(Map.of("id","rule")),List.of(Map.of("id","rule","status","ACTIVE")));
        service.rollback("version","tester");
        verify(jdbc).update(startsWith("update budget_rule set"),any(Object[].class));
    }

    @Test void controlledJsonSyncImportsRealReferenceRecords(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);ModelDiscoveryController discovery=mock(ModelDiscoveryController.class);SyncJobExecutor executor=new SyncJobExecutor(jdbc,discovery,new ObjectMapper(),"");
        when(jdbc.queryForList(startsWith("select id,schedule_expression"))).thenReturn(List.of());
        when(jdbc.queryForList(startsWith("select j.id"))).thenReturn(List.of(new HashMap<>(Map.of("id","job","data_source_id","source","source_type","FILE_IMPORT","config","{\"format\":\"JSON\",\"content\":\"[{\\\"canonicalName\\\":\\\"model-a\\\",\\\"displayName\\\":\\\"模型甲\\\"}]\"}"))));
        when(jdbc.update(startsWith("update sync_job set status='RUNNING'"),any(Object[].class))).thenReturn(1);
        when(jdbc.update(startsWith("insert into public_model_reference"),any(Object[].class))).thenReturn(1);
        executor.poll();
        verify(jdbc).update(startsWith("insert into public_model_reference"),any(Object[].class));
        verify(jdbc).update(startsWith("update sync_job set status='SUCCEEDED'"),any(Object[].class));
    }
    private static String write(Object value){try{return new ObjectMapper().writeValueAsString(value);}catch(Exception e){throw new RuntimeException(e);}}
}
