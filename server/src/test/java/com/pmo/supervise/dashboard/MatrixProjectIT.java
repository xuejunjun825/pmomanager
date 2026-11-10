package com.pmo.supervise.dashboard;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.dashboard.dto.DashboardView;
import com.pmo.supervise.dashboard.dto.MatrixFilter;
import com.pmo.supervise.dashboard.dto.MatrixRow;
import com.pmo.supervise.dashboard.dto.MatrixView;
import com.pmo.supervise.task.TaskQueryDao;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目筛选的三处对齐（AC-5 / 反面目标 ④）：任务池、看板、矩阵都只经
 * {@code ProjectFilterSql.append}，因此同一 project 条件下三个数字必然同源。
 *
 * <p>合计口径（TECHNICAL §5 line 650 沿用 r1）：任务池 total 含取消任务，
 * 看板 total 不含取消 → 任务池 total = 看板 total + 矩阵取消数；Σ矩阵五列 = 看板 total。
 * 固定 TODAY = 2026-11-09，逾期限定 due_date &lt; today。
 */
@AutoConfigureMockMvc
class MatrixProjectIT extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final String PASSWORD = "Admin@12345";

    /** 项目形态来源（与内置 NOTE 形态的「PROJECT」编码同名不同义，正是反面目标 ①）。 */
    private static final String PROJECT_SOURCE_CODE = "R4PRJ";
    private static final String PROJECT_SOURCE_NAME = "R4项目来源";
    private static final String NOTE_SOURCE = "PROJECT";

    @Autowired
    private MatrixService matrixService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private TaskQueryDao queryDao;

    @Autowired
    private MockMvc mvc;

    /** source_types 不被 resetDatabase 清理，故按名称复用既有行，保持可重跑。 */
    private String projectSource;
    private long project;
    private long disabledProject;
    private long cleanProject;

    @BeforeEach
    void seedProjectWorld() {
        projectSource = ensureProjectFormSource();
        project = seedProject("P00001", "智能工厂", 1, true);
        disabledProject = seedProject("P00002", "已停用项目", 2, false);
        cleanProject = seedProject("P00003", "城市更新一期", 3, true);

        long zhang = seedOwner("prjzhang", "张三");
        long li = seedOwner("prjli", "李四");

        // 项目 1：五列全覆盖 + 一条取消（用于「任务池 total = 看板 total + 取消数」）
        seedProjectTask("待开始", zhang, TODAY.plusDays(5), "PENDING", project);
        seedProjectTask("进行中", zhang, TODAY.plusDays(2), "IN_PROGRESS", project);
        seedProjectTask("待验收", zhang, TODAY.plusDays(3), "PENDING_ACCEPT", project);
        seedProjectTask("已完成", zhang, TODAY.minusDays(3), "DONE", project);
        seedProjectTask("已取消", zhang, TODAY.minusDays(4), "CANCELLED", project);
        seedProjectTask("逾期待开始", li, TODAY.minusDays(2), "PENDING", project);
        seedProjectTask("李四已完成", li, TODAY.minusDays(1), "DONE", project);

        // 项目 3：无取消任务 → 三者严格相等
        seedProjectTask("干净项目待办", zhang, TODAY.plusDays(1), "PENDING", cleanProject);

        // 停用项目：AC-4 仍可筛
        seedProjectTask("停用项目任务", li, TODAY.plusDays(2), "PENDING", disabledProject);

        // 改形历史：来源形态已是 PROJECT、任务没有项目（source_note 仍在，库层 CHECK 合法）
        seedUnassignedTask("改形历史待办", zhang, TODAY.plusDays(6), "PENDING");
        seedUnassignedTask("改形历史已完成", li, TODAY.minusDays(2), "DONE");

        // 反面目标 ①：内置 NOTE 形态来源（编码恰为 PROJECT）同样没有项目，但不是「未指定项目」
        seedTask("说明型任务", zhang, TODAY.plusDays(4), "PENDING", adminId);
    }

    @Test
    @DisplayName("同一 project：任务池 total = 看板 total + 取消数，Σ矩阵五列 = 看板 total")
    void poolBoardAndMatrixAgreeUnderTheSameProject() {
        long poolTotal = poolTotal(projectFilter(String.valueOf(project)));
        DashboardView board = dashboardService.load(projectFilter(String.valueOf(project)), TODAY, 10);
        MatrixView matrix = matrixService.load(matrixFilter(String.valueOf(project)), TODAY);

        assertThat(poolTotal).isEqualTo(7);
        assertThat(board.kpis().total()).isEqualTo(6); // 7 条减去取消
        assertThat(matrix.totals().cancelled()).isEqualTo(1);
        assertThat(poolTotal).isEqualTo(board.kpis().total() + matrix.totals().cancelled());

        assertThat(matrix.totals().total()).isEqualTo(board.kpis().total());
        assertThat(fiveColumns(matrix.totals())).isEqualTo(board.kpis().total());
        assertThat(board.kpis().overdue()).isEqualTo(matrix.totals().overdue()).isEqualTo(1);
        assertThat(board.kpis().pendingAccept()).isEqualTo(1);

        MatrixRow zhang = row(matrix, "张三");
        assertThat(zhang.pending()).isEqualTo(1);
        assertThat(zhang.inProgress()).isEqualTo(1);
        assertThat(zhang.pendingAccept()).isEqualTo(1);
        assertThat(zhang.overdue()).isZero();
        assertThat(zhang.done()).isEqualTo(1);
        assertThat(zhang.cancelled()).isEqualTo(1);
        // 行合计沿用 r1 口径：五列之和（不含取消），取消单列，否则矩阵行会与看板 total 不同源
        assertThat(zhang.total()).isEqualTo(4);

        MatrixRow li = row(matrix, "李四");
        assertThat(li.overdue()).isEqualTo(1); // 逾期任务从原状态列移出
        assertThat(li.pending()).isZero();
        assertThat(li.done()).isEqualTo(1);
        assertThat(li.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("无取消任务的项目：任务池 total = 看板 total = Σ矩阵五列（严格相等）")
    void cleanProjectHasThreeEqualNumbers() {
        long poolTotal = poolTotal(projectFilter(String.valueOf(cleanProject)));
        DashboardView board = dashboardService.load(projectFilter(String.valueOf(cleanProject)), TODAY, 10);
        MatrixView matrix = matrixService.load(matrixFilter(String.valueOf(cleanProject)), TODAY);

        assertThat(poolTotal).isEqualTo(1);
        assertThat(board.kpis().total()).isEqualTo(poolTotal);
        assertThat(matrix.totals().cancelled()).isZero();
        assertThat(fiveColumns(matrix.totals())).isEqualTo(poolTotal);
        assertThat(matrix.totals().total()).isEqualTo(poolTotal);
    }

    @Test
    @DisplayName("project=none 只命中「PROJECT 形态且无项目」的改形历史，不吞说明型来源任务")
    void unassignedProjectMatchesOnlyProjectFormTasksWithoutProject() {
        String none = "none";
        long poolTotal = poolTotal(projectFilter(none));
        DashboardView board = dashboardService.load(projectFilter(none), TODAY, 10);
        MatrixView matrix = matrixService.load(matrixFilter(none), TODAY);

        assertThat(poolTotal).isEqualTo(2);
        assertThat(board.kpis().total()).isEqualTo(2);
        assertThat(matrix.totals().total()).isEqualTo(2);
        assertThat(matrix.totals().done()).isEqualTo(1);
        assertThat(matrix.totals().pending()).isEqualTo(1);

        // 说明型任务（内置 NOTE 形态来源，无项目）不在「未指定项目」里：否则 3 而不是 2
        assertThat(poolTotal).isNotEqualTo(3);
    }

    @Test
    @DisplayName("停用项目仍可筛选（AC-4），且三处数字一致")
    void disabledProjectIsStillFilterable() {
        String disabled = String.valueOf(disabledProject);
        long poolTotal = poolTotal(projectFilter(disabled));
        MatrixView matrix = matrixService.load(matrixFilter(disabled), TODAY);

        assertThat(poolTotal).isEqualTo(1);
        assertThat(matrix.totals().total()).isEqualTo(1);
        assertThat(dashboardService.load(projectFilter(disabled), TODAY, 10).kpis().total()).isEqualTo(1);
    }

    @Test
    @DisplayName("不带 project 时三处退回升级前口径：任务池 total = 看板 total + 全部取消数")
    void withoutProjectFilterNumbersFallBackToAllTasks() {
        long poolTotal = poolTotal(new TaskFilter());
        DashboardView board = dashboardService.load(new TaskFilter(), TODAY, 10);
        MatrixView matrix = matrixService.load(new MatrixFilter(), TODAY);

        assertThat(poolTotal).isEqualTo(12);
        assertThat(poolTotal).isEqualTo(board.kpis().total() + matrix.totals().cancelled());
        assertThat(fiveColumns(matrix.totals())).isEqualTo(board.kpis().total());
    }

    @Test
    @DisplayName("非法项目参数 → 400 PROJECT_INVALID（矩阵与任务池同一份谓词）")
    void invalidProjectIs400() {
        for (String raw : new String[]{"abc", "0", "-3", "999999"}) {
            assertThatThrownBy(() -> matrixService.load(matrixFilter(raw), TODAY))
                    .as("矩阵 project=%s", raw)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).code())
                            .isEqualTo(ErrorCode.PROJECT_INVALID));
            assertThatThrownBy(() -> queryDao.page(projectFilter(raw), TODAY, 1, 100))
                    .as("任务池 project=%s", raw)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).code())
                            .isEqualTo(ErrorCode.PROJECT_INVALID));
        }
    }

    @Test
    @DisplayName("项目筛选与来源筛选叠加时互不吞掉：PRJ + 项目 1 = 同一批行")
    void projectAndSourceTypeFiltersCompose() {
        TaskFilter both = projectFilter(String.valueOf(project));
        both.setSourceType(projectSource);
        assertThat(poolTotal(both)).isEqualTo(7);

        MatrixFilter matrixBoth = matrixFilter(String.valueOf(project));
        matrixBoth.setSourceType(projectSource);
        assertThat(matrixService.load(matrixBoth, TODAY).totals().total()).isEqualTo(6);

        TaskFilter wrongSource = projectFilter(String.valueOf(project));
        wrongSource.setSourceType(NOTE_SOURCE); // 内置说明型来源：与项目筛选交集为空
        assertThat(poolTotal(wrongSource)).isZero();
    }

    @Test
    @DisplayName("HTTP 层 project 参数绑定到 MatrixFilter / TaskFilter，数字与 service 口径一致")
    void httpBindsProjectParameter() throws Exception {
        seedUser("prjadmin", "项目管理员", Role.ADMIN, "ACTIVE");
        MockHttpSession session = login("prjadmin");

        mvc.perform(get("/api/matrix").param("project", String.valueOf(project)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total").value(6))
                .andExpect(jsonPath("$.totals.cancelled").value(1));

        mvc.perform(get("/api/dashboard").param("project", String.valueOf(project)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.total").value(6));

        mvc.perform(get("/api/tasks").param("project", "none").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/matrix").param("project", "999999").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_INVALID"));
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long poolTotal(TaskFilter filter) {
        PageResponse<TaskRow> page = queryDao.page(filter, TODAY, 1, 100);
        return page.total();
    }

    private static long fiveColumns(MatrixRow row) {
        return row.pending() + row.inProgress() + row.pendingAccept() + row.overdue() + row.done();
    }

    private static MatrixRow row(MatrixView view, String name) {
        return view.rows().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("矩阵应包含用户 " + name));
    }

    private static TaskFilter projectFilter(String project) {
        TaskFilter filter = new TaskFilter();
        filter.setProject(project);
        filter.setSize(100);
        return filter;
    }

    private static MatrixFilter matrixFilter(String project) {
        MatrixFilter filter = new MatrixFilter();
        filter.setProject(project);
        return filter;
    }

    /** source_types 是主数据、resetDatabase 不清它，所以本用例自己造的来源必须自己收走。 */
    @AfterEach
    void dropSeededSource() {
        if (projectSource == null) {
            return;
        }
        jdbc.update("DELETE FROM tasks WHERE source_type = ?", projectSource);
        jdbc.update("DELETE FROM source_types WHERE code = ?", projectSource);
    }

    private long seedProject(String code, String name, int sort, boolean enabled) {
        jdbc.update("INSERT INTO projects (code, name, sort_no, enabled) VALUES (?, ?, ?, ?)",
                code, name, sort, enabled ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM projects WHERE code = ?", Long.class, code);
    }

    private void seedProjectTask(String title, long ownerId, LocalDate dueDate, String status, long projectId) {
        jdbc.update("""
                INSERT INTO tasks (title, owner_id, due_date, source_type, source_project_id,
                                   status, dispatcher_id, priority)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'MEDIUM')
                """, title, ownerId, dueDate, projectSource, projectId, status, adminId);
    }

    /** 改形历史：说明仍在、项目为空，但来源形态已是 PROJECT（CHECK 不看 source_type，故合法）。 */
    private void seedUnassignedTask(String title, long ownerId, LocalDate dueDate, String status) {
        jdbc.update("""
                INSERT INTO tasks (title, owner_id, due_date, source_type, source_note,
                                   status, dispatcher_id, priority)
                VALUES (?, ?, ?, ?, '改形前留下的事由', ?, ?, 'MEDIUM')
                """, title, ownerId, dueDate, projectSource, status, adminId);
    }

    /**
     * source_types 是管理员主数据、不随 resetDatabase 清理；按名称复用既有行并强制 PROJECT 形态，
     * 这样重复跑测试不会撞 uk_source_name，形态也被钉死。
     */
    private String ensureProjectFormSource() {
        List<String> existing = jdbc.queryForList(
                "SELECT code FROM source_types WHERE name = ?", String.class, PROJECT_SOURCE_NAME);
        if (!existing.isEmpty()) {
            String code = existing.get(0);
            jdbc.update("UPDATE source_types SET form = 'PROJECT', enabled = 1 WHERE code = ?", code);
            return code;
        }
        jdbc.update("""
                INSERT INTO source_types (code, name, form, color, sort_no, enabled)
                VALUES (?, ?, 'PROJECT', '#0B7A6E', 5, 1)
                """, PROJECT_SOURCE_CODE, PROJECT_SOURCE_NAME);
        return PROJECT_SOURCE_CODE;
    }

    /** 防线自检：确认三张表的种子与断言口径一致（避免静默变成空断言）。 */
    @Test
    @DisplayName("种子自检：项目行数与来源形态")
    void seedSanity() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects", Long.class)).isEqualTo(3);
        Map<String, Object> form = jdbc.queryForMap(
                "SELECT form FROM source_types WHERE code = ?", projectSource);
        assertThat(form.get("form")).isEqualTo("PROJECT");
    }
}
