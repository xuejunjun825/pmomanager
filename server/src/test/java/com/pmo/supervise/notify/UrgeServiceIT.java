package com.pmo.supervise.notify;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.notify.dto.UrgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一键催办（TECHNICAL §4.5）：10 分钟窗口按 (任务, 操作人) 生效，
 * 已结束任务拒绝催办；渠道未配置时明确返回 NOT_CONFIGURED 而不是报错。
 */
class UrgeServiceIT extends IntegrationTestSupport {

    @Autowired
    private UrgeService urgeService;

    @Test
    @DisplayName("首次催办成功并记录渠道结果，10 分钟内同人再催 409 带 lastUrgedAt")
    void urgeWindowIsPerTaskAndActor() {
        long owner = seedOwner("urge-owner", "被催办人");
        long exec = seedExec("urge-exec", "督办人");
        long exec2 = seedExec("urge-exec2", "另一督办人");
        long taskId = seedTask("准备上会材料", owner, LocalDate.of(2026, 11, 12), "IN_PROGRESS", adminId);

        UrgeResult first = urgeService.urge(taskId, actor(exec));
        assertThat(first.urgeCount()).isEqualTo(1);
        assertThat(first.channelStatus()).isEqualTo(DingTalkStatus.NOT_CONFIGURED);
        assertThat(first.channelLabel()).isEqualTo("钉钉未配置");
        assertThat(notificationCount()).isEqualTo(1);

        assertThatThrownBy(() -> urgeService.urge(taskId, actor(exec)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.code()).isEqualTo(ErrorCode.URGE_TOO_SOON);
                    assertThat(be.details()).containsKey("lastUrgedAt");
                });

        // 窗口按 (任务, 操作人)：另一人可以立即催办
        UrgeResult byOther = urgeService.urge(taskId, actor(exec2));
        assertThat(byOther.urgeCount()).isEqualTo(2);
        assertThat(notificationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("已结束任务催办返回 409 TASK_FINISHED")
    void finishedTaskCannotBeUrged() {
        long owner = seedOwner("urge-owner2", "负责人");
        long exec = seedExec("urge-exec3", "督办人");
        long taskId = seedTask("已收尾的任务", owner, LocalDate.of(2026, 11, 1), "DONE", adminId);

        assertThatThrownBy(() -> urgeService.urge(taskId, actor(exec)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.TASK_FINISHED));
    }

    @Test
    @DisplayName("负责人无权催办（403）")
    void ownerCannotUrge() {
        long owner = seedOwner("urge-owner3", "负责人");
        long taskId = seedTask("普通任务", owner, LocalDate.of(2026, 11, 12), "PENDING", adminId);

        assertThatThrownBy(() -> urgeService.urge(taskId, actor(owner)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
