package com.pmo.supervise.auth;

import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.user.UserController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 认证/用户接口的错误码 → HTTP 状态修正（TECHNICAL §4.8 / §5.1）。
 *
 * <p>{@link ErrorCode} 由 T01 定义，其中 AUTH_DISABLED / AUTH_STATE_INVALID /
 * PASSWORD_CHANGE_REQUIRED / SELF_DISABLE_FORBIDDEN / AUTH_OLD_PASSWORD_WRONG 的状态与
 * 技术设计约定不一致（设计分别是 403 / 400 / 409 / 409 / 400）。公共枚举不在本任务边界内，
 * 因此在 auth 模块内用只作用于认证与用户控制器的 advice 做映射，其余错误码仍按 code.status() 返回，
 * 响应体形状与 GlobalExceptionHandler 完全一致。</p>
 */
@RestControllerAdvice(assignableTypes = {AuthController.class, UserController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionAdvice {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ApiError body = new ApiError(ex.code().name(), ex.getMessage(), ex.fieldErrors(),
                ex.details(), traceId(request));
        return ResponseEntity.status(statusOf(ex.code())).body(body);
    }

    static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case AUTH_DISABLED -> HttpStatus.FORBIDDEN;
            case AUTH_STATE_INVALID, AUTH_OLD_PASSWORD_WRONG -> HttpStatus.BAD_REQUEST;
            case PASSWORD_CHANGE_REQUIRED, SELF_DISABLE_FORBIDDEN -> HttpStatus.CONFLICT;
            default -> code.status();
        };
    }

    private String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute("traceId");
        if (existing instanceof String value) {
            return value;
        }
        String traceId = LocalDateTime.now() + "-" + UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute("traceId", traceId);
        return traceId;
    }
}
