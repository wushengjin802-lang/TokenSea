package com.tokensea.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

public abstract class BaseCrudController<T> {
    @Autowired(required = false)
    private AuditLogMapper auditLogMapper;

    protected abstract BaseMapper<T> mapper();

    @GetMapping
    public ApiResponse<List<T>> list() {
        return ApiResponse.ok(mapper().selectList(null));
    }

    @GetMapping("/{id}")
    public ApiResponse<T> get(@PathVariable("id") String id) {
        return ApiResponse.ok(mapper().selectById(id));
    }

    @PostMapping
    public ApiResponse<T> create(@RequestBody T body) {
        mapper().insert(body);
        audit("CREATE", body, null);
        return ApiResponse.ok(body);
    }

    @PutMapping("/{id}")
    public ApiResponse<T> update(@PathVariable("id") String id, @RequestBody T body) {
        T before = mapper().selectById(id);
        try {
            body.getClass().getMethod("setId", String.class).invoke(body, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("entity must provide setId(String)");
        }
        mapper().updateById(body);
        audit("UPDATE", body, before);
        return ApiResponse.ok(body);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable("id") String id) {
        T before = mapper().selectById(id);
        mapper().deleteById(id);
        audit("DELETE", before, before);
        return ApiResponse.ok(true);
    }

    private void audit(String action, T after, T before) {
        if (auditLogMapper == null || after == null) return;
        try {
            AuditLog log = new AuditLog();
            log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction(action);
            log.setObjectType(after.getClass().getSimpleName());
            log.setObjectId(readId(after));
            log.setBeforeValue(before == null ? null : String.valueOf(before));
            log.setAfterValue(String.valueOf(after));
            auditLogMapper.insert(log);
        } catch (Exception ignored) {
            // 审计写入失败不能影响主业务写入，生产环境应接入告警。
        }
    }

    private String readId(T body) {
        try {
            Method m = body.getClass().getMethod("getId");
            Object id = m.invoke(body);
            return id == null ? null : String.valueOf(id);
        } catch (Exception e) {
            return null;
        }
    }
}
