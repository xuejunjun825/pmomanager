package com.pmo.supervise.common;

import org.springframework.http.HttpStatus;

/**
 * 全量错误码（TECHNICAL §7）。HTTP 状态与默认文案由错误码承载，
 * Controller 不写业务分支，GlobalExceptionHandler 统一渲染。
 */
public enum ErrorCode {
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "未登录或会话已失效"),
    AUTH_BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_DISABLED(HttpStatus.UNAUTHORIZED, "账号已停用，请联系管理员"),
    AUTH_STATE_INVALID(HttpStatus.UNAUTHORIZED, "登录状态校验失败，请重新登录"),
    AUTH_PASSWORD_WEAK(HttpStatus.UNPROCESSABLE_ENTITY, "新密码不符合强度要求"),
    AUTH_OLD_PASSWORD_WRONG(HttpStatus.UNPROCESSABLE_ENTITY, "原密码不正确"),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "首次登录需先修改密码"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行该操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "对象不存在或无权访问"),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "请求参数校验失败"),
    TASK_INVALID_TRANSITION(HttpStatus.CONFLICT, "当前状态下不能执行该动作"),
    TASK_SOURCE_IMMUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "任务来源不可修改"),
    TASK_FINISHED(HttpStatus.CONFLICT, "任务已结束"),
    URGE_TOO_SOON(HttpStatus.CONFLICT, "10 分钟内已催办过该任务"),
    DECISION_ITEM_LOCKED(HttpStatus.CONFLICT, "决议项已转任务，不可修改"),
    MEETING_ARCHIVED(HttpStatus.CONFLICT, "会议已归档，不可修改"),
    SELF_DISABLE_FORBIDDEN(HttpStatus.UNPROCESSABLE_ENTITY, "不能停用当前登录账号"),
    DINGTALK_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "钉钉未配置"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
