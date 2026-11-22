package fu.coreservice.service;

import fu.coreservice.event.InstructorReviewedEvent;
import fu.coreservice.listener.InstructorReviewMailListener;
import fu.coreservice.service.mail.InstructorReviewMailer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstructorReviewMailTransactionTest {
    private final InstructorReviewMailer mailer = mock(InstructorReviewMailer.class);
    private final ActivityAlertService alerts = mock(ActivityAlertService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final InstructorReviewedEvent event = new InstructorReviewedEvent(7L, "teacher@example.com", "An", true, true, null);
    private AnnotationConfigApplicationContext context;
    private EmbeddedDatabase database;
    private TransactionTemplate transactions;

    @BeforeEach void setup() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(InstructorReviewMailListener.class,
                () -> new InstructorReviewMailListener(mailer, queued::add, alerts));
        context.refresh();
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (database != null) database.shutdown();
    }

    @Test void queuesOnlyAfterCommitAndSendsInWorker() {
        transactions.executeWithoutResult(status -> {
            context.publishEvent(event);
            assertThat(queued).isEmpty();
            verifyNoInteractions(mailer);
        });
        assertThat(queued).hasSize(1);
        verifyNoInteractions(mailer);
        queued.getFirst().run();
        verify(mailer).send(event);
        verifyNoInteractions(alerts);
    }

    @Test void rolledBackReviewNeverQueuesEmail() {
        transactions.executeWithoutResult(status -> {
            context.publishEvent(event);
            status.setRollbackOnly();
        });
        assertThat(queued).isEmpty();
        verifyNoInteractions(mailer, alerts);
    }

    @Test void eventOutsideTransactionDoesNotNotifyAboutAnUncommittedReview() {
        context.publishEvent(event);
        assertThat(queued).isEmpty();
        verifyNoInteractions(mailer);
    }

    @Test void smtpFailureDoesNotFailReviewAndIsReported() {
        doThrow(new MailSendException("provider unavailable")).when(mailer).send(event);
        transactions.executeWithoutResult(status -> context.publishEvent(event));
        assertThatCode(() -> queued.getFirst().run()).doesNotThrowAnyException();
        verify(alerts).repeatedSystemFailure("INSTRUCTOR_REVIEW_DELIVERY", "USER");
    }

    @Test void rejectedQueueAndAlertFailureDoNotFailCommittedReview() {
        TaskExecutor fullQueue = task -> { throw new RejectedExecutionException("queue full"); };
        doThrow(new IllegalStateException("alert storage unavailable")).when(alerts)
                .repeatedSystemFailure("INSTRUCTOR_REVIEW_DELIVERY", "USER");
        context.close();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(InstructorReviewMailListener.class,
                () -> new InstructorReviewMailListener(mailer, fullQueue, alerts));
        context.refresh();
        assertThatCode(() -> transactions.executeWithoutResult(status -> context.publishEvent(event)))
                .doesNotThrowAnyException();
        verifyNoInteractions(mailer);
        verify(alerts).repeatedSystemFailure("INSTRUCTOR_REVIEW_DELIVERY", "USER");
    }
}
