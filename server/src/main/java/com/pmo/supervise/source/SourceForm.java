package com.pmo.supervise.source;

/**
 * 来源形态（TECHNICAL §3.1 / r4 §3.2）：有限业务枚举，不是可配置数据。
 * MEETING 要求绑定会议与决议项；PROJECT 要求绑定项目；NOTE 要求填写自由说明。
 *
 * <p>形态只认 {@code source_types.form} 这一列，**绝不按来源编码推断**：内置来源的编码
 * {@code PROJECT} 至今仍是 NOTE 形态，编码与形态同名不同义。
 */
public enum SourceForm {
    MEETING("需绑定会议"),
    PROJECT("需绑定项目"),
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
