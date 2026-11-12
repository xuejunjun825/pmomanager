package com.pmo.supervise.theme.dto;

/**
 * 主题视图（TECHNICAL §3.1）：配置页与任务标签共用。
 * softColor/borderColor 由服务端用 {@code SourcePalette.of(color)} 派生，前端不解析 hex，
 * 也不新增 CSS 令牌（AC-8）；usageCount 只在这里出现，meta 选项不带计数子查询。
 */
public record ThemeView(
        long id,
        String name,
        String color,
        String softColor,
        String borderColor,
        int sort,
        boolean enabled,
        long usageCount) {
}
