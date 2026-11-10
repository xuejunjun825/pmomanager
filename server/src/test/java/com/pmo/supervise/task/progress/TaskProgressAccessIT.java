package com.pmo.supervise.task.progress;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.task.TaskService;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.progress.dto.AddProgressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可见性两分（TECHNICAL §4.5 / AC-7 / AC-8）：被@人获得只读可见与续写权；
 * 非可查看者补进度 403，而详情仍然 404——两个语义不能共用同一个 notFound。
 */
class TaskProgressAccessIT extends IntegrationTestSupport {

    @Autowired
    private TaskProgressService progressService;

    @Autowired
    private TaskService taskService;

    private final LocalDate today = LocalDate.of(2026, 11, 9);

    @Test
    void ownerExecAndAdminCanAlwaysWrite() {
        long owner = seedOwner("owner1", "张三");
        long exec = seedExec("exec1", "李四");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        progressService.add(taskId, actor(owner), new AddProgressRequest("负责人补充", null));
        progressService.add(taskId, actor(exec), new AddProgressRequest("管理层补充", null));
        progressService.add(taskId, actor(adminId), new AddProgressRequest("管理员补充", null));

        assertThat(progressService.page(taskId, actor(owner), 50, null).total()).isEqualTo(3);
    }

    @Test
    void strangerGets403OnProgressBut404OnDetail() {
        long owner = seedOwner("owner1", "张三");
        long stranger = seedOwner("stranger", "王五");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        assertThatThrownBy(() -> progressService.add(taskId, actor(stranger),
                new AddProgressRequest("插一句", null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.PROGRESS_FORBIDDEN);
                    assertThat(ex.code().status().value()).isEqualTo(403);
                });

        assertThatThrownBy(() -> taskService.get(taskId, actor(stranger), today))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> progressService.page(taskId, actor(stranger), 50, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void mentionedUserSeesDetailInMentionOnlyView() {
        long owner = seedOwner("owner1", "张三");
        long helper = seedOwner("helper", "李四");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        progressService.add(taskId, actor(owner),
                new AddProgressRequest("请李四补充口径", List.of(helper)));

        TaskDetailRow detail = taskService.get(taskId, actor(helper), today);

        assertThat(detail.id()).isEqualTo(taskId);
        assertThat(detail.permissions().canAddProgress()).isTrue();
        assertThat(detail.permissions().mentionOnly()).isTrue();
        assertThat(detail.permissions().canStart()).isFalse();
        assertThat(detail.permissions().canAccept()).isFalse();
        assertThat(detail.permissions().canReassign()).isFalse();
    }

    @Test
    void mentionedUserCanContinueWritingProgress() {
        long owner = seedOwner("owner1", "张三");
        long helper = seedOwner("helper", "李四");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        progressService.add(taskId, actor(owner),
                new AddProgressRequest("请李四补充口径", List.of(helper)));

        progressService.add(taskId, actor(helper), new AddProgressRequest("口径已补充", null));

        assertThat(progressService.page(taskId, actor(helper), 50, null).total()).isEqualTo(2);
        assertThat(progressService.page(taskId, actor(helper), 50, null).items().get(0).author().id())
                .isEqualTo(helper);
    }

    @Test
    void beingMentionedOnceIsEnoughForLaterVisibility() {
        long owner = seedOwner("owner1", "张三");
        long helper = seedOwner("helper", "李四");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        progressService.add(taskId, actor(owner), new AddProgressRequest("先自己记一笔", null));
        assertThatThrownBy(() -> taskService.get(taskId, actor(helper), today))
                .isInstanceOf(BusinessException.class);

        progressService.add(taskId, actor(owner),
                new AddProgressRequest("再请李四确认", List.of(helper)));
        assertThat(taskService.get(taskId, actor(helper), today).id()).isEqualTo(taskId);
    }

    @Test
    void ownerViewIsNotMarkedAsMentionOnly() {
        long owner = seedOwner("owner1", "张三");
        long taskId = seedTask("整理会议纪要", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);

        TaskDetailRow detail = taskService.get(taskId, actor(owner), today);
        assertThat(detail.permissions().mentionOnly()).isFalse();
        assertThat(detail.permissions().canAddProgress()).isTrue();
        assertThat(detail.permissions().canStart()).isTrue();
    }
}
