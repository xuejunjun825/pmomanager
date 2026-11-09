package com.pmo.supervise.settings.dto;

import com.pmo.supervise.user.dto.UserRow;

import java.util.List;

/**
 * 报表设置（GET /api/settings 的 report 段，TECHNICAL §5.5）。
 * dailyTime / weeklyTime 用 "HH:mm" 文本，页面直接绑定时间输入框；
 * recipientsEmpty 是服务端算好的结论——名单为空时页面必须出现“未配置收件人”的提示（AC-20）。
 */
public record ReportSettings(
        boolean dailyEnabled,
        String dailyTime,
        boolean weeklyEnabled,
        String weeklyTime,
        List<UserRow> recipients,
        boolean recipientsEmpty) {
}
