package com.pmo.supervise.meeting.dto;

import java.time.LocalDate;

/**
 * 会议列表行（前端 MeetingRow）：taskCount / unfinishedCount / closed 与任务状态联动，
 * 口径来自 tasks.source_meeting_id（TECHNICAL §4.4）。
 */
public record MeetingRow(
        long id,
        String title,
        LocalDate meetingDate,
        String host,
        boolean archived,
        long taskCount,
        long unfinishedCount,
        boolean closed) {
}
