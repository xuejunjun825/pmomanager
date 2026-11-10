package com.pmo.supervise.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskPermissions;
import com.pmo.supervise.task.dto.TaskRow;
import com.pmo.supervise.task.dto.TaskSource;
import com.pmo.supervise.task.dto.UserRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON 契约（web/src/types/api.ts）：布尔字段必须保持 isOverdue / dueSoon 原名。 */
class TaskJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void rowJsonKeepsFrontendFieldNames() throws Exception {
        TaskRow row = new TaskRow(1L, "任务", new UserRef(2L, "张三", Role.OWNER),
                LocalDate.of(2026, 11, 10),
                new TaskSource("PROJECT", "项目任务", "#6941C6", "#F4F3FF", "#D9D6FE", null, null, null, "说明"),
                TaskStatus.PENDING, "待开始", true, 3, true, 2L, null, null, null);

        String json = mapper.writeValueAsString(row);

        assertThat(json).contains("\"isOverdue\":true")
                .contains("\"dueSoon\":true")
                .contains("\"overdueDays\":3")
                .contains("\"statusLabel\":\"待开始\"");
    }

    @Test
    void detailJsonAddsEventsPermissionsAndIsOverdue() throws Exception {
        TaskDetailRow detail = new TaskDetailRow(1L, "任务", new UserRef(2L, "张三", Role.OWNER),
                LocalDate.of(2026, 11, 10),
                new TaskSource("MEETING", "会议决议", "#1B4D8F", "#EAF1FA", "#C7D9EF", 9L, "周会", 11L, null),
                TaskStatus.PENDING_ACCEPT, "待验收", false, 0, false, 1L, null, null, null,
                new UserRef(3L, "派发人", Role.ADMIN), Priority.HIGH, "已完成", null, null,
                List.of(), new TaskPermissions(true, true, false, false, false, false, false,
                        false, false, false, true, false, Map.of()));

        String json = mapper.writeValueAsString(detail);

        assertThat(json).contains("\"isOverdue\":false")
                .contains("\"permissions\"")
                .contains("\"source\"")
                .contains("\"dispatcher\"");
    }
}
