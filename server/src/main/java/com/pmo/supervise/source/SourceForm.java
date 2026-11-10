package com.pmo.supervise.source;

/**
 * 来源形态（TECHNICAL §3.1）：有限业务枚举，不是可配置数据。
 * MEETING 要求绑定会议与决议项；NOTE 要求填写自由说明。
 */
public enum SourceForm {
    MEETING("需绑定会议"),
    NOTE("自由说明");

    private final String label;

    SourceForm(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static SourceForm of(String dbValue) {
        return SourceForm.valueOf(dbValue);
    }
}
