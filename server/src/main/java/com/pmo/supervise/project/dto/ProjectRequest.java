package com.pmo.supervise.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新增 / 编辑项目（TECHNICAL §2.2）。
 *
 * <p>Bean Validation 只兜「空名称」；2–20 码点的业务上限在 ProjectService 内按码点判定，
 * 列宽（VARCHAR(40)）留有余量，不会因为长名称先撞库。
 */
public record ProjectRequest(@NotBlank(message = "项目名称不能为空") String name,
                             Integer sort,
                             Boolean enabled) {
}
