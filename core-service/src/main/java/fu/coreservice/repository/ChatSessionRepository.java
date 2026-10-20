package fu.coreservice.repository;

import fu.coreservice.entity.ChatSession;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.LearningPathPlan;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Page<ChatSession> findByLearnerAndCourse(User learner, Course course, Pageable pageable);
    Optional<ChatSession> findBySessionIdAndCourseAndLearner(Long sessionId, Course course, User learner);
    List<ChatSession> findByLearner(User learner);
    List<ChatSession> findByCourse(Course course);
    List<ChatSession> findByPlan(LearningPathPlan plan);
    List<ChatSession> findByLearnerAndCourseOrderByLastActiveDesc(User learner, Course course);
}
