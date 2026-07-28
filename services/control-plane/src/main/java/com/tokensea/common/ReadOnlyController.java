package com.tokensea.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Read-only projection for immutable/derived resources. */
public abstract class ReadOnlyController<T> {
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
}
