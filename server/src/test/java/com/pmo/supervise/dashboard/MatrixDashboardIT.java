package com.pmo.supervise.dashboard;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.dashboard.dto.DashboardView;
import com.pmo.supervise.dashboard.dto.Kpis;
import com.pmo.supervise.dashboard.dto.MatrixFilter;
import com.pmo.supervise.dashboard.dto.MatrixRow;
import com.pmo.supervise.dashboard.dto.MatrixView;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 矩阵与看板口径（AC-15 / AC-16 / AC-17）：
 * 五列互斥、行合计非取消、完成率分母 0 为 null、KPI 与矩阵交叉一致、焦点四段排序、权限只读。
 * 固定 TODAY = 2026-11-09（周一），使「本周 = 11-09..11-15」的周一界限可断言。
 */
class MatrixDashboardIT extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Autowired
    private MatrixService matrixService;

    @Autowired
    private DashboardService dashboardService;

    @Test
    void matrixMovesOverdueOutOfOriginalColumnsAndTotalsExcludeCancelled() {
        long zhang = seedOwner("zhang", "张三");
        long li = seedOwner("li", "李四");
        long wang = seedUser("wang", "王五", Role.OWNER, "DISABLED");
        long zhao = seedOwner("zhao", "赵六");

        seedTask("待开始", zhang, TODAY.plusDays(5), "PENDING", adminId);
        seedTask("待开始已逾期", zhang, TODAY.minusDays(2), "PENDING", adminId);
        seedTask("进行中", zhang, TODAY.plusDays(2), "IN_PROGRESS", adminId);
        seedTask("待验收已逾期", zhang, TODAY.minusDays(1), "PENDING_ACCEPT", adminId);
        seedTask("待验收", zhang, TODAY.plusDays(3), "PENDING_ACCEPT", adminId);
        seedTask("已完成", zhang, TODAY.minusDays(3), "DONE", adminId);
        seedTask("已取消", zhang, TODAY.minusDays(4), "CANCELLED", adminId);
        seedTask("李四完成", li, TODAY.minusDays(1), "DONE", adminId);
        seedTask("王五待办", wang, TODAY.plusDays(1), "PENDING", adminId);
        seedTask("赵六已取消", zhao, TODAY.plusDays(1), "CANCELLED", adminId);

        MatrixView view = matrixService.load(new MatrixFilter(), TODAY, actor(adminId));

        MatrixRow row = row(view, "张三");
        assertThat(row.pending()).isEqualTo(1);
        assertThat(row.inProgress()).isEqualTo(1);
        assertThat(row.pendingAccept()).isEqualTo(1); // 逾期的待验收被移入逾期列
        assertThat(row.overdue()).isEqualTo(2);
        assertThat(row.done()).isEqualTo(1);
        assertThat(row.cancelled()).isEqualTo(1);
        assertThat(row.total()).isEqualTo(6); // 非取消：1+1+1+2+1
        assertThat(row.total()).isEqualTo(row.pending() + row.inProgress() + row.pendingAccept()
                + row.overdue() + row.done());
        assertThat(row.completionRate()).isEqualTo(0.1667);
        assertThat(row(view, "李四").completionRate()).isEqualTo(1.0);
        assertThat(view.rows()).extracting(MatrixRow::name).doesNotContain("赵六"); // 只有取消任务不入行

        // AC-15：默认含已停用用户
        assertThat(view.rows()).extracting(MatrixRow::name).contains("王五");
        MatrixRow totals = view.totals();
        assertThat(totals.total()).isEqualTo(view.rows().stream().mapToLong(MatrixRow::total).sum());
        assertThat(totals.pending()).isEqualTo(view.rows().stream().mapToLong(MatrixRow::pending).sum());
        assertThat(totals.overdue()).isEqualTo(2);
        assertThat(totals.cancelled()).isEqualTo(1);
        assertThat(totals.completionRate()).isEqualTo(0.25); // (1+1)/8

        MatrixFilter activeOnly = new MatrixFilter();
        activeOnly.setIncludeDisabled(false);
        MatrixView activeView = matrixService.load(activeOnly, TODAY, actor(adminId));
        assertThat(activeView.rows()).extracting(MatrixRow::name).containsExactlyInAnyOrder("张三", "李四");
        assertThat(activeView.totals().total()).isEqualTo(7);
    }

    @Test
    void matrixRangeUsesDueDateAndWeeksStartOnMonday() {
        long owner = seedOwner("range", "范围甲");
        seedTask("上周日", owner, TODAY.minusDays(1), "PENDING", adminId);
        seedTask("本周一", owner, TODAY, "PENDING", adminId);
        seedTask("本周日", owner, TODAY.plusDays(6), "PENDING", adminId);
        seedTask("下周一", owner, TODAY.plusDays(7), "PENDING", adminId);

        MatrixView week = matrixService.load(filter("WEEK"), TODAY, actor(adminId));
        assertThat(week.rangeFrom()).isEqualTo(TODAY);
        assertThat(week.rangeTo()).isEqualTo(TODAY.plusDays(6));
        assertThat(week.totals().total()).isEqualTo(2); // 本周一与本周日；上周日、下周一被排除
        assertThat(week.totals().overdue()).isZero();

        MatrixView month = matrixService.load(filter("MONTH"), TODAY, actor(adminId));
        assertThat(month.rangeFrom()).isEqualTo(TODAY.withDayOfMonth(1));
        assertThat(month.rangeTo()).isEqualTo(TODAY.withDayOfMonth(30));
        assertThat(month.totals().total()).isEqualTo(4);
        assertThat(month.totals().overdue()).isEqualTo(1);

        MatrixView all = matrixService.load(filter("ALL"), TODAY, actor(adminId));
        assertThat(all.rangeFrom()).isNull();
        assertThat(all.rangeTo()).isNull();
        assertThat(all.totals().total()).isEqualTo(4);
    }

    @Test
    void kpisShareTheMatrixNumbersAndDueSoonCoversTodayAndTomorrow() {
        long owner = seedOwner("kpi", "KPI甲");
        seedTask("今天到期", owner, TODAY, "PENDING", adminId);
        seedTask("明天到期", owner, TODAY.plusDays(1), "IN_PROGRESS", adminId);
        seedTask("后天到期", owner, TODAY.plusDays(2), "PENDING", adminId);
        long overdueAccept = seedTask("逾期待验收", owner, TODAY.minusDays(1), "PENDING_ACCEPT", adminId);
        jdbc.update("UPDATE tasks SET submitted_at = ? WHERE id = ?", TODAY.minusDays(2).atTime(9, 0), overdueAccept);
        seedTask("待验收", owner, TODAY.plusDays(3), "PENDING_ACCEPT", adminId);
        seedTask("已完成", owner, TODAY.minusDays(5), "DONE", adminId);
        seedTask("已取消", owner, TODAY.minusDays(6), "CANCELLED", adminId);

        DashboardView dashboard = dashboardService.load(new TaskFilter(), TODAY, 10, actor(adminId));
        MatrixView matrix = matrixService.load(new MatrixFilter(), TODAY, actor(adminId));
        Kpis kpis = dashboard.kpis();
        MatrixRow totals = matrix.totals();

        assertThat(kpis.total()).isEqualTo(6).isEqualTo(totals.total()); // 7 条里减去已取消
        assertThat(kpis.unfinished()).isEqualTo(5).isEqualTo(totals.total() - totals.done());
        assertThat(kpis.overdue()).isEqualTo(1).isEqualTo(totals.overdue());
        assertThat(kpis.overdueRate()).isEqualTo(0.2); // 1 / 5
        assertThat(kpis.dueSoon24h()).isEqualTo(2); // 今天 + 明天；后天不算
        // 「待验收数」与任务池 status 口径一致（含逾期）；矩阵列按 AC-15 已把逾期移出
        assertThat(kpis.pendingAccept()).isEqualTo(2);
        assertThat(totals.pendingAccept()).isEqualTo(1);
        assertThat(dashboard.focusTotal()).isEqualTo(kpis.unfinished());
    }

    @Test
    void focusOrdersByFourBucketsThenLimit() {
        long owner = seedOwner("focus", "焦点甲");
        seedTask("逾期最久", owner, TODAY.minusDays(8), "PENDING", adminId);
        seedTask("逾期次久", owner, TODAY.minusDays(4), "IN_PROGRESS", adminId);
        seedTask("待开始晚", owner, TODAY.plusDays(3), "PENDING", adminId);
        seedTask("待开始早", owner, TODAY.plusDays(1), "PENDING", adminId);
        long acceptLate = seedTask("待验收晚提交", owner, TODAY.plusDays(5), "PENDING_ACCEPT", adminId);
        jdbc.update("UPDATE tasks SET submitted_at = ? WHERE id = ?", TODAY.atTime(10, 0), acceptLate);
        long acceptEarly = seedTask("待验收早提交", owner, TODAY.plusDays(9), "PENDING_ACCEPT", adminId);
        jdbc.update("UPDATE tasks SET submitted_at = ? WHERE id = ?", TODAY.atTime(9, 0), acceptEarly);
        seedTask("进行中", owner, TODAY.plusDays(2), "IN_PROGRESS", adminId);
        seedTask("已完成不出现", owner, TODAY.minusDays(1), "DONE", adminId);

        DashboardView view = dashboardService.load(new TaskFilter(), TODAY, 10, actor(adminId));
        assertThat(view.focus()).extracting(TaskRow::title).containsExactly(
                "逾期最久", "逾期次久", "待开始早", "待开始晚", "待验收早提交", "待验收晚提交", "进行中");
        assertThat(view.focus()).allSatisfy(row -> assertThat(row.status()).isNotEqualTo(
                com.pmo.supervise.common.TaskStatus.DONE));
        assertThat(view.focus().get(0).isOverdue()).isTrue();
        assertThat(view.focus().get(0).overdueDays()).isEqualTo(8);

        DashboardView limited = dashboardService.load(new TaskFilter(), TODAY, 3, actor(adminId));
        assertThat(limited.focus()).hasSize(3);
        assertThat(limited.focus()).extracting(TaskRow::title)
                .containsExactly("逾期最久", "逾期次久", "待开始早");
        assertThat(limited.focusTotal()).isEqualTo(7);
    }

    @Test
    void emptyMatrixTotalsAreZeroAndCompletionRateIsNull() {
        MatrixView view = matrixService.load(new MatrixFilter(), TODAY, actor(adminId));
        assertThat(view.rows()).isEmpty();
        assertThat(view.totals().total()).isZero();
        assertThat(view.totals().completionRate()).isNull();
    }

    @Test
    void ownerIsForbiddenButExecAndAdminCanRead() {
        long owner = seedOwner("plain", "普通负责人");
        Actor ownerActor = actor(owner);
        Actor execActor = actor(seedExec("boss", "领导"));

        assertThatThrownBy(() -> matrixService.load(new MatrixFilter(), TODAY, ownerActor))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> dashboardService.load(new TaskFilter(), TODAY, 10, ownerActor))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(matrixService.load(new MatrixFilter(), TODAY, execActor).rows()).isNotNull();
        assertThat(dashboardService.load(new TaskFilter(), TODAY, 10, execActor).kpis().total()).isZero();
        assertThat(matrixService.load(new MatrixFilter(), TODAY, actor(adminId)).rows()).isNotNull();
    }

    @Test
    void invalidRangeOrSourceTypeIsValidationError() {
        assertThatThrownBy(() -> matrixService.load(filter("QUARTER"), TODAY, actor(adminId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors().get(0).field())
                        .isEqualTo("range"));

        MatrixFilter badSource = new MatrixFilter();
        badSource.setSourceType("UNKNOWN");
        assertThatThrownBy(() -> matrixService.load(badSource, TODAY, actor(adminId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).fieldErrors().get(0).field())
                        .isEqualTo("sourceType"));
    }

    private static MatrixFilter filter(String range) {
        MatrixFilter filter = new MatrixFilter();
        filter.setRange(range);
        return filter;
    }

    private static MatrixRow row(MatrixView view, String name) {
        List<MatrixRow> matched = view.rows().stream().filter(r -> r.name().equals(name)).toList();
        assertThat(matched).as("矩阵应包含用户 %s", name).hasSize(1);
        return matched.get(0);
    }
}
