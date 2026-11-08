package fu.coreservice.repository;

import fu.coreservice.entity.MessageSourceCourse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageSourceCourseRepository extends JpaRepository<MessageSourceCourse, Long> {
    void deleteByMessage_MessageId(Long messageId);
    List<MessageSourceCourse> findByMessage_MessageIdIn(List<Long> messageIds);
    List<MessageSourceCourse> findByMessage_MessageId(Long messageId);
}
