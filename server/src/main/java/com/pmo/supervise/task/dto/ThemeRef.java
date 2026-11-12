package com.pmo.supervise.task.dto;

/**
 * 任务行上的主题快照（r7 / AC-9）。三色由服务端从 themes.color 派生，
 * 前端只渲染不解析 hex（ThemeTag 是唯一渲染点）。
 *
 * <p>未归主题的任务该字段为 {@code null}，前端统一渲染「未归主题」。
 */
public record ThemeRef(
        long id,
        String name,
        String color,
        String softColor,
        String borderColor,
        boolean enabled) {
}
