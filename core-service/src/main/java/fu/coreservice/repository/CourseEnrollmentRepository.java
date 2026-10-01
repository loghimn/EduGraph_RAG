package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.CourseEnrollment;
import fu.coreservice.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CourseEnrollmentRepository extends JpaRepository<CourseEnrollment, Long> {
    boolean existsByCourseAndUser(Course course, User user);
    Page<CourseEnrollment> findByUser(User user, Pageable pageable);
    Optional<CourseEnrollment> findByCourse_CourseIdAndUser(Long courseId, User user);
    Page<CourseEnrollment> findByCourse(Course course, Pageable pageable);
}
