package com.pmo.supervise.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 密码登录请求。字段缺失按 422 返回（由全局异常处理统一渲染 fieldErrors）。 */
public record LoginRequest(
        @NotBlank(message = "请输入账号")
        @Size(max = 64, message = "账号长度不能超过 64 个字符")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password) {
}
