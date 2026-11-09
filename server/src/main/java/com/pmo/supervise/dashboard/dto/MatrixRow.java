package com.pmo.supervise.dashboard.dto;

import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.UserStatus;

/**
 * 矩阵一行（前端 MatrixRow，TECHNICAL §5.5）。
 * 五列互斥：逾期任务已从原状态列移出，只计入 overdue；cancelled 单列统计、不进入五列。
 * total = pending + inProgress + pendingAccept + overdue + done（非取消），
 * completionRate = done / total，total = 0 时为 null（前端显示「—」）。
 * 合计行是本记录的哨兵：userId = 0，role / userStatus 为 null。
 */
public record MatrixRow(
        long userId,
        String name,
        Role role,
        UserStatus userStatus,
        long pending,
        long inProgress,
        long pendingAccept,
        long overdue,
        long done,
        long cancelled,
        long total,
        Double completionRate) {
}
