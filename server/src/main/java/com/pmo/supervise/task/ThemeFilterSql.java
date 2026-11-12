package com.pmo.supervise.task;

import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 主题筛选的唯一 SQL 谓词（r7 / AC-13）。
 *
 * <p>任务池、看板、矩阵计数与甘特全部经这里拼 {@code t.theme_id} 条件：
 * 空 → 完全不动 SQL（保证「不带参数 = 升级前结果」，默认计数零回归）；
 * {@code none} → 未归主题；数字 → 指定主题；其余 → 400 THEME_INVALID。
 * 已删除/不存在的主题 id 不报错，只返回空集合——筛选是读路径，
 * 与「停用主题不可新选」的写路径校验分开（写路径在 TaskService）。
 */
@Component
public class ThemeFilterSql {

    /** 「未归主题」：theme_id IS NULL，与下拉里「未归主题」选项同语义。 */
    public static final String UNASSIGNED = "none";

    public void append(StringBuilder where, Map<String, Object> params, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.trim();
        if (UNASSIGNED.equalsIgnoreCase(value)) {
            where.append(" AND t.theme_id IS NULL");
            return;
        }
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalidTheme();
        }
        if (id <= 0) {
            throw invalidTheme();
        }
        where.append(" AND t.theme_id = :themeId");
        params.put("themeId", id);
    }

    private static BusinessException invalidTheme() {
        return new BusinessException(ErrorCode.THEME_INVALID, "主题不合法");
    }
}
