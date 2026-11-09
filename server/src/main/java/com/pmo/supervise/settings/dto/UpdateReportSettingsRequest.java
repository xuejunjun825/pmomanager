package com.pmo.supervise.settings.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/settings/report 的请求体（TECHNICAL §5.5）。
 *
 * <p>时间在入参层就按 HH:mm 校验（422 + fieldErrors）；关闭某通道时前端可能提交空时间，
 * 空串由 SettingService 按“沿用库中已有时间”处理，因此正则允许空值。收件人上限 20 与页面
 * chips 上限一致；名单语义是“指定领导名单”，允许非管理层人员，故不做角色限制。</p>
 */
public record UpdateReportSettingsRequest(
        @NotNull(message = "缺少日报开关")
        Boolean dailyEnabled,

        @Pattern(regexp = "^$|^([01]\\d|2[0-3]):[0-5]\\d$", message = "日报时间格式须为 HH:mm")
        String dailyTime,

        @NotNull(message = "缺少周报开关")
        Boolean weeklyEnabled,

        @Pattern(regexp = "^$|^([01]\\d|2[0-3]):[0-5]\\d$", message = "周报时间格式须为 HH:mm")
        String weeklyTime,

        @NotNull(message = "缺少收件人名单")
        @Size(max = 20, message = "收件人最多 20 位")
        List<Long> recipientIds) {
}
