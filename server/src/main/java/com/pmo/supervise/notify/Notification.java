package com.pmo.supervise.notify;

import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.NotifyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 站内通知（notifications）：dedup_key 唯一约束是幂等的唯一依据。 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "task_id")
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private NotifyType type;

    @Column(name = "notify_date", nullable = false)
    private LocalDate notifyDate;

    @Column(name = "dedup_key", nullable = false, length = 128)
    private String dedupKey;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "dingtalk_status", nullable = false, length = 24)
    private DingTalkStatus dingtalkStatus = DingTalkStatus.PENDING;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TINYINT)
    @Column(name = "dingtalk_attempts", nullable = false)
    private int dingtalkAttempts;

    @Column(name = "dingtalk_next_retry_at")
    private LocalDateTime dingtalkNextRetryAt;

    @Column(name = "dingtalk_last_error", length = 500)
    private String dingtalkLastError;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public NotifyType getType() {
        return type;
    }

    public void setType(NotifyType type) {
        this.type = type;
    }

    public LocalDate getNotifyDate() {
        return notifyDate;
    }

    public void setNotifyDate(LocalDate notifyDate) {
        this.notifyDate = notifyDate;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public DingTalkStatus getDingtalkStatus() {
        return dingtalkStatus;
    }

    public void setDingtalkStatus(DingTalkStatus dingtalkStatus) {
        this.dingtalkStatus = dingtalkStatus;
    }

    public int getDingtalkAttempts() {
        return dingtalkAttempts;
    }

    public void setDingtalkAttempts(int dingtalkAttempts) {
        this.dingtalkAttempts = dingtalkAttempts;
    }

    public LocalDateTime getDingtalkNextRetryAt() {
        return dingtalkNextRetryAt;
    }

    public void setDingtalkNextRetryAt(LocalDateTime dingtalkNextRetryAt) {
        this.dingtalkNextRetryAt = dingtalkNextRetryAt;
    }

    public String getDingtalkLastError() {
        return dingtalkLastError;
    }

    public void setDingtalkLastError(String dingtalkLastError) {
        this.dingtalkLastError = dingtalkLastError;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
