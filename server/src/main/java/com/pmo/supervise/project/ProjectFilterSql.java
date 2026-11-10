package com.pmo.supervise.project;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 项目筛选的唯一 SQL 谓词（TECHNICAL §3.3 / AC-5）。
 *
 * <p>任务池、看板经 {@code TaskQueryBuilder}，矩阵经 {@code MatrixService}，全部只能调这里；
 * 谁都不许自己拼 {@code source_project_id} 条件，三处合计才不可能漂移。
 */
@Component
public class ProjectFilterSql {

    /** 「未指定项目」：改形历史（来源已是项目型但任务没绑定项目），不是「没有项目」的同义词。 */
    public static final String UNASSIGNED = "none";

    /** 子查询避免给三个读模型各加一个 join。 */
    private static final String UNASSIGNED_CONDITION =
            " AND t.source_project_id IS NULL"
            + " AND t.source_type IN (SELECT s.code FROM source_types s WHERE s.form = 'PROJECT')";

    private final ProjectService projects;

    public ProjectFilterSql(ProjectService projects) {
        this.projects = projects;
    }

    /**
     * 追加项目筛选条件；raw 为空则完全不动 SQL（保证「不带参数 = 升级前结果」）。
     * 非法/已删 id 抛 PROJECT_INVALID(400)；停用项目允许筛选（AC-4）。
     */
    public void append(StringBuilder where, Map<String, Object> params, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        if (UNASSIGNED.equals(value)) {
            where.append(UNASSIGNED_CONDITION);
            return;
        }
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.PROJECT_INVALID, "项目不合法");
        }
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PROJECT_INVALID, "项目不合法");
        }
        ProjectDao.ProjectRow project = projects.requireKnown(id);
        where.append(" AND t.source_project_id = :projectId");
        params.put("projectId", project.id());
    }
}
