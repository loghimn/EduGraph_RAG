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
import java.util.Optional;

import java.util.List;

@Repository
public interface LearningPathPlanRepository extends JpaRepository<LearningPathPlan, Long> {
    Optional<LearningPathPlan> findByPlanIdAndLearner(Long planId, User learner);

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
