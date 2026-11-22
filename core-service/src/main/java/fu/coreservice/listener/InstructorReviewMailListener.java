package fu.coreservice.listener;

import fu.coreservice.event.InstructorReviewedEvent;
import fu.coreservice.service.ActivityAlertService;
import fu.coreservice.service.mail.InstructorReviewMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
public class InstructorReviewMailListener {
    private final InstructorReviewMailer mailer;
    private final TaskExecutor executor;
    private final ActivityAlertService alerts;

    public InstructorReviewMailListener(InstructorReviewMailer mailer,
            @Qualifier("instructorReviewMailExecutor") TaskExecutor executor, ActivityAlertService alerts) {
        this.mailer = mailer;
        this.executor = executor;
        this.alerts = alerts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewed(InstructorReviewedEvent event) {
        // A rolled-back review must never notify an instructor. SMTP runs off the request thread.
        try {
            executor.execute(() -> {
                try {
                    mailer.send(event);
                } catch (RuntimeException failure) {
                    deliveryFailed(event.userId());
                }
            });
        } catch (RejectedExecutionException failure) {
            deliveryFailed(event.userId());
        }
    }

    private void deliveryFailed(Long userId) {
        // Mail-provider exception messages may contain addresses or credentials.
        log.warn("Instructor review email delivery failed for userId={}", userId);
        try {
            alerts.repeatedSystemFailure("INSTRUCTOR_REVIEW_DELIVERY", "USER");
        } catch (RuntimeException failure) {
            // Alert failures must not turn an already committed review into an API error.
            log.warn("Unable to record instructor review email failure for userId={}", userId);
        }
    }
}
