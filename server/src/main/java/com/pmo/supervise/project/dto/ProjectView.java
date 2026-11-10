package com.pmo.supervise.project.dto;

/**
 * 项目配置行（/api/projects 列表与写入返回）。
 *
 * <p>与 {@link ProjectOption} 刻意分开：usageCount 是配置页才需要的管理字段，
 * meta 读侧不下发（AC-8 最小暴露）。
 */
public record ProjectView(long id, String code, String name, int sort, boolean enabled, long usageCount) {
}
