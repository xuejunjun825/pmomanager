package com.pmo.supervise.project.dto;

/**
 * meta 下发的项目最小字段（AC-8）：全员可读，不含 code / usageCount。
 *
 * <p>停用项目也下发——历史任务仍要显示项目名，前端用 enabled 决定后缀与可选项。
 */
public record ProjectOption(long id, String name, boolean enabled, int sort) {
}
