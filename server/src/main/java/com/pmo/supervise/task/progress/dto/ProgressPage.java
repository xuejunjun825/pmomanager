package com.pmo.supervise.task.progress.dto;

import java.util.List;

/**
 * 进度分页（TECHNICAL §5.3）。total 同时驱动 200 条上限的禁用态，前端不需要自己数。
 */
public record ProgressPage(List<ProgressRow> items, long total, boolean hasMore) {
}
