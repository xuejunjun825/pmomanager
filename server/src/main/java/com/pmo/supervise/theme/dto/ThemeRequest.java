package com.pmo.supervise.theme.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 主题写请求（TECHNICAL §3.1）：与 {@code ProjectRequest} 同构。
 * 长度、色板、上限都属业务规则，在 {@code ThemeService} 单点判定；
 * color 可空表示「按序分配下一个预设色」（AC-8）。
 */
public record ThemeRequest(
        @NotBlank String name,
        String color,
        Integer sort,
        Boolean enabled) {
}
