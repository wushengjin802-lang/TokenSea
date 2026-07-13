package com.tokensea.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
        return ApiResponse.ok(mapper().selectById(id));
    }
}
