package fu.coreservice.repository;

import fu.coreservice.entity.ConceptQuery;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface ConceptQueryRepository extends JpaRepository<ConceptQuery, Long> {
    boolean existsByMessage_MessageId(Long id);
    interface QueryCount {
        String getConceptId(); String getName(); Long getLearnerId(); Long getQueries(); Long getNegativeFeedback();
    }
    @Query("""
        select q.conceptId as conceptId, max(q.name) as name, q.message.session.learner.userId as learnerId,
               count(q.id) as queries, sum(case when f.rating = -1 then 1 else 0 end) as negativeFeedback
        from ConceptQuery q left join MessageFeedback f on f.message = q.message
        where q.course.courseId = :course and q.message.createdAt >= :since
          and q.message.status = fu.coreservice.entity.ChatMessageStatus.COMPLETED
          and q.message.session.learner.role = fu.coreservice.entity.UserRole.STUDENT
        group by q.conceptId, q.message.session.learner.userId
        """)
    List<QueryCount> counts(@Param("course") Long courseId, @Param("since") LocalDateTime since);
}
