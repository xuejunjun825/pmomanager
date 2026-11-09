package com.pmo.supervise.notify;

import com.pmo.supervise.common.DingTalkStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 催办记录（urge_records）：保存当次渠道结果快照，与通知行的最新重试状态语义不同。 */
@Entity
@Table(name = "urge_records")
public class UrgeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private long taskId;

    @Column(name = "actor_id", nullable = false)
    private long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dingtalk_status", nullable = false, length = 24)
    private DingTalkStatus dingtalkStatus;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public long getActorId() {
        return actorId;
    }

    public void setActorId(long actorId) {
        this.actorId = actorId;
    }

    public DingTalkStatus getDingtalkStatus() {
        return dingtalkStatus;
    }

    public void setDingtalkStatus(DingTalkStatus dingtalkStatus) {
        this.dingtalkStatus = dingtalkStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
