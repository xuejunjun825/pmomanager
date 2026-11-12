package com.pmo.supervise.task;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主题筛选谓词单测（r7 / AC-13）：任务池、看板、矩阵与甘特只经这一个类拼主题条件。
 *
 * <p>关键回归是「空值完全不动 SQL」——升级前的所有筛选请求必须逐字不变。
 */
class ThemeFilterSqlTest {

    private final ThemeFilterSql filter = new ThemeFilterSql();

    @Test
    @DisplayName("空/空白 → 不加谓词、不加参数（默认计数零回归）")
    void blankAppendsNothing() {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        filter.append(where, params, null);
        filter.append(where, params, "  ");

        assertThat(where.toString()).isEqualTo(" WHERE 1 = 1");
        assertThat(params).isEmpty();
    }

    @Test
    @DisplayName("none → 未归主题（theme_id IS NULL）")
    void noneFiltersUnassigned() {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        filter.append(where, params, "NONE");

        assertThat(where.toString()).isEqualTo(" WHERE 1 = 1 AND t.theme_id IS NULL");
        assertThat(params).isEmpty();
    }

    @Test
    @DisplayName("数字 → 命名参数绑定，不拼进 SQL")
    void numberBindsNamedParam() {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        filter.append(where, params, " 12 ");

        assertThat(where.toString()).isEqualTo(" WHERE 1 = 1 AND t.theme_id = :themeId");
        assertThat(params).containsEntry("themeId", 12L);
    }

    @Test
    @DisplayName("非数字 / 0 / 负数 → 400 THEME_INVALID")
    void invalidRejected() {
        for (String bad : new String[]{"abc", "0", "-3", "1.5"}) {
            StringBuilder where = new StringBuilder(" WHERE 1 = 1");
            assertThatThrownBy(() -> filter.append(where, new LinkedHashMap<>(), bad))
                    .as("theme=%s", bad)
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(ErrorCode.THEME_INVALID);
                        assertThat(ex.code().status().value()).isEqualTo(400);
                    });
        }
    }
}
