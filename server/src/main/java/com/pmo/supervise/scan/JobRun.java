package com.pmo.supervise.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/** 任务运行记录（job_runs）：(job_key, run_date) 主键同时保证一天一次与当日补跑。 */
@Entity
@Table(name = "job_runs")
public class JobRun {

    @EmbeddedId
    private JobRunId id;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 500)
    private String detail;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public JobRun() {
    }

    public JobRun(String jobKey, LocalDate runDate, String status, LocalDateTime startedAt) {
        this.id = new JobRunId(jobKey, runDate);
        this.status = status;
        this.startedAt = startedAt;
    }

    public JobRunId getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    @Embeddable
    public static class JobRunId implements Serializable {

        @Column(name = "job_key", length = 32)
        private String jobKey;

        @Column(name = "run_date")
        private LocalDate runDate;

        public JobRunId() {
        }

        public JobRunId(String jobKey, LocalDate runDate) {
            this.jobKey = jobKey;
            this.runDate = runDate;
        }

        public String getJobKey() {
            return jobKey;
        }

        public LocalDate getRunDate() {
            return runDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof JobRunId that)) {
                return false;
            }
            return Objects.equals(jobKey, that.jobKey) && Objects.equals(runDate, that.runDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobKey, runDate);
        }
    }
}
