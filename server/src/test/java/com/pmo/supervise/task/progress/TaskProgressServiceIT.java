package com.pmo.supervise.task.progress;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.task.progress.dto.AddProgressRequest;
import com.pmo.supervise.task.progress.dto.ProgressPage;
import com.pmo.supervise.task.progress.dto.ProgressRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 进度写入规则（TECHNICAL §4.4 / AC-6 / AC-8 / AC-9 / AC-10）。 */
class TaskProgressServiceIT extends IntegrationTestSupport {

    @Autowired
    private TaskProgressService service;

    private long owner;
    private long taskId;

    private void seed() {
        owner = seedOwner("owner1", "张三");
        taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
    }

    @Test
    void addStoresAuthorMentionsAndTotal() {
        seed();
        long helper = seedOwner("helper", "李四");

        ProgressRow row = service.add(taskId, actor(owner),
                new AddProgressRequest("  已整理前半部分  ", List.of(helper, helper)));

        assertThat(row.id()).isPositive();
        assertThat(row.content()).isEqualTo("已整理前半部分");
        assertThat(row.author().id()).isEqualTo(owner);
        assertThat(row.author().name()).isEqualTo("张三");
        assertThat(row.mentions()).extracting(ref -> ref.id()).containsExactly(helper);
        assertThat(row.mentionedMe()).isFalse();
        assertThat(row.createdAt()).isNotNull();
        assertThat(service.page(taskId, actor(owner), 50, null).total()).isEqualTo(1);
    }

    @Test
    void mentioningYourselfIsDropped() {
        seed();

        ProgressRow row = service.add(taskId, actor(owner),
                new AddProgressRequest("自己记一笔", List.of(owner)));

        assertThat(row.mentions()).isEmpty();
        assertThat(row.mentionedMe()).isFalse();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void moreThanTenMentionsIsRejected() {
        seed();
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            users.add(seedOwner("member" + i, "成员" + i));
        }

        assertThatThrownBy(() -> service.add(taskId, actor(owner),
                new AddProgressRequest("全员阅", users)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).contains("最多 @ 10 人");
                });
        assertThat(notificationCount()).isZero();
    }

    @Test
    void disabledMemberCannotBeMentioned() {
        seed();
        long disabled = seedUser("gone", "已停用", com.pmo.supervise.common.Role.OWNER, "DISABLED");

        assertThatThrownBy(() -> service.add(taskId, actor(owner),
                new AddProgressRequest("找停用人", List.of(disabled))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getMessage()).contains("只能 @ 启用成员"));
    }

    @Test
    void contentMustBeOneToFiveHundred() {
        seed();

        assertThatThrownBy(() -> service.add(taskId, actor(owner), new AddProgressRequest("   ", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.add(taskId, actor(owner),
                new AddProgressRequest("字".repeat(501), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getMessage()).contains("1–500 字"));

        ProgressRow row = service.add(taskId, actor(owner), new AddProgressRequest("字".repeat(500), null));
        assertThat(row.content()).hasSize(500);
    }

    @Test
    void twoHundredEntriesIsTheHardLimit() {
        seed();
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO task_progress (task_id, author_id, content) VALUES (?, ?, ?)",
                    taskId, owner, "历史进度 " + i);
        }

        assertThatThrownBy(() -> service.add(taskId, actor(owner), new AddProgressRequest("第 201 条", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PROGRESS_LIMIT_REACHED));
        assertThat(service.page(taskId, actor(owner), 50, null).total()).isEqualTo(200);
    }

    @Test
    void pageIsNewestFirstAndPaginatesByBeforeId() {
        seed();
        ProgressRow first = service.add(taskId, actor(owner), new AddProgressRequest("第一条", null));
        service.add(taskId, actor(owner), new AddProgressRequest("第二条", null));
        ProgressRow third = service.add(taskId, actor(owner), new AddProgressRequest("第三条", null));

        ProgressPage page = service.page(taskId, actor(owner), 2, null);
        assertThat(page.items()).extracting(ProgressRow::content).containsExactly("第三条", "第二条");
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasMore()).isTrue();

        ProgressPage next = service.page(taskId, actor(owner), 2, page.items().get(1).id());
        assertThat(next.items()).extracting(ProgressRow::content).containsExactly("第一条");
        assertThat(next.hasMore()).isFalse();
        assertThat(next.items().get(0).id()).isEqualTo(first.id());
        assertThat(third.id()).isGreaterThan(first.id());
    }
}
