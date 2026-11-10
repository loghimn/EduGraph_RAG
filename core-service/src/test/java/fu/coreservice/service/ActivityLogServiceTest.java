package fu.coreservice.service;

import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.entity.*;
import fu.coreservice.repository.ActivityLogRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.impl.ActivityLogServiceImpl;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActivityLogServiceTest {
    private SessionFactory factory;
    private ActivityLogService service;
    private JpaTransactionManager transactionManager;
    private Long actorId;

    @BeforeEach
    void setup() {
        factory = new Configuration().addAnnotatedClass(User.class).addAnnotatedClass(ActivityLog.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true").buildSessionFactory();
        actorId = factory.fromTransaction(session -> {
            User user = user("actor");
            session.persist(user);
            return user.getUserId();
        });
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
        transactionManager = new JpaTransactionManager(factory);
        service = new ActivityLogServiceImpl(repositories.getRepository(ActivityLogRepository.class),
                repositories.getRepository(UserRepository.class), transactionManager);
    }

    @AfterEach
    void cleanup() {
        if (factory != null) factory.close();
    }

    @Test
    void storesActorAndAnonymousSystemOutcomeWithGeneratedTimestamp() {
        assertThat(service.log(request(actorId))).isTrue();
        assertThat(service.log(new ActivityLogRequest(null, ActivityActorType.SYSTEM,
                "DOCUMENT_PROCESS", ActivityLogStatus.INTERRUPTED, "DOCUMENT", null, null, null))).isTrue();
        factory.inTransaction(session -> {
            var logs = session.createSelectionQuery("from ActivityLog order by logId", ActivityLog.class)
                    .getResultList();
            assertThat(logs).hasSize(2);
            assertThat(logs.getFirst().getActor().getUserId()).isEqualTo(actorId);
            assertThat(logs.getFirst().getCreatedAt()).isNotNull();
            assertThat(logs.getFirst().getStatus()).isEqualTo(ActivityLogStatus.FAILED);
            assertThat(logs.getFirst().getDurationMs()).isEqualTo(50L);
            assertThat(logs.getLast().getActor()).isNull();
            assertThat(logs.getLast().getStatus()).isEqualTo(ActivityLogStatus.INTERRUPTED);
        });
    }

    @Test
    void logSurvivesRollbackOfBusinessTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
            entityManager.persist(user("rolled-back"));
            assertThat(service.log(request(actorId))).isTrue();
            outer.setRollbackOnly();
        });
        factory.inTransaction(session -> {
            assertThat(session.createSelectionQuery("select count(l) from ActivityLog l", Long.class)
                    .getSingleResult()).isEqualTo(1L);
            assertThat(session.createSelectionQuery("select count(u) from User u", Long.class)
                    .getSingleResult()).isEqualTo(1L);
        });
    }

    @Test
    void foreignKeyFailureDoesNotRollBackBusinessTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
            entityManager.persist(user("committed"));
            assertThat(service.log(request(Long.MAX_VALUE))).isFalse();
            assertThat(outer.isRollbackOnly()).isFalse();
        });
        factory.inTransaction(session -> {
            assertThat(session.createSelectionQuery("select count(l) from ActivityLog l", Long.class)
                    .getSingleResult()).isZero();
            assertThat(session.createSelectionQuery("select count(u) from User u", Long.class)
                    .getSingleResult()).isEqualTo(2L);
        });
    }

    @Test
    void commitFailureIsReportedWithoutEscapingToCaller() {
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        doThrow(new IllegalStateException("commit failed")).when(manager).commit(transaction);
        var logs = mock(ActivityLogRepository.class);
        var target = new ActivityLogServiceImpl(logs, mock(UserRepository.class), manager);
        assertThat(target.log(request(null))).isFalse();
        verify(logs).saveAndFlush(any(ActivityLog.class));
        verify(manager).commit(transaction);
    }

    @Test
    void rejectsInvalidDurationAndMissingActionBeforeWriting() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivityLogRequest(null,
                ActivityActorType.SYSTEM, "CHAT_GENERATE", ActivityLogStatus.FAILED,
                "CHAT_MESSAGE", null, null, -1L));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivityLogRequest(null,
                ActivityActorType.SYSTEM, " ", ActivityLogStatus.FAILED,
                "CHAT_MESSAGE", null, null, null));
    }

    private ActivityLogRequest request(Long id) {
        return new ActivityLogRequest(id, ActivityActorType.USER, "CHAT_GENERATE",
                ActivityLogStatus.FAILED, "CHAT_MESSAGE", "AI_TIMEOUT", "AI response timed out", 50L);
    }

    private User user(String name) {
        return User.builder().email(name + "@test.local").username(name)
                .passwordHash("test").role(UserRole.STUDENT).build();
    }
}
