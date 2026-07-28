package com.tokensea.common;

import java.util.Locale;
import java.util.Map;

public record PageQuery(int page, int size, long offset, String sortColumn, String direction) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 500;

    public static PageQuery of(Integer page,
                               Integer size,
                               String sort,
                               String order,
                               Map<String, String> allowedSorts,
                               String defaultSort,
                               String defaultOrder) {
        int resolvedPage = page == null || page < 1 ? 1 : page;
        int resolvedSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String resolvedSort = sort == null
                ? allowedSorts.get(defaultSort)
                : allowedSorts.getOrDefault(sort, allowedSorts.get(defaultSort));
        if (resolvedSort == null) {
            throw new IllegalArgumentException("缺少默认排序字段");
        }
        String requestedDirection = order == null ? defaultOrder : order;
        String direction = "desc".equalsIgnoreCase(requestedDirection) ? "desc" : "asc";
        long offset = (long) (resolvedPage - 1) * resolvedSize;
        return new PageQuery(resolvedPage, resolvedSize, offset, resolvedSort, direction.toLowerCase(Locale.ROOT));
    }

    public boolean ascending() {
        return "asc".equals(direction);
    }
}
