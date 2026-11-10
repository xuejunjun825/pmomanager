package com.pmo.supervise.source.dto;

import com.pmo.supervise.source.SourceForm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 编辑来源（TECHNICAL §5.1）。字段级校验交给 Bean Validation，
 * 跨字段规则（重名、上限、形态切换、锁定）留在 SourceTypeService。
 */
public record SourceTypeRequest(
        @NotBlank(message = "来源名称不能为空")
        @Size(min = 2, max = 10, message = "来源名称需 2–10 个字")
        String name,

        @NotNull(message = "来源形态不能为空")
        SourceForm form,

        @NotBlank(message = "请选择来源颜色")
        String color,

        Integer sort) {
}
