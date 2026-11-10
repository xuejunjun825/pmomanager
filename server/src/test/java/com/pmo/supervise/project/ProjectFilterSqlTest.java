package com.pmo.supervise.project;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.project.ProjectDao.ProjectRow;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 项目筛选唯一谓词的单元测试（TECHNICAL §3.3 / §6.1 / AC-5）。 */
class ProjectFilterSqlTest {

    private static ProjectFilterSql filter(Long knownId) {
        return new ProjectFilterSql(new ProjectService(null, null) {
            @Override
            public ProjectRow requireKnown(long id) {
                if (knownId != null && id == knownId) {
                    return new ProjectRow(id, "P00001", "智能工厂", 0, true);
                }
                throw new BusinessException(ErrorCode.PROJECT_INVALID, "项目不合法");
            }
        });
    }

    private static String append(ProjectFilterSql filter, Map<String, Object> params, String raw) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        filter.append(where, params, raw);
        return where.toString();
    }

    @Test
    void blankValuesDoNotChangeTheSql() {
        for (String raw : new String[]{null, "", "   "}) {
            Map<String, Object> params = new HashMap<>();
            assertThat(append(filter(null), params, raw)).isEqualTo(" WHERE 1 = 1");
            assertThat(params).isEmpty();
        }
    }

    @Test
    void noneMeansProjectFormTasksWithoutProject() {
        Map<String, Object> params = new HashMap<>();
        String where = append(filter(null), params, " none ");
        assertThat(where).contains("t.source_project_id IS NULL");
        assertThat(where).contains("s.form = 'PROJECT'");
        assertThat(where).doesNotContain(":projectId");
        assertThat(params).isEmpty();
    }

    @Test
    void malformedIdsAreBadRequests() {
        for (String raw : new String[]{"abc", "0", "-3", "1.5"}) {
            Map<String, Object> params = new HashMap<>();
            assertThatThrownBy(() -> filter(null).append(new StringBuilder(" WHERE 1 = 1"), params, raw))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).code())
                            .isEqualTo(ErrorCode.PROJECT_INVALID));
            assertThat(params).isEmpty();
        }
    }

    @Test
    void deletedOrUnknownIdIsBadRequest() {
        Map<String, Object> params = new HashMap<>();
        assertThatThrownBy(() -> filter(7L).append(new StringBuilder(" WHERE 1 = 1"), params, "8"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo(ErrorCode.PROJECT_INVALID));
    }

    @Test
    void knownIdBindsOneNamedParameter() {
        Map<String, Object> params = new HashMap<>();
        String where = append(filter(7L), params, "007");
        assertThat(where).endsWith(" AND t.source_project_id = :projectId");
        assertThat(params).containsExactly(Map.entry("projectId", 7L));
    }
}
