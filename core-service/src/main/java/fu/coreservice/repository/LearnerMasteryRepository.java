package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.LearnerMastery;
import fu.coreservice.entity.MasteryStatus;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LearnerMasteryRepository extends JpaRepository<LearnerMastery, Long> {
    List<LearnerMastery> findByLearner(User learner);
    List<LearnerMastery> findByCourse(Course course);
    List<LearnerMastery> findByLearnerAndCourse(User learner, Course course);
    Optional<LearnerMastery> findByLearnerAndCourseAndNeo4jConceptId(User learner, Course course, String neo4jConceptId);
    List<LearnerMastery> findByMasteryStatus(MasteryStatus masteryStatus);
}
