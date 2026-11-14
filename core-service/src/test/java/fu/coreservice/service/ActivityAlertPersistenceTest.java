package fu.coreservice.service;

import fu.coreservice.listener.PrivilegedChangeListener;

import fu.coreservice.service.impl.ActivityAlertServiceImpl;

import fu.coreservice.config.ActivityAlertProperties;
import fu.coreservice.entity.*;
import fu.coreservice.repository.ActivityLogRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.impl.ActivityLogServiceImpl;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ActivityAlertPersistenceTest {
    private SessionFactory factory;
    private ActivityAlertService alerts;
    private TransactionTemplate tx;
    private Long userId;

    @BeforeEach
    void setup() {
        var beans = new DefaultListableBeanFactory();
        var injection = new org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor();
        injection.setBeanFactory(beans);
        beans.addBeanPostProcessor(injection);
        beans.registerSingleton("privilegedChangeListener", new PrivilegedChangeListener(
                beans.getBeanProvider(ActivityAlertService.class)));
        var config = new Configuration().addAnnotatedClass(User.class).addAnnotatedClass(Course.class)
                .addAnnotatedClass(SystemSettings.class).addAnnotatedClass(ActivityLog.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        config.getProperties().put("hibernate.resource.beans.container", new SpringBeanContainer(beans));
        factory = config.buildSessionFactory();
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
        var manager = new JpaTransactionManager(factory);
        alerts = new ActivityAlertServiceImpl(new ActivityLogServiceImpl(repositories.getRepository(ActivityLogRepository.class),
                repositories.getRepository(UserRepository.class), manager),
                repositories.getRepository(UserRepository.class), new ActivityAlertProperties());
        beans.registerSingleton("activityAlertService", alerts);
        tx = new TransactionTemplate(manager);
        userId = factory.fromTransaction(session -> {
            User user = User.builder().email("student@test.local").username("student")
                    .passwordHash("test").role(UserRole.STUDENT).build();
            session.persist(user);
            return user.getUserId();
        });
    }

    @AfterEach
    void cleanup() { factory.close(); }

    @Test
    void destructiveOutcomeWaitsForCommitAndIsSuppressedOnRollback() {
        tx.executeWithoutResult(transaction -> {
            alerts.destructiveAfterCommit(userId, "DOCUMENT_CANCEL", "DOCUMENT");
            assertThat(logs()).isEmpty();
            transaction.setRollbackOnly();
        });
        assertThat(logs()).isEmpty();
        tx.executeWithoutResult(transaction -> alerts.destructiveAfterCommit(userId, "DOCUMENT_CANCEL", "DOCUMENT"));
        assertThat(logs()).singleElement().satisfies(log -> {
            assertThat(log.getStatus()).isEqualTo(ActivityLogStatus.SUCCESS);
            assertThat(log.getActor().getUserId()).isEqualTo(userId);
        });
    }

    @Test
    void criticalIncidentSurvivesBusinessRollback() {
        tx.executeWithoutResult(transaction -> {
            alerts.critical("STORAGE_CLEANUP_FAILED", "DOCUMENT", "DATA_INCONSISTENCY");
            transaction.setRollbackOnly();
        });
        assertThat(logs()).singleElement().satisfies(log ->
                assertThat(log.getErrorCode()).isEqualTo("DATA_INCONSISTENCY"));
    }

    @Test
    void profileEditDoesNotLogButCommittedRoleChangeDoes() {
        tx.executeWithoutResult(transaction -> em().find(User.class, userId).setUsername("changed"));
        assertThat(logs()).isEmpty();
        tx.executeWithoutResult(transaction -> em().find(User.class, userId).setRole(UserRole.ADMIN));
        assertThat(logs()).singleElement().satisfies(log ->
                assertThat(log.getAction()).isEqualTo("USER_PRIVILEGE_CHANGED"));
    }

    @Test
    void rolledBackPrivilegeChangeDoesNotCreateSuccessfulAlert() {
        tx.executeWithoutResult(transaction -> {
            em().find(User.class, userId).setRole(UserRole.ADMIN);
            em().flush();
            transaction.setRollbackOnly();
        });
        assertThat(logs()).isEmpty();
    }

    @Test
    void settingsCreateAndUpdateAreObservedWithoutStoringPromptValues() {
        Long settingId = tx.execute(transaction -> {
            var settings = SystemSettings.builder().updatedBy(em().getReference(User.class, userId))
                    .promptTemplate("private prompt value").build();
            em().persist(settings);
            return settings.getSettingId();
        });
        tx.executeWithoutResult(transaction -> em().find(SystemSettings.class, settingId).setTraversalDepth(5));
        assertThat(logs()).extracting(ActivityLog::getAction)
                .containsExactly("SYSTEM_SETTINGS_CREATED", "SYSTEM_SETTINGS_CHANGED");
        assertThat(logs()).allSatisfy(log -> assertThat(log.getMessage()).doesNotContain("private prompt value"));
    }

    private jakarta.persistence.EntityManager em() {
        return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    private List<ActivityLog> logs() {
        return factory.fromTransaction(session -> session.createSelectionQuery(
                "from ActivityLog order by logId", ActivityLog.class).getResultList());
    }
}
