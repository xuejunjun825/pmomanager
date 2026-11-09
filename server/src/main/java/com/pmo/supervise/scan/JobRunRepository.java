package com.pmo.supervise.scan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRun, JobRun.JobRunId> {

    default Optional<JobRun> find(String jobKey, LocalDate runDate) {
        return findById(new JobRun.JobRunId(jobKey, runDate));
    }
}
