package com.pmo.supervise.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉配置绑定（TECHNICAL §4.6）。缺失项只报名称，不返回值。
 * AC-21 的降级判断只依赖本对象，避免各处自行读环境变量。
 */
@ConfigurationProperties(prefix = "supervise.dingtalk")
public class DingTalkProperties {

    private static final Logger log = LoggerFactory.getLogger(DingTalkProperties.class);

    private String corpId = "";
    private String agentId = "";
    private String appKey = "";
    private String appSecret = "";
    private String apiBase = "https://oapi.dingtalk.com";
    private String loginBase = "https://login.dingtalk.com";

    public boolean isConfigured() {
        return missingKeys().isEmpty();
    }

    public List<String> missingKeys() {
        List<String> missing = new ArrayList<>();
        if (isBlank(corpId)) {
            missing.add("DINGTALK_CORP_ID");
        }
        if (isBlank(agentId)) {
            missing.add("DINGTALK_AGENT_ID");
        }
        if (isBlank(appKey)) {
            missing.add("DINGTALK_APP_KEY");
        }
        if (isBlank(appSecret)) {
            missing.add("DINGTALK_APP_SECRET");
        }
        return missing;
    }

    public void logStatusOnce() {
        if (isConfigured()) {
            log.info("钉钉配置已就绪：工作通知与免登可用");
        } else {
            log.warn("钉钉未配置，以下能力降级为站内：{}（缺失配置：{}）",
                    "工作通知推送、免登", String.join(", ", missingKeys()));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getLoginBase() {
        return loginBase;
    }

    public void setLoginBase(String loginBase) {
        this.loginBase = loginBase;
    }
}
