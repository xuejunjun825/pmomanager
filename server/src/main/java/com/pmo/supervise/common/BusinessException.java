package com.pmo.supervise.common;

import java.util.List;
import java.util.Map;

/**
 * 带错误码的业务异常。409/422/503 等语义由异常承载，
 * GlobalExceptionHandler 决定响应形状。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;
    private final List<ApiError.FieldError> fieldErrors;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage(), List.of(), Map.of());
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, List.of(), Map.of());
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, List.of(), details);
    }

    public BusinessException(ErrorCode code, String message, List<ApiError.FieldError> fieldErrors,
                             Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }

    /** 422 + 字段级错误（来源组合等跨字段校验用）。 */
    public static BusinessException validation(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new ApiError.FieldError(field, message, 0)), Map.of());
    }

    /** 422 + 多个字段级错误。 */
    public static BusinessException validation(Map<String, String> fields) {
        List<ApiError.FieldError> errors = fields.entrySet().stream()
                .map(e -> new ApiError.FieldError(e.getKey(), e.getValue(), 0))
                .toList();
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "请求参数校验失败", errors, Map.of());
    }
}
