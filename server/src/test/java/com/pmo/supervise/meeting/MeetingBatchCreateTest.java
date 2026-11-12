package com.pmo.supervise.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.meeting.dto.DecisionItemRequest;
import com.pmo.supervise.meeting.dto.DecisionItemRow;
import com.pmo.supervise.meeting.dto.MeetingDetail;
import com.pmo.supervise.meeting.dto.MeetingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 建会一次录 0..50 条决议（AC-2，TECHNICAL §3.3）：
 * 会议与决议同事务——任一条非法则 422 且会议与决议都不落库；
 * 错误字段为 {@code items[K].<field>}（K 为 1 基行号），前端直接映射到行内单元格；
 * items 缺省（旧客户端）行为与 r7 之前逐字一致。
 */
@AutoConfigureMockMvc
class MeetingBatchCreateTest extends IntegrationTestSupport {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 20);
    private static final String PASSWORD = "Admin@12345";

    @Autowired
    private MeetingService meetingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mvc;

    private MeetingRequest request(List<DecisionItemRequest> items) {
        return new MeetingRequest("周例会", LocalDate.of(2026, 11, 1), "张三",
                List.of(), "周会纪要", items);
    }

    private DecisionItemRequest validItem(String description, long ownerId) {
        return new DecisionItemRequest(description, ownerId, DUE, "交付说明");
    }

    private long meetingCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM meetings", Long.class);
        return count == null ? 0 : count;
    }

    private long itemCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM decision_items", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("一次建会落 N 条决议：顺序即录入顺序，后续 addItem 接续 sortNo")
    void createPersistsItemsInEntryOrder() {
        long owner = seedOwner("zhang", "张三");

        MeetingDetail detail = meetingService.create(actor(adminId),
                request(List.of(validItem("事项一", owner), validItem("事项二", owner), validItem("事项三", owner))),
                clock.today());

        assertThat(detail.items()).hasSize(3);
        assertThat(detail.items()).extracting(DecisionItemRow::description)
                .containsExactly("事项一", "事项二", "事项三");
        assertThat(detail.items()).extracting(DecisionItemRow::sortNo).containsExactly(0, 1, 2);
        assertThat(detail.items()).extracting(DecisionItemRow::owner)
                .extracting("id").containsOnly(owner);
        assertThat(detail.items()).extracting(DecisionItemRow::dueDate).containsOnly(DUE);
        assertThat(detail.items()).extracting(DecisionItemRow::deliveryNote).containsOnly("交付说明");
        assertThat(detail.items()).extracting(DecisionItemRow::converted).containsOnly(false);
        // 会议与决议同一次请求落库，计数仍按任务联动（尚无任务）
        assertThat(detail.taskCount()).isZero();
        assertThat(detail.unfinishedCount()).isZero();

        // 建会之后的单条新增继续接在批量之后
        long appended = meetingService.addItem(detail.id(), actor(adminId),
                new DecisionItemRequest("事项四", owner, DUE, null), clock.today()).id();
        assertThat(meetingService.get(detail.id(), clock.today()).items())
                .extracting(DecisionItemRow::id).last().isEqualTo(appended);
        assertThat(meetingService.get(detail.id(), clock.today()).items())
                .extracting(DecisionItemRow::sortNo).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("任一条非法 → 422 定位 items[K].field，会议与决议都不落库")
    void invalidRowAbortsWholeCreation() {
        long owner = seedOwner("zhang", "张三");
        List<DecisionItemRequest> items = new ArrayList<>();
        items.add(validItem("事项一", owner));
        items.add(new DecisionItemRequest("事项二", null, DUE, null));                  // 第 2 行缺负责人
        items.add(new DecisionItemRequest("  ", owner, DUE, null));                     // 第 3 行缺描述
        items.add(new DecisionItemRequest("事项四", owner, null, "x".repeat(501)));      // 第 4 行缺日期 + 说明超长

        assertThatThrownBy(() -> meetingService.create(actor(adminId), request(items), clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting("field")
                            .contains("items[2].ownerId", "items[3].description",
                                    "items[4].dueDate", "items[4].deliveryNote");
                    assertThat(ex.fieldErrors()).extracting("index")
                            .contains(2, 3, 4);
                    // 字段名与行号必须一一对应，前端才能把错误放到对应单元格
                    assertThat(ex.fieldErrors()).allSatisfy(error ->
                            assertThat(error.field()).startsWith("items[" + error.index() + "]"));
                });

        assertThat(meetingCount()).isZero();
        assertThat(itemCount()).isZero();
    }

    @Test
    @DisplayName("参会人非法先于决议项报错（顺序与 §1.3 一致），且仍不落库")
    void attendeeErrorPreemptsItems() {
        List<DecisionItemRequest> items = List.of(new DecisionItemRequest("事项一", null, DUE, null));

        assertThatThrownBy(() -> meetingService.create(actor(adminId),
                new MeetingRequest("周例会", LocalDate.of(2026, 11, 1), "张三", List.of(999999L), null, items),
                clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting("field").containsExactly("attendeeIds");
                });

        assertThat(meetingCount()).isZero();
        assertThat(itemCount()).isZero();
    }

    @Test
    @DisplayName("超过 50 条 → 422 items，不落库（上限与前端行数一致）")
    void batchLimitEnforced() {
        long owner = seedOwner("zhang", "张三");
        List<DecisionItemRequest> items = new ArrayList<>();
        for (int i = 0; i < MeetingService.MAX_BATCH_ITEMS + 1; i++) {
            items.add(validItem("事项" + (i + 1), owner));
        }

        assertThatThrownBy(() -> meetingService.create(actor(adminId), request(items), clock.today()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).extracting("field").containsExactly("items");
                });

        assertThat(meetingCount()).isZero();
    }

    @Test
    @DisplayName("items 缺省 / 空数组 → 与 r7 之前一致：只建会议")
    void creationWithoutItemsStaysCompatible() throws Exception {
        MeetingDetail noItems = meetingService.create(actor(adminId), request(null), clock.today());
        assertThat(noItems.items()).isEmpty();

        MeetingDetail emptyItems = meetingService.create(actor(adminId), request(List.of()), clock.today());
        assertThat(emptyItems.items()).isEmpty();
        assertThat(meetingCount()).isEqualTo(2);

        // 旧客户端 JSON 不含 items：反序列化后为 null，create 视作「不录决议」
        String legacyJson = """
                {"title":"旧客户端","meetingDate":"2026-11-01","host":"张三","attendeeIds":[],"note":null}
                """;
        MeetingRequest legacy = objectMapper.readValue(legacyJson, MeetingRequest.class);
        assertThat(legacy.items()).isNull();
        assertThat(meetingService.create(actor(adminId), legacy, clock.today()).items()).isEmpty();
    }

    @Test
    @DisplayName("HTTP 建会：items 一次落库；非法行回 422 且字段名为 items[K].field")
    void httpCreateWithItemsAndIndexedErrors() throws Exception {
        long owner = seedOwner("zhang", "张三");
        seedUser("meetingadmin", "会议管理员", Role.ADMIN, "ACTIVE");
        MockHttpSession session = login("meetingadmin");

        mvc.perform(post("/api/meetings").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"周例会","meetingDate":"2026-11-01","host":"张三",
                                 "attendeeIds":[%d],"note":"周会纪要",
                                 "items":[{"description":"事项一","ownerId":%d,"dueDate":"2026-11-20"},
                                          {"description":"事项二","ownerId":%d,"dueDate":"2026-11-21"}]}
                                """.formatted(owner, owner, owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].description").value("事项二"))
                .andExpect(jsonPath("$.taskCount").value(0));

        mvc.perform(post("/api/meetings").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"失败会议","meetingDate":"2026-11-01","host":"张三","attendeeIds":[],
                                 "items":[{"description":"事项一","ownerId":%d,"dueDate":"2026-11-20"},
                                          {"description":"事项二","ownerId":null,"dueDate":"2026-11-20"}]}
                                """.formatted(owner)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items[2].ownerId"))
                .andExpect(jsonPath("$.fieldErrors[0].index").value(2));

        assertThat(meetingCount()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(2);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
