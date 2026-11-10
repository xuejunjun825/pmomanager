package com.pmo.supervise.task;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.dashboard.MatrixService;
import com.pmo.supervise.dashboard.dto.MatrixFilter;
import com.pmo.supervise.dashboard.dto.MatrixView;
import com.pmo.supervise.meta.MetaService;
import com.pmo.supervise.meta.dto.MetaOptions;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.source.SourceForm;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.source.dto.SourceTypeOption;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置同源一致（TECHNICAL §4.3 / AC-5）：meta 下拉、任务池筛选、矩阵三处必须用同一份
 * source_types，未知编码统一 400，停用来源仍能筛历史。
 */
class TaskSourceQueryParityIT extends IntegrationTestSupport {

    @org.junit.jupiter.api.BeforeEach
    void resetSources() {
        jdbc.update("DELETE FROM source_types WHERE code NOT IN ('MEETING', 'PROJECT', 'DAILY')");
        jdbc.update("UPDATE source_types SET enabled = 1");
    }

    @Autowired
    private TaskQueryDao queryDao;

    @Autowired
    private MetaService metaService;

    @Autowired
    private MatrixService matrixService;

    @Autowired
    private SourceTypeService sourceTypes;

    private final LocalDate today = LocalDate.of(2026, 11, 9);
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    private long poolTotal(String sourceCode) {
        TaskFilter filter = new TaskFilter();
        filter.setSourceType(sourceCode);
        PageResponse<TaskRow> page = queryDao.page(filter, today, 1, 20);
        return page.total();
    }

    private MatrixView matrix(String sourceCode) {
        MatrixFilter filter = new MatrixFilter();
        filter.setSourceType(sourceCode);
        return matrixService.load(filter, today, actor(adminId));
    }

    @Test
    void metaPoolAndMatrixAgreeOnTheSameSourceSet() {
        long owner = seedOwner("owner1", "张三");
        long meeting = seedMeeting("周会", adminId);
        long item = seedDecisionItem(meeting, "输出纪要", owner);
        seedTask("项目任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        seedMeetingTask("会议任务", owner, LocalDate.of(2026, 11, 21), "PENDING", adminId, meeting, item);
        Actor admin = actor(adminId);

        MetaOptions meta = metaService.load(admin);
        assertThat(meta.sourceTypes()).extracting(SourceTypeOption::value)
                .containsExactly("MEETING", "PROJECT", "DAILY");
        assertThat(meta.sourceTypes()).extracting(SourceTypeOption::label)
                .containsExactly("会议决议", "项目任务", "日常待办");
        assertThat(meta.sourceTypes().get(0).color()).isEqualTo("#1B4D8F");
        assertThat(meta.sourceTypes().get(1).form()).isEqualTo(SourceForm.NOTE);

        assertThat(poolTotal("PROJECT")).isEqualTo(1);
        assertThat(poolTotal("MEETING")).isEqualTo(1);
        assertThat(poolTotal("DAILY")).isZero();
        assertThat(matrix("PROJECT").totals().total()).isEqualTo(1);
        assertThat(matrix("MEETING").totals().total()).isEqualTo(1);
    }

    @Test
    void disabledSourceIsStillSelectableForHistory() {
        long owner = seedOwner("owner1", "张三");
        seedTask("项目任务", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        sourceTypes.setEnabled(actor(adminId), "PROJECT", false, http);

        MetaOptions meta = metaService.load(actor(adminId));
        SourceTypeOption project = meta.sourceTypes().stream()
                .filter(o -> o.value().equals("PROJECT")).findFirst().orElseThrow();
        assertThat(project.enabled()).isFalse();
        assertThat(poolTotal("PROJECT")).isEqualTo(1);
    }

    @Test
    void customSourceJoinsTheSameSetEverywhere() {
        long owner = seedOwner("owner1", "张三");
        String code = sourceTypes.create(actor(adminId),
                new com.pmo.supervise.source.dto.SourceTypeRequest(
                        "质量检查", SourceForm.NOTE, "#067647", null), http).code();
        seedTask("抽查", owner, LocalDate.of(2026, 11, 20), "PENDING", adminId);
        jdbc.update("UPDATE tasks SET source_type = ?, source_note = '季度抽查' WHERE title = '抽查'", code);

        assertThat(metaService.load(actor(adminId)).sourceTypes())
                .extracting(SourceTypeOption::value).contains(code);
        assertThat(poolTotal(code)).isEqualTo(1);
        assertThat(matrix(code).totals().total()).isEqualTo(1);
    }

    @Test
    void unknownCodeIs400InPoolAndMatrix() {
        assertThatThrownBy(() -> poolTotal("GHOST"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_TYPE_INVALID));
        assertThatThrownBy(() -> matrix("GHOST"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SOURCE_TYPE_INVALID));
    }

    @Test
    void poolAndMetaOnlyExposeConfiguredCodes() {
        List<String> configured = jdbc.queryForList(
                "SELECT code FROM source_types ORDER BY sort_no, id", String.class);

        assertThat(metaService.load(actor(adminId)).sourceTypes())
                .extracting(SourceTypeOption::value)
                .containsExactlyElementsOf(configured);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
    }
}
