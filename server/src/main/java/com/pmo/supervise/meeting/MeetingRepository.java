package com.pmo.supervise.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

/** 会议写模型仓储；读模型（含闭环计数）见 {@link MeetingService} 的原生 SQL。 */
public interface MeetingRepository extends JpaRepository<Meeting, Long> {
}
