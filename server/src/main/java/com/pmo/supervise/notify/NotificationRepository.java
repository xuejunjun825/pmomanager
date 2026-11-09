package com.pmo.supervise.notify;

import com.pmo.supervise.common.DingTalkStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
            select n from Notification n
            where n.dingtalkStatus = :status
              and n.dingtalkAttempts < 3
              and (n.dingtalkNextRetryAt is null or n.dingtalkNextRetryAt <= :now)
            order by n.id asc
            """)
    List<Notification> findRetryable(@Param("status") DingTalkStatus status,
                                     @Param("now") LocalDateTime now,
                                     Pageable pageable);
}
