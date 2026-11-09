package com.pmo.supervise.settings.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/settings 的响应（TECHNICAL §5.5，admin）。
 * dingtalk 段只暴露“是否已配置 + 缺失项名称”，绝不回显凭证值（AC-21）；
 * updatedAt 是最后一次设置变更时间，页面用它显示“上次更新”。
 */
public record SettingsView(
        ReportSettings report,
        DingtalkInfo dingtalk,
        LocalDateTime updatedAt) {

    public record DingtalkInfo(boolean configured, List<String> missingKeys) {
    }
}
