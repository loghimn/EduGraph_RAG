package fu.coreservice.repository;

import fu.coreservice.entity.ChatSession;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.LearningPathPlan;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findByLearner(User learner);
    List<ChatSession> findByCourse(Course course);
    List<ChatSession> findByPlan(LearningPathPlan plan);
    List<ChatSession> findByLearnerAndCourseOrderByLastActiveDesc(User learner, Course course);
}
