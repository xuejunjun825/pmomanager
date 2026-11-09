package com.pmo.supervise.scan;

import com.pmo.supervise.common.JobKey;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 按 (jobKey, runDate) 抢占与收尾（TECHNICAL §4.7）：
 * 主键唯一即分布式锁的本地版，重启/重复触发都不会产生第二批通知。
 * 抢占使用 INSERT 捕主键冲突（JPA 对有主键的实体执行 merge，会变成 UPDATE，无法表达“插入失败”）。
 */
@Service
public class JobRunService {

    private final JdbcTemplate jdbc;

    public JobRunService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 抢占成功返回 true；当日已存在记录返回 false。 */
    public boolean tryStart(JobKey jobKey, LocalDate date, LocalDateTime now) {
        try {
            jdbc.update("""
                    INSERT INTO job_runs (job_key, run_date, status, started_at)
                    VALUES (?, ?, 'RUNNING', ?)
                    """, jobKey.name(), date, now);
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    public boolean hasRun(JobKey jobKey, LocalDate date) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_runs WHERE job_key = ? AND run_date = ?",
                Integer.class, jobKey.name(), date);
        return count != null && count > 0;
    }

    public void finish(JobKey jobKey, LocalDate date, String detail, LocalDateTime now) {
        String status = detail != null && detail.startsWith("error=") ? "FAILED" : "SUCCESS";
        jdbc.update("""
                UPDATE job_runs SET status = ?, detail = ?, finished_at = ?
                WHERE job_key = ? AND run_date = ?
                """, status, truncate(detail), now, jobKey.name(), date);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
