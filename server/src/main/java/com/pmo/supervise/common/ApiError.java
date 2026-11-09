package com.pmo.supervise.common;

import java.util.List;
import java.util.Map;

/** 统一错误体（TECHNICAL §7）：{code, message, fieldErrors, details, traceId}。 */
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors,
        Map<String, Object> details,
        String traceId) {

    public record FieldError(String field, String message, Integer index) {
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details, String traceId) {
        return new ApiError(code.name(), message, List.of(), details == null ? Map.of() : details, traceId);
    }
}
