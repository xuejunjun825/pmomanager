package com.pmo.supervise.meta.dto;

import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.source.dto.SourceTypeOption;

import java.time.LocalDate;
import java.util.List;

/**
 * 筛选/表单下拉的元数据（TECHNICAL §5.5 / GET /api/meta/options）。
 * owners 对 OWNER 只返回本人；meetings 对 OWNER 只返回其任务涉及的会议。
 * 状态词来自枚举 label()，来源类型来自 source_types 配置——一份数据同时喂下拉、筛选与标签颜色（AC-5）。
 */
public record MetaOptions(
        List<OwnerOption> owners,
        List<MeetingOption> meetings,
        List<SourceTypeOption> sourceTypes,
        List<Option<TaskStatus>> statuses,
        List<Option<Priority>> priorities) {

    public record OwnerOption(long id, String name, Role role, UserStatus status) {
    }

    public record MeetingOption(long id, String title, LocalDate meetingDate) {
    }

    public record Option<T>(T value, String label) {
    }
}
