package com.pmo.supervise.dashboard.dto;

/**
 * 矩阵筛选条件（GET /api/matrix，AC-15）。
 * range：WEEK=本周一至本周日 / MONTH=当月 1 号至月末 / ALL=不限（默认），按 due_date 过滤；
 * includeDisabled：默认 true（AC-15 要求默认含已停用用户），显式 false 时仅统计在职用户。
 */
public class MatrixFilter {

    private String sourceType;
    private String range = "ALL";
    private Boolean includeDisabled;

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRange() {
        return range;
    }

    public void setRange(String range) {
        this.range = range;
    }

    public Boolean getIncludeDisabled() {
        return includeDisabled;
    }

    public void setIncludeDisabled(Boolean includeDisabled) {
        this.includeDisabled = includeDisabled;
    }
}
