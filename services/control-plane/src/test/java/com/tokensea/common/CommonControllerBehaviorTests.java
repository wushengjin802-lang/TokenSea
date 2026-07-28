package com.tokensea.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonControllerBehaviorTests {
    @Test
    void readOnlyGetReturnsNotFoundForMissingResource() {
        @SuppressWarnings("unchecked")
        BaseMapper<TestEntity> mapper = mock(BaseMapper.class);
        when(mapper.selectById("missing")).thenReturn(null);
        ReadOnlyController<TestEntity> controller = new ReadOnlyController<>() {
            @Override protected BaseMapper<TestEntity> mapper() { return mapper; }
        };

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.get("missing"));

        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void deleteAuditKeepsBeforeSnapshotAndClearsAfterSnapshot() {
        @SuppressWarnings("unchecked")
        BaseMapper<TestEntity> mapper = mock(BaseMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        TestEntity entity = new TestEntity();
        entity.setId("entity-1");
        entity.setName("before");
        when(mapper.selectById("entity-1")).thenReturn(entity);
        when(mapper.deleteById("entity-1")).thenReturn(1);
        BaseCrudController<TestEntity> controller = new BaseCrudController<>() {
            @Override protected BaseMapper<TestEntity> mapper() { return mapper; }
        };
        ReflectionTestUtils.setField(controller, "auditLogMapper", audits);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());

        controller.delete("entity-1");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).insert(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("DELETE", audit.getAction());
        assertEquals("entity-1", audit.getObjectId());
        assertEquals("{\"id\":\"entity-1\",\"name\":\"before\"}", audit.getBeforeValue());
        assertNull(audit.getAfterValue());
    }

    static class TestEntity {
        private String id;
        private String name;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
