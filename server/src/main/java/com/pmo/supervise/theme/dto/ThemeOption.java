package com.pmo.supervise.theme.dto;

/**
 * 主题下拉选项（TECHNICAL §3.1）：供 /api/meta/options 与任务表单使用。
 * 与 {@link ThemeView} 分开是有意的——选项不进 usageCount，meta 接口不做 count 子查询（AC-8）。
 */
public record ThemeOption(
        long id,
        String name,
        String color,
        String softColor,
        String borderColor,
        int sort,
        boolean enabled) {
}
