package com.pmo.supervise.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.meeting.dto.ConvertResult;
import com.pmo.supervise.meeting.dto.DecisionItemRow;
import com.pmo.supervise.task.dto.UserRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON 契约（web/src/types/api.ts:64-130）：isOverdue 保持原名，convert 返回 created/skipped。 */
class MeetingJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void decisionItemRowKeepsFrontendFieldNames() throws Exception {
        DecisionItemRow row = new DecisionItemRow(1L, "事项", new UserRef(2L, "张三", Role.OWNER),
                LocalDate.of(2026, 11, 20), "交付说明", 0, true, 9L, TaskStatus.PENDING_ACCEPT,
                true, 4);

        String json = mapper.writeValueAsString(row);

        assertThat(json).contains("\"isOverdue\":true")
                .contains("\"overdueDays\":4")
                .contains("\"converted\":true")
                .contains("\"taskStatus\":\"PENDING_ACCEPT\"")
                .contains("\"owner\":{")
                .doesNotContain("\"overdue\":");
    }

    @Test
    void convertResultCarriesCreatedAndSkipped() throws Exception {
        ConvertResult result = new ConvertResult(
                List.of(new ConvertResult.Created(1L, 10L)),
                List.of(new ConvertResult.Skipped(2L, ConvertResult.Skipped.ALREADY_CONVERTED, 11L)));

        String json = mapper.writeValueAsString(result);

        assertThat(json).contains("\"created\":[{\"itemId\":1,\"taskId\":10}]")
                .contains("\"reason\":\"ALREADY_CONVERTED\"");
    }
}
