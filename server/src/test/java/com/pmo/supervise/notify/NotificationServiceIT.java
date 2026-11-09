package com.pmo.supervise.notify;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.notify.dto.NotificationPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知幂等（TECHNICAL §4.6）：先插入再捕唯一键冲突，并发下也不会重复；
 * 未配置钉钉立即降级为 NOT_CONFIGURED，不重试也不抛异常。
 */
class NotificationServiceIT extends IntegrationTestSupport {

    @Autowired
    private NotificationService notificationService;

    @Test
    @DisplayName("同一 dedup_key 重复创建只落一行，第二次返回 empty")
    void createIfAbsentIsIdempotent() {
        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("准备月度汇报", owner, LocalDate.of(2026, 11, 10), "PENDING", adminId);
        CreateNotificationCommand cmd = new CreateNotificationCommand(
                owner, NotifyType.OVERDUE, taskId, "准备月度汇报",
                "任务已逾期", "正文", NotificationKeys.daily(taskId, clock.today(), NotifyType.OVERDUE),
                "/tasks/" + taskId, false);

        Optional<Notification> first = notificationService.createIfAbsent(cmd);
        Optional<Notification> second = notificationService.createIfAbsent(cmd);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(first.get().getDingtalkStatus()).isEqualTo(DingTalkStatus.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("未读列表与已读操作按本人隔离，越权读他人通知 404")
    void readStateIsPerUser() {
        long owner = seedOwner("owner2", "李四");
        long other = seedOwner("owner3", "王五");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 10), "PENDING", adminId);
        long notificationId = notificationService.createIfAbsent(new CreateNotificationCommand(
                owner, NotifyType.DUE_SOON, taskId, "整理会议纪要",
                "任务即将到期", "正文", "test:read:" + taskId, null, false)).orElseThrow().getId();

        NotificationPage page = notificationService.page(owner, true, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(page.items().get(0).read()).isFalse();

        assertThatThrownBy(() -> notificationService.markRead(notificationId, other))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo(ErrorCode.NOT_FOUND));

        notificationService.markRead(notificationId, owner);
        assertThat(notificationService.unreadCount(owner)).isZero();
        assertThat(notificationService.page(owner, true, 1, 20).total()).isZero();
        assertThat(notificationService.page(owner, false, 1, 20).items().get(0).read()).isTrue();
    }

    @Test
    @DisplayName("全部已读返回本次更新条数")
    void markAllReadUpdatesOnlyOwnRows() {
        long owner = seedOwner("owner4", "赵六");
        long other = seedOwner("owner5", "孙七");
        long taskId = seedTask("跟进预算", owner, LocalDate.of(2026, 11, 10), "PENDING", adminId);
        for (int i = 0; i < 3; i++) {
            notificationService.createIfAbsent(new CreateNotificationCommand(
                    owner, NotifyType.DUE_SOON, taskId, "跟进预算", "标题" + i, "正文",
                    "test:all:" + taskId + ":" + i, null, false));
        }
        notificationService.createIfAbsent(new CreateNotificationCommand(
                other, NotifyType.DUE_SOON, taskId, "跟进预算", "别人的", "正文",
                "test:all:" + taskId + ":other", null, false));

        assertThat(notificationService.markAllRead(owner)).isEqualTo(3);
        assertThat(notificationService.unreadCount(owner)).isZero();
        assertThat(notificationService.unreadCount(other)).isEqualTo(1);
    }

    @Test
    @DisplayName("列表行返回钉钉实际重试次数与最近失败原因（D-04）")
    void pageExposesRetryCountAndFailureReason() {
        long owner = seedOwner("owner6", "周八");
        long taskId = seedTask("复盘投放效果", owner, LocalDate.of(2026, 11, 10), "PENDING", adminId);
        long notificationId = notificationService.createIfAbsent(new CreateNotificationCommand(
                owner, NotifyType.OVERDUE, taskId, "复盘投放效果",
                "任务已逾期", "正文", "test:retry:" + taskId, null, false)).orElseThrow().getId();
        jdbc.update("""
                UPDATE notifications
                SET dingtalk_status = 'FAILED', dingtalk_attempts = 2, dingtalk_last_error = '发送失败'
                WHERE id = ?
                """, notificationId);

        NotificationPage page = notificationService.page(owner, false, 1, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).retryCount()).isEqualTo(2);
        assertThat(page.items().get(0).failureReason()).isEqualTo("发送失败");
    }
}
