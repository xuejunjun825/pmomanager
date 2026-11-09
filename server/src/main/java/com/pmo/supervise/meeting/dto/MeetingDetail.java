package com.pmo.supervise.meeting.dto;

import com.pmo.supervise.task.dto.UserRef;

import java.time.LocalDate;
import java.util.List;

/**
 * 会议详情（前端 MeetingDetail = MeetingRow + 参会人/备注/决议项）。
 * closed = taskCount > 0 且 unfinishedCount == 0；归档后仍可读（AC-5）。
 */
public record MeetingDetail(
        long id,
        String title,
        LocalDate meetingDate,
        String host,
        boolean archived,
        List<UserRef> attendees,
        String note,
        List<DecisionItemRow> items,
        long taskCount,
        long unfinishedCount,
        boolean closed) {
}
