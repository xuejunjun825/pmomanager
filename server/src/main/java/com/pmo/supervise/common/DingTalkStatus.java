package com.pmo.supervise.common;

/**
 * 钉钉渠道状态（TECHNICAL §3.3 / §4.6）。渠道文案只在这里定义一次，
 * 接口统一返回 channelStatus + channelLabel，页面不再拼文案。
 */
public enum DingTalkStatus {
    PENDING("推送中"),
    SENT("钉钉已送达"),
    FAILED("钉钉失败"),
    NOT_CONFIGURED("钉钉未配置"),
    UNBOUND("未绑定钉钉"),
    SKIPPED("仅站内");

    private final String label;

    DingTalkStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static DingTalkStatus of(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return DingTalkStatus.valueOf(dbValue);
    }
}
