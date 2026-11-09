package com.pmo.supervise.common;

import java.util.List;

/** 统一分页响应（TECHNICAL §5.1）。page 从 1 开始。 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}
