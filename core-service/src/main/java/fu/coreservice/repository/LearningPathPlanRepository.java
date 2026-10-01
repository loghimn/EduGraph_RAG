package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.LearningPathPlan;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LearningPathPlanRepository extends JpaRepository<LearningPathPlan, Long> {
    List<LearningPathPlan> findByLearner(User learner);
    List<LearningPathPlan> findByCourse(Course course);
    List<LearningPathPlan> findByLearnerAndCourse(User learner, Course course);
    Optional<LearningPathPlan> findByLearnerAndCourseAndIsActiveTrue(User learner, Course course);
    List<LearningPathPlan> findByLearnerAndIsActiveTrue(User learner);
}
