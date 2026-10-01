package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    Optional<Course> findByCourseIdAndUser(Long courseId, User user);
    Page<Course> findByUser(User user, Pageable pageable);
    Page<Course> findByUserAndIsPublic(User user, Boolean isPublic, Pageable pageable);

    @Query("""
    SELECT c
    FROM Course c
    WHERE c.isPublic = true
      AND (
          CAST(:keyword AS string) IS NULL
          OR LOWER(c.courseName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
          OR LOWER(c.description) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
      )
      AND (
          CAST(:domain AS string) IS NULL
          OR LOWER(c.domain) = LOWER(CAST(:domain AS string))
      )
      AND (
          CAST(:language AS string) IS NULL
          OR LOWER(c.language) = LOWER(CAST(:language AS string))
      )
""")
    Page<Course> searchPublicCourses(
            @Param("keyword") String keyword,
            @Param("domain") String domain,
            @Param("language") String language,
            Pageable pageable
    );

    Optional<Course> findByCourseIdAndIsPublicTrue(Long courseId);
}
