package com.pmo.supervise.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 系统设置 KV（settings，TECHNICAL §3.3）。主键是配置键本身，
 * 值统一存字符串（布尔 "true"/"false"、时间 "HH:mm"、名单 JSON 数组），
 * 解析只在 {@link SettingService} 一处做，页面与报表任务读到的是同一份值。
 */
@Entity
@Table(name = "settings")
public class Setting {

    @Id
    @Column(name = "k", length = 64, nullable = false)
    private String key;

    @Column(name = "v", length = 2000, nullable = false)
    private String value;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Setting() {
    }

    public Setting(String key, String value, Long updatedBy, LocalDateTime updatedAt) {
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
