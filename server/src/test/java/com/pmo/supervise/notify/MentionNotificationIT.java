package com.pmo.supervise.notify;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.NotifyType;
import com.pmo.supervise.notify.dto.CreateNotificationCommand;
import com.pmo.supervise.notify.dto.NotificationPage;
import com.pmo.supervise.notify.dto.NotificationRow;
import com.pmo.supervise.task.progress.TaskProgressService;
import com.pmo.supervise.task.progress.dto.AddProgressRequest;
import com.pmo.supervise.task.progress.dto.ProgressRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 被@通知（TECHNICAL §4.7 / AC-10）：一条进度每人一条、带进度锚点、未配置钉钉按降级处理。 */
class MentionNotificationIT extends IntegrationTestSupport {

    @Autowired
    private TaskProgressService progressService;

    @Autowired
    private NotificationService notificationService;

    private long owner;
    private long helper;
    private long taskId;

    private void seed() {
        owner = seedOwner("owner1", "张三");
        helper = seedOwner("helper", "李四");
        taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
    }

    @Test
    void mentionWritesOneNotificationWithProgressAnchor() {
        seed();

        ProgressRow progress = progressService.add(taskId, actor(owner),
                new AddProgressRequest("请李四补充口径", List.of(helper)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT type, task_id, progress_id, dedup_key, title, body, read_at, dingtalk_status
                FROM notifications WHERE user_id = ?
                """, helper);
        assertThat(row.get("type")).isEqualTo("MENTION");
        assertThat(((Number) row.get("task_id")).longValue()).isEqualTo(taskId);
        assertThat(((Number) row.get("progress_id")).longValue()).isEqualTo(progress.id());
        assertThat(row.get("dedup_key")).isEqualTo("mention:" + progress.id() + ":" + helper);
        assertThat((String) row.get("title")).contains("@了你").contains("整理会议纪要");
        assertThat(row.get("read_at")).isNull();
        assertThat(row.get("dingtalk_status")).isEqualTo("NOT_CONFIGURED");

        NotificationPage page = notificationService.page(helper, false, "MENTION", 1, 20);
        assertThat(page.total()).isEqualTo(1);
        NotificationRow item = page.items().get(0);
        assertThat(item.type()).isEqualTo(NotifyType.MENTION);
        assertThat(item.typeLabel()).isEqualTo("被@");
        assertThat(item.progressId()).isEqualTo(progress.id());
        assertThat(item.read()).isFalse();
        assertThat(page.unreadCount()).isEqualTo(1);
    }

    @Test
    void selfMentionNotifiesNobody() {
        seed();

        progressService.add(taskId, actor(owner), new AddProgressRequest("自己记一笔", List.of(owner)));

        assertThat(notificationCount()).isZero();
    }

    @Test
    void duplicateCommandIsIdempotentByDedupKey() {
        seed();

        CreateNotificationCommand command = new CreateNotificationCommand(
                helper, NotifyType.MENTION, taskId, "整理会议纪要",
                "张三 在任务《整理会议纪要》中@了你", "请补充口径",
                "mention:99:" + helper, "/tasks/" + taskId + "#progress-99", true);

        assertThat(notificationService.createIfAbsent(command)).isPresent();
        assertThat(notificationService.createIfAbsent(command)).isEmpty();
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    void longProgressIsTruncatedToHundredCodePoints() {
        seed();
        String longContent = "进度".repeat(60);

        progressService.add(taskId, actor(owner), new AddProgressRequest(longContent, List.of(helper)));

        String body = jdbc.queryForObject(
                "SELECT body FROM notifications WHERE user_id = ?", String.class, helper);
        assertThat(body.codePointCount(0, body.length())).isEqualTo(101);
        assertThat(body).endsWith("…");
    }

    @Test
    void mentionWithoutTaskTitleStillStoresTaskReference() {
        seed();
        ProgressRow progress = progressService.add(taskId, actor(owner),
                new AddProgressRequest("补充一句", List.of(helper)));

        String dedupKey = jdbc.queryForObject(
                "SELECT dedup_key FROM notifications WHERE progress_id = ?", String.class, progress.id());
        assertThat(dedupKey).startsWith("mention:");
    }
}
