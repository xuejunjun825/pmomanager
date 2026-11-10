package com.pmo.supervise.notify;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.notify.dto.NotificationPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通知中心类型过滤（TECHNICAL §5.4 / AC-13）：@我的 tab 用服务端过滤，分页与未读计数才不错。 */
class NotificationPageTypeIT extends IntegrationTestSupport {

    @Autowired
    private NotificationService notificationService;

    private long owner;
    private long taskId;

    private void seed() {
        owner = seedOwner("owner1", "张三");
        taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        notificationService.createIfAbsent(new CreateNotificationCommand(
                owner, NotifyType.MENTION, taskId, "整理会议纪要",
                "张三 在任务《整理会议纪要》中@了你", "请补充口径",
                "mention:7:" + owner, "/tasks/" + taskId + "#progress-7", true));
        notificationService.createIfAbsent(new CreateNotificationCommand(
                owner, NotifyType.DUE_SOON, taskId, "整理会议纪要",
                "任务即将到期", "正文", "test:due:" + taskId, null, false));
        notificationService.createIfAbsent(new CreateNotificationCommand(
                owner, NotifyType.OVERDUE, taskId, "整理会议纪要",
                "任务已逾期", "正文", "test:overdue:" + taskId, null, false));
    }

    @Test
    void typeFilterNarrowsToMentionsOnly() {
        seed();

        NotificationPage mentions = notificationService.page(owner, false, "MENTION", 1, 20);

        assertThat(mentions.total()).isEqualTo(1);
        assertThat(mentions.items()).hasSize(1);
        assertThat(mentions.items().get(0).type()).isEqualTo(NotifyType.MENTION);
        assertThat(mentions.unreadCount()).isEqualTo(3);

        NotificationPage all = notificationService.page(owner, false, null, 1, 20);
        assertThat(all.total()).isEqualTo(3);
    }

    @Test
    void unreadOnlyCombinesWithType() {
        seed();
        Long mentionId = notificationService.page(owner, false, "MENTION", 1, 20).items().get(0).id();
        notificationService.markRead(mentionId, owner);

        assertThat(notificationService.page(owner, true, "MENTION", 1, 20).total()).isZero();
        assertThat(notificationService.page(owner, false, "MENTION", 1, 20).total()).isEqualTo(1);
        assertThat(notificationService.page(owner, true, null, 1, 20).total()).isEqualTo(2);
        assertThat(notificationService.unreadCount(owner)).isEqualTo(2);
    }

    @Test
    void invalidTypeIs422() {
        seed();

        assertThatThrownBy(() -> notificationService.page(owner, false, "NOPE", 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).contains("通知类型不合法");
                });
    }

    @Test
    void lowercaseTypeIsAccepted() {
        seed();

        assertThat(notificationService.page(owner, false, "mention", 1, 20).total()).isEqualTo(1);
    }
}
