package com.pmo.supervise.task.progress.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 追加进度（TECHNICAL §5.3）。长度上限由 Bean Validation 与库 CHECK 双兜底；
 * mentionedUserIds 只有选择器选中才会出现，手打 @ 不授予可见性（AC-9）。
 */
public record AddProgressRequest(
        @NotBlank(message = "进度内容不能为空")
        @Size(max = 500, message = "进度内容不能超过 500 字")
        String content,

        List<Long> mentionedUserIds) {
}
