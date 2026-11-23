package fu.coreservice.service.mail;

import fu.coreservice.repository.CoursePrivacyMailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service @RequiredArgsConstructor @Slf4j
public class CoursePrivacyMailDelivery {
    private final CoursePrivacyMailRepository queue;
    private final CoursePrivacyMailer mailer;

    // At-least-once delivery: SMTP cannot participate in the database transaction.
    @Scheduled(fixedDelayString = "${app.course-privacy-mail.poll-ms:30000}")
    @Transactional
    public void deliver() {
        for (var item : queue.findTop20ByNextAttemptAtLessThanEqualOrderByIdAsc(LocalDateTime.now())) {
            try {
                mailer.send(item);
                queue.delete(item);
            } catch (RuntimeException failure) {
                item.setAttempts(Math.min(item.getAttempts() + 1, 30));
                item.setNextAttemptAt(LocalDateTime.now().plusMinutes(Math.min(60, 1L << Math.min(item.getAttempts(), 6))));
                log.warn("Course privacy email delivery failed; queued for retry, mailId={}", item.getId());
            }
        }
    }
}
