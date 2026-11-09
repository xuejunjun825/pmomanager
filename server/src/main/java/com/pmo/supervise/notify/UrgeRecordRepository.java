package com.pmo.supervise.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UrgeRecordRepository extends JpaRepository<UrgeRecord, Long> {

    Optional<UrgeRecord> findFirstByTaskIdAndActorIdOrderByCreatedAtDesc(long taskId, long actorId);

    long countByTaskId(long taskId);
}
