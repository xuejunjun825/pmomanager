package com.pmo.supervise.meeting;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.meeting.dto.ConvertResult;
import com.pmo.supervise.meeting.dto.DecisionItemRequest;
import com.pmo.supervise.meeting.dto.DecisionItemRow;
import com.pmo.supervise.meeting.dto.MeetingDetail;
import com.pmo.supervise.meeting.dto.MeetingRequest;
import com.pmo.supervise.meeting.dto.MeetingRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T05 会议与决议（AC-3 / AC-4 / AC-5）：
 * 逐项 422 带 index、convert 幂等（重复进 skipped）、转任务后锁定、归档只读不删、闭环与任务状态联动。
 */
class MeetingServiceIT extends IntegrationTestSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Autowired
    private MeetingService meetingService;

    @Autowired
    private DecisionConversionService conversionService;

    private Actor admin() {
        return actor(adminId);
    }

    private MeetingDetail newMeeting(String title, long... attendeeIds) {
        List<Long> ids = java.util.Arrays.stream(attendeeIds).boxed().toList();
        return meetingService.create(admin(), new MeetingRequest(title, LocalDate.of(2026, 11, 1),
                "主持人", ids, "周会纪要"), clock.today());
    }

    private long addItem(long meetingId, String description, long ownerId) {
        return meetingService.addItem(meetingId, admin(),
                new DecisionItemRequest(description, ownerId, DAY, null), clock.today()).id();
    }

    @Test
    void createMeetingPersistsAttendeesAndIsListedWithZeroCounts() {
        long owner1 = seedOwner("zhang", "张三");
        long owner2 = seedOwner("li", "李四");

        MeetingDetail detail = newMeeting("周例会", owner1, owner2);

        assertThat(detail.title()).isEqualTo("周例会");
        assertThat(detail.attendees()).extracting("id").containsExactly(owner1, owner2);
        assertThat(detail.note()).isEqualTo("周会纪要");
        assertThat(detail.archived()).isFalse();
        assertThat(detail.taskCount()).isZero();
        assertThat(detail.unfinishedCount()).isZero();
        assertThat(detail.closed()).isFalse();
        assertThat(detail.items()).isEmpty();

        assertThat(meetingService.list(false, "周例", 1, 20, admin()).items())
                .extracting(MeetingRow::title).containsExactly("周例会");
        assertThat(meetingService.list(false, "不存在", 1, 20, admin()).total()).isZero();
    }

    @Test
    void addItemReportsFieldErrorsWithCurrentRowIndex() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        addItem(meetingId, "事项一", owner);
        addItem(meetingId, "事项二", owner);

        assertThatThrownBy(() -> meetingService.addItem(meetingId, admin(),
                new DecisionItemRequest("  ", null, null, null), clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting("field")
                            .containsExactlyInAnyOrder("description", "ownerId", "dueDate");
                    assertThat(ex.fieldErrors()).extracting("index").containsOnly(2);
                });
        assertThat(meetingService.get(meetingId, clock.today()).items()).hasSize(2);
    }

    @Test
    void updateItemReportsIndexInCurrentOrder() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        long first = addItem(meetingId, "事项一", owner);
        long second = addItem(meetingId, "事项二", owner);

        assertThatThrownBy(() -> meetingService.updateItem(second, admin(),
                new DecisionItemRequest("事项二", null, DAY, null), clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.fieldErrors()).singleElement()
                                .satisfies(e -> assertThat(e.index()).isEqualTo(1)));

        DecisionItemRow updated = meetingService.updateItem(first, admin(),
                new DecisionItemRequest("事项一（改）", owner, DAY.plusDays(2), "交付说明"), clock.today());
        assertThat(updated.description()).isEqualTo("事项一（改）");
        assertThat(updated.deliveryNote()).isEqualTo("交付说明");
        assertThat(updated.converted()).isFalse();
    }

    @Test
    void convertCreatesTaskWithMeetingSourceAndIsIdempotent() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        long itemA = addItem(meetingId, "事项A", owner);
        long itemB = addItem(meetingId, "事项B", owner);

        ConvertResult first = conversionService.convert(meetingId, admin(), List.of(itemA, itemB));

        assertThat(first.created()).extracting("itemId").containsExactly(itemA, itemB);
        assertThat(first.skipped()).isEmpty();
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT * FROM tasks WHERE source_decision_item_id = ?", itemA);
        assertThat(task.get("title")).isEqualTo("事项A");
        assertThat(((Number) task.get("owner_id")).longValue()).isEqualTo(owner);
        assertThat(task.get("source_type")).isEqualTo("MEETING");
        assertThat(((Number) task.get("source_meeting_id")).longValue()).isEqualTo(meetingId);
        assertThat(((Number) task.get("dispatcher_id")).longValue()).isEqualTo(adminId);
        assertThat(task.get("status")).isEqualTo("PENDING");
        assertThat(task.get("priority")).isEqualTo("MEDIUM");
        assertThat(task.get("due_date")).isEqualTo(java.sql.Date.valueOf(DAY));

        MeetingDetail afterConvert = meetingService.get(meetingId, clock.today());
        assertThat(afterConvert.taskCount()).isEqualTo(2);
        assertThat(afterConvert.unfinishedCount()).isEqualTo(2);
        assertThat(afterConvert.closed()).isFalse();
        assertThat(afterConvert.items()).allSatisfy(item -> {
            assertThat(item.converted()).isTrue();
            assertThat(item.taskId()).isNotNull();
            assertThat(item.taskStatus()).isEqualTo(TaskStatus.PENDING);
        });

        ConvertResult second = conversionService.convert(meetingId, admin(), List.of(itemA, itemB));
        assertThat(second.created()).isEmpty();
        assertThat(second.skipped()).hasSize(2).allSatisfy(skipped -> {
            assertThat(skipped.reason()).isEqualTo(ConvertResult.Skipped.ALREADY_CONVERTED);
            assertThat(skipped.taskId()).isNotNull();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isEqualTo(2);
    }

    @Test
    void convertIsPartialAndLocksConvertedItems() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        long itemA = addItem(meetingId, "事项A", owner);
        long itemB = addItem(meetingId, "事项B", owner);
        conversionService.convert(meetingId, admin(), List.of(itemA));

        ConvertResult partial = conversionService.convert(meetingId, admin(), List.of(itemA, itemB));
        assertThat(partial.created()).extracting("itemId").containsExactly(itemB);
        assertThat(partial.skipped()).extracting("itemId").containsExactly(itemA);

        assertThatThrownBy(() -> meetingService.updateItem(itemA, admin(),
                new DecisionItemRequest("改标题", owner, DAY, null), clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.DECISION_ITEM_LOCKED));
        assertThatThrownBy(() -> meetingService.deleteItem(itemA, admin()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.DECISION_ITEM_LOCKED));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_items", Integer.class)).isEqualTo(2);
    }

    @Test
    void convertRejectsItemsOfAnotherMeetingAndMissingItems() {
        long owner = seedOwner("zhang", "张三");
        long meetingA = newMeeting("会议A").id();
        long meetingB = newMeeting("会议B").id();
        long itemA = addItem(meetingA, "A的事项", owner);

        assertThatThrownBy(() -> conversionService.convert(meetingB, admin(), List.of(itemA)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> conversionService.convert(meetingA, admin(), List.of(999999L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
    }

    @Test
    void archiveHidesFromDefaultListAndFreezesMeeting() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        addItem(meetingId, "事项A", owner);

        MeetingDetail archived = meetingService.archive(meetingId, admin(), clock.today());
        assertThat(archived.archived()).isTrue();
        assertThat(archived.items()).hasSize(1);

        assertThat(meetingService.list(false, null, 1, 20, admin()).total()).isZero();
        assertThat(meetingService.list(true, null, 1, 20, admin()).total()).isEqualTo(1);
        assertThat(meetingService.get(meetingId, clock.today()).archived()).isTrue();

        assertThatThrownBy(() -> meetingService.update(meetingId, admin(),
                new MeetingRequest("改名", LocalDate.of(2026, 11, 1), "主持人", List.of(), null),
                clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.MEETING_ARCHIVED));
        assertThatThrownBy(() -> meetingService.addItem(meetingId, admin(),
                new DecisionItemRequest("新增", owner, DAY, null), clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.MEETING_ARCHIVED));

        // 幂等：重复归档不报错，也不改变状态
        assertThat(meetingService.archive(meetingId, admin(), clock.today()).archived()).isTrue();
    }

    @Test
    void archivedMeetingKeepsTaskSourceReadable() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        long itemId = addItem(meetingId, "事项A", owner);
        ConvertResult result = conversionService.convert(meetingId, admin(), List.of(itemId));
        long taskId = result.created().get(0).taskId();

        meetingService.archive(meetingId, admin(), clock.today());

        MeetingDetail detail = meetingService.get(meetingId, clock.today());
        assertThat(detail.items()).singleElement().satisfies(item -> {
            assertThat(item.taskId()).isEqualTo(taskId);
            assertThat(item.taskStatus()).isEqualTo(TaskStatus.PENDING);
        });
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("PENDING");
    }

    @Test
    void closureFollowsTaskStatuses() {
        long owner = seedOwner("zhang", "张三");
        long meetingId = newMeeting("周例会").id();
        long itemA = addItem(meetingId, "事项A", owner);
        long itemB = addItem(meetingId, "事项B", owner);
        ConvertResult converted = conversionService.convert(meetingId, admin(), List.of(itemA, itemB));
        long taskA = converted.created().get(0).taskId();
        long taskB = converted.created().get(1).taskId();

        assertThat(meetingService.get(meetingId, clock.today()).closed()).isFalse();

        jdbc.update("UPDATE tasks SET status = 'DONE' WHERE id = ?", taskA);
        MeetingDetail half = meetingService.get(meetingId, clock.today());
        assertThat(half.unfinishedCount()).isEqualTo(1);
        assertThat(half.closed()).isFalse();

        // 已取消同样算「不再未完成」，与矩阵/看板口径一致（TaskSql.NOT_FINISHED）
        jdbc.update("UPDATE tasks SET status = 'CANCELLED' WHERE id = ?", taskB);
        MeetingDetail closed = meetingService.get(meetingId, clock.today());
        assertThat(closed.taskCount()).isEqualTo(2);
        assertThat(closed.unfinishedCount()).isZero();
        assertThat(closed.closed()).isTrue();
        assertThat(meetingService.list(false, null, 1, 20, admin()).items())
                .singleElement().satisfies(row -> assertThat(row.closed()).isTrue());
    }

    @Test
    void meetingIsAdminOnly() {
        long ownerId = seedOwner("zhang", "张三");
        Actor owner = actor(ownerId);
        long meetingId = newMeeting("周例会").id();
        long itemId = addItem(meetingId, "事项A", ownerId);

        assertThatThrownBy(() -> meetingService.list(false, null, 1, 20, owner))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> meetingService.get(meetingId, owner, clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> conversionService.convert(meetingId, owner, List.of(itemId)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
    }

    @Test
    void unknownMeetingIs404() {
        assertThatThrownBy(() -> meetingService.get(999999L, clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> conversionService.convert(999999L, admin(), List.of(1L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
