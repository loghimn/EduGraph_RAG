package fu.coreservice.repository;

import fu.coreservice.entity.ActivityLog;
import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    long countByStatusAndCreatedAtGreaterThanEqual(ActivityLogStatus status, LocalDateTime since);

    @EntityGraph(attributePaths = "actor")
    @Query("""
            SELECT l FROM ActivityLog l LEFT JOIN l.actor a
            WHERE (CAST(:from AS LocalDateTime) IS NULL OR l.createdAt >= :from)
              AND (CAST(:to AS LocalDateTime) IS NULL OR l.createdAt < :to)
              AND (CAST(:action AS string) IS NULL OR l.action = :action)
              AND (CAST(:status AS string) IS NULL OR l.status = :status)
              AND (CAST(:actorType AS string) IS NULL OR l.actorType = :actorType)
              AND (CAST(:actorId AS long) IS NULL OR a.userId = :actorId)
            """)
    Page<ActivityLog> searchLogs(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("action") String action,
            @Param("status") ActivityLogStatus status,
            @Param("actorType") ActivityActorType actorType,
            @Param("actorId") Long actorId,
            Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "actor")
    Optional<ActivityLog> findById(Long id);
}
