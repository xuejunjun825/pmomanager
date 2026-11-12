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
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    TASK_INVALID_TRANSITION(HttpStatus.CONFLICT, "当前状态下不能执行该动作"),
    TASK_SOURCE_IMMUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "任务来源不可修改"),
    TASK_FINISHED(HttpStatus.CONFLICT, "任务已结束"),
    URGE_TOO_SOON(HttpStatus.CONFLICT, "10 分钟内已催办过该任务"),
    DECISION_ITEM_LOCKED(HttpStatus.CONFLICT, "决议项已转任务，不可修改"),
    MEETING_ARCHIVED(HttpStatus.CONFLICT, "会议已归档，不可修改"),
    SOURCE_TYPE_INVALID(HttpStatus.BAD_REQUEST, "来源类型不合法"),
    SOURCE_TYPE_DISABLED(HttpStatus.BAD_REQUEST, "该来源已停用，请重新选择"),
    SOURCE_NAME_DUPLICATE(HttpStatus.CONFLICT, "名称已存在，请换一个"),
    SOURCE_LIMIT_REACHED(HttpStatus.CONFLICT, "来源类型最多 20 个"),
    SOURCE_BUILTIN_PROTECTED(HttpStatus.CONFLICT, "内置来源不可停用或删除"),
    SOURCE_IN_USE(HttpStatus.CONFLICT, "该来源已被任务使用，只能停用"),
    SOURCE_FORM_CONFLICT(HttpStatus.CONFLICT, "已有任务使用该来源且未绑定会议，无法改为会议型"),
    PROJECT_INVALID(HttpStatus.BAD_REQUEST, "项目不合法"),
    PROJECT_DISABLED(HttpStatus.BAD_REQUEST, "该项目已停用，请重新选择"),
    PROJECT_SOURCE_CONFLICT(HttpStatus.BAD_REQUEST, "项目来源不能填写说明或关联会议"),
    PROJECT_NAME_DUPLICATE(HttpStatus.CONFLICT, "名称已存在，请换一个"),
    PROJECT_LIMIT_REACHED(HttpStatus.CONFLICT, "项目最多 200 个"),
    PROJECT_IN_USE(HttpStatus.CONFLICT, "该项目已被任务使用，只能停用"),
    THEME_INVALID(HttpStatus.BAD_REQUEST, "主题不合法"),
    THEME_DISABLED(HttpStatus.BAD_REQUEST, "该主题已停用，请重新选择"),
    THEME_NAME_DUPLICATE(HttpStatus.CONFLICT, "名称已存在，请换一个"),
    THEME_LIMIT_REACHED(HttpStatus.CONFLICT, "主题最多 100 个"),
    THEME_IN_USE(HttpStatus.CONFLICT, "该主题已被任务使用，只能停用"),
    PROGRESS_FORBIDDEN(HttpStatus.FORBIDDEN, "你没有补充进度的权限"),
    PROGRESS_LIMIT_REACHED(HttpStatus.CONFLICT, "该任务进度已达 200 条上限"),
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
