package com.tokensea.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public abstract class BaseCrudController<T> {
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private ObjectMapper objectMapper;

    protected abstract BaseMapper<T> mapper();

    @GetMapping
    public ApiResponse<List<T>> list() {
        return ApiResponse.ok(mapper().selectList(null));
    }

    @GetMapping("/{id}")
    public ApiResponse<T> get(@PathVariable("id") String id) {
        T value = mapper().selectById(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        return ApiResponse.ok(value);
    }

    @PostMapping
    @Transactional
    public ApiResponse<T> create(@RequestBody T body) {
        if (mapper().insert(body) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "资源创建失败");
        audit("CREATE", body, null);
        return ApiResponse.ok(body);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<T> update(@PathVariable("id") String id, @RequestBody T body) {
        T before = mapper().selectById(id);
        if (before == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        try {
            body.getClass().getMethod("setId", String.class).invoke(body, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("entity must provide setId(String)");
        }
        if (mapper().updateById(body) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "资源已被并发修改");
        audit("UPDATE", body, before);
        return ApiResponse.ok(body);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Boolean> delete(@PathVariable("id") String id) {
        T before = mapper().selectById(id);
        if (before == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        if (mapper().deleteById(id) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "资源删除失败");
        audit("DELETE", before, before);
        return ApiResponse.ok(true);
    }

    protected void audit(String action, T after, T before) {
        if (after == null) return;
        try {
            AuditLog log = new AuditLog();
            log.setId(UUID.randomUUID().toString().replace("-", ""));
            log.setAction(action);
            log.setObjectType(after.getClass().getSimpleName());
            log.setObjectId(readId(after));
            log.setBeforeValue(before == null ? null : objectMapper.writeValueAsString(before));
            log.setAfterValue(objectMapper.writeValueAsString(after));
            auditLogMapper.insert(log);
        } catch (Exception e) {
            // Key/model/route/provider mutations are not allowed to succeed without audit evidence.
            throw new IllegalStateException("关键操作审计写入失败", e);
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
