package com.tokensea.common;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
