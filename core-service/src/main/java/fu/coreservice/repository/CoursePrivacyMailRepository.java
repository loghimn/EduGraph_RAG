package fu.coreservice.repository;

import fu.coreservice.entity.CoursePrivacyMail;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.LocalDateTime;
import java.util.List;

public interface CoursePrivacyMailRepository extends JpaRepository<CoursePrivacyMail, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<CoursePrivacyMail> findTop20ByNextAttemptAtLessThanEqualOrderByIdAsc(LocalDateTime now);
}
