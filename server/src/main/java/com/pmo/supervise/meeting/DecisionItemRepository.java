package com.pmo.supervise.meeting;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DecisionItemRepository extends JpaRepository<DecisionItem, Long> {

    /** 编辑/删除/转任务一律先取行锁，让「转任务 vs 编辑」这类并发有确定裁决。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DecisionItem d where d.id = :id")
    Optional<DecisionItem> findByIdForUpdate(@Param("id") long id);

    List<DecisionItem> findByMeetingIdOrderBySortNoAscIdAsc(long meetingId);

    /** 批量转任务：按 id 升序取行锁，保证同一决议项并发转换被串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DecisionItem d where d.id in :ids order by d.id")
    List<DecisionItem> findByIdInForUpdate(@Param("ids") List<Long> ids);

    @Query("select coalesce(max(d.sortNo), -1) + 1 from DecisionItem d where d.meetingId = :meetingId")
    int nextSortNo(@Param("meetingId") long meetingId);

    @Query("select count(d) from DecisionItem d where d.meetingId = :meetingId")
    int countByMeetingId(@Param("meetingId") long meetingId);

    /** 该决议项在当前排序中的行号（0 基），422 的 fieldErrors.index 用它定位到行。 */
    @Query("""
            select count(d) from DecisionItem d
            where d.meetingId = :meetingId
              and (d.sortNo < :sortNo or (d.sortNo = :sortNo and d.id < :id))
            """)
    int positionOf(@Param("meetingId") long meetingId, @Param("sortNo") int sortNo, @Param("id") long id);
}
