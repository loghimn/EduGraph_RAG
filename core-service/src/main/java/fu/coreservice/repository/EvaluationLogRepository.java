package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.EvalType;
import fu.coreservice.entity.EvaluationLog;
import fu.coreservice.entity.RetrievalMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvaluationLogRepository extends JpaRepository<EvaluationLog, Long> {
    List<EvaluationLog> findByCourse(Course course);
    List<EvaluationLog> findByCourse_CourseIdOrderByCreatedAtDesc(Long courseId);
    List<EvaluationLog> findByEvalType(EvalType evalType);
    List<EvaluationLog> findByRetrievalMode(RetrievalMode retrievalMode);
}
