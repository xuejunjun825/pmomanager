package com.pmo.supervise.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 会议新建 / 编辑请求（前端 MeetingRequest）：
 * 参会人传用户 id 列表，落库时以逗号分隔存于 meetings.attendees。
 */
public record MeetingRequest(
        @NotBlank(message = "请填写会议标题")
        @Size(max = 100, message = "会议标题不能超过 100 字")
        String title,

        @NotNull(message = "请选择会议日期")
        LocalDate meetingDate,

        @NotBlank(message = "请填写主持人")
        @Size(max = 64, message = "主持人不能超过 64 字")
        String host,

        List<Long> attendeeIds,

        @Size(max = 500, message = "备注不能超过 500 字")
        String note) {
}
