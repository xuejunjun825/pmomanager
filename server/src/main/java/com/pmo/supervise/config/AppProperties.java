package com.pmo.supervise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点级配置（非敏感）：时区、站点名、扫描开关与前端地址。
 * 密钥只从环境变量进入 DingTalkProperties，仓库不落真实凭证。
 */
@ConfigurationProperties(prefix = "supervise")
public class AppProperties {

    private String timezone = "Asia/Shanghai";
    private String siteName = "会议任务督办系统";
    private String frontendBaseUrl = "http://localhost:5173";
    private final Scan scan = new Scan();

    public static class Scan {
        private boolean startupCatchupEnabled = true;
        private String cron = "0 0 9 * * *";

        public boolean isStartupCatchupEnabled() {
            return startupCatchupEnabled;
        }

        public void setStartupCatchupEnabled(boolean startupCatchupEnabled) {
            this.startupCatchupEnabled = startupCatchupEnabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public Scan getScan() {
        return scan;
    }
}
