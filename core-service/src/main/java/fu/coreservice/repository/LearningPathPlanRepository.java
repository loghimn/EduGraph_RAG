package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.LearningPathPlan;
import fu.coreservice.entity.LearningPathStatus;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

@Repository
public interface LearningPathPlanRepository extends JpaRepository<LearningPathPlan, Long> {
    Optional<LearningPathPlan> findByPlanIdAndLearner(Long planId, User learner);

    // Serialize updates within a plan, including simultaneous completion of a stage.
    // Do not join-fetch nullable relationships or collections in this locking query.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LearningPathPlan p where p.planId = :planId and p.learner = :learner")
    Optional<LearningPathPlan> findOwnedForUpdate(@Param("planId") Long planId, @Param("learner") User learner);

    @EntityGraph(attributePaths = "document")
    Page<LearningPathPlan> findByLearnerAndCourse(User learner, Course course, Pageable pageable);

    @EntityGraph(attributePaths = "document")
    Page<LearningPathPlan> findByLearnerAndCourseAndStatus(
            User learner, Course course, LearningPathStatus status, Pageable pageable);
    List<LearningPathPlan> findByLearner(User learner);
    List<LearningPathPlan> findByCourse(Course course);
    List<LearningPathPlan> findByLearnerAndCourse(User learner, Course course);
    List<LearningPathPlan> findByLearnerAndCourseAndStatus(User learner, Course course, LearningPathStatus status);
    List<LearningPathPlan> findByLearnerAndStatus(User learner, LearningPathStatus status);
}
