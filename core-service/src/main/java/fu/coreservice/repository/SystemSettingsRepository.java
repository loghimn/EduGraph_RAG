package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.SystemSettings;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSettings, Long> {
    Optional<SystemSettings> findByCourse(Course course);
    Optional<SystemSettings> findByCourse_CourseId(Long courseId);
    List<SystemSettings> findByUpdatedBy(User updatedBy);
}
