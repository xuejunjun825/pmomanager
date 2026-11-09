package com.pmo.supervise.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一异常渲染（TECHNICAL §7）：错误体形状是前端契约的一部分，
 * 集中一处才不会每种错误一个样。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.code();
        ApiError body = new ApiError(code.name(), ex.getMessage(), ex.fieldErrors(), ex.details(), traceId(request));
        return ResponseEntity.status(code.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        int index = 0;
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage(), index++));
        }
        for (var globalError : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new ApiError.FieldError(globalError.getObjectName(), globalError.getDefaultMessage(), index++));
        }
        ApiError body = new ApiError(ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(), errors, Map.of(), traceId(request));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        int index = 0;
        for (var violation : ex.getConstraintViolations()) {
            errors.add(new ApiError.FieldError(violation.getPropertyPath().toString(),
                    violation.getMessage(), index++));
        }
        ApiError body = new ApiError(ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(), errors, Map.of(), traceId(request));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
        ApiError body = new ApiError(ErrorCode.VALIDATION_FAILED.name(), "请求格式不正确",
                List.of(), Map.of(), traceId(request));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("constraint", "database");
        ApiError body = new ApiError(ErrorCode.VALIDATION_FAILED.name(), "数据约束校验失败",
                List.of(), details, traceId(request));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ApiError body = new ApiError(ErrorCode.FORBIDDEN.name(), ErrorCode.FORBIDDEN.defaultMessage(),
                List.of(), Map.of(), traceId(request));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        ApiError body = new ApiError(ErrorCode.NOT_FOUND.name(), "接口不存在",
                List.of(), Map.of(), traceId(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("unhandled error traceId={} uri={}", traceId, request.getRequestURI(), ex);
        ApiError body = new ApiError(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage(),
                List.of(), Map.of(), traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute("traceId");
        if (existing instanceof String value) {
            return value;
        }
        String traceId = LocalDateTime.now().toString() + "-" + UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute("traceId", traceId);
        return traceId;
    }
}
