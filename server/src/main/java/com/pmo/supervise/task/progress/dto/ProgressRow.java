package com.pmo.supervise.task.progress.dto;

import com.pmo.supervise.task.dto.UserRef;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 进度行（TECHNICAL §5.3）。一次带上作者与 @ 名单，时间线渲染不再逐条查用户。
 */
public record ProgressRow(
        long id,
        String content,
        UserRef author,
        List<UserRef> mentions,
        boolean mentionedMe,
        LocalDateTime createdAt) {

    public ProgressRow withMentionedMe(boolean value) {
        return new ProgressRow(id, content, author, mentions, value, createdAt);
    }
}
