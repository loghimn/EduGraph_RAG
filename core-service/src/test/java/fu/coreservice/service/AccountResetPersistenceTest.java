package fu.coreservice.service;

import fu.coreservice.service.impl.AccountServiceImpl;
import fu.coreservice.dto.auth.ForgotPasswordRequest;
import fu.coreservice.dto.auth.ResetPasswordRequest;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.entity.UserSession;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.InvalidResetOtpException;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AccountResetPersistenceTest {
    private SessionFactory factory;
    private AccountService service;
    private PasswordResetMailer mailer;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private static final String EMAIL = "reset@example.com";

    @BeforeEach void setup() {
        factory = new Configuration().addAnnotatedClass(User.class).addAnnotatedClass(UserSession.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true").buildSessionFactory();
        factory.inTransaction(session -> {
            User user = User.builder().email(EMAIL).username("reset-user").role(UserRole.STUDENT)
                    .passwordHash(encoder.encode("old-password"))
                    .passwordResetOtpHash(encoder.encode("012345"))
                    .passwordResetOtpExpiresAt(LocalDateTime.now().plusMinutes(5)).build();
            session.persist(user);
            session.persist(UserSession.builder().user(user).refreshToken("existing-session")
                    .expiresAt(LocalDateTime.now().plusDays(1)).build());
        });
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        mailer = mock(PasswordResetMailer.class);
        AccountServiceImpl target = new AccountServiceImpl(repositories.getRepository(UserRepository.class),
                repositories.getRepository(UserSessionRepository.class), encoder, mailer);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new JpaTransactionManager(factory));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(AccountService.class);
        proxy.addAdvice(interceptor);
        service = (AccountService) proxy.getProxy();
    }
    @AfterEach void cleanup() { if (factory != null) factory.close(); }

    @Test void concurrentUseOfSameOtpSucceedsExactlyOnceAndDeletesSessions() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                service.resetPassword(new ResetPasswordRequest(EMAIL, "012345", "new-password"));
                return true;
            } catch (AppException ex) { return false; }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            start.countDown();
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }
        factory.inTransaction(session -> {
            User user = session.createQuery("from User", User.class).getSingleResult();
            assertThat(user.getPasswordResetOtpHash()).isNull();
            assertThat(encoder.matches("new-password", user.getPasswordHash())).isTrue();
            assertThat(session.createQuery("select count(s) from UserSession s", Long.class)
                    .getSingleResult()).isZero();
        });
    }

    @Test void failedAttemptsCommitAcrossRequestsAndFifthFailureInvalidatesOtp() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, "999999", "new-password")))
                    .isInstanceOf(InvalidResetOtpException.class);
            int expected = attempt;
            factory.inTransaction(session -> assertThat(session.createQuery("from User", User.class)
                    .getSingleResult().getPasswordResetOtpAttempts()).isEqualTo(expected));
        }
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, "999999", "new-password")))
                .isInstanceOf(InvalidResetOtpException.class);
        factory.inTransaction(session -> assertThat(session.createQuery("from User", User.class)
                .getSingleResult().getPasswordResetOtpHash()).isNull());
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, "012345", "new-password")))
                .isInstanceOf(InvalidResetOtpException.class);
        factory.inTransaction(session -> {
            User user = session.createQuery("from User", User.class).getSingleResult();
            assertThat(encoder.matches("old-password", user.getPasswordHash())).isTrue();
            assertThat(session.createQuery("select count(s) from UserSession s", Long.class).getSingleResult()).isOne();
        });
    }

    @Test void expiredOtpIsClearedAndCannotReset() {
        factory.inTransaction(session -> session.createQuery("from User", User.class).getSingleResult()
                .setPasswordResetOtpExpiresAt(LocalDateTime.now().minusSeconds(1)));
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, "012345", "new-password")))
                .isInstanceOf(InvalidResetOtpException.class);
        factory.inTransaction(session -> assertThat(session.createQuery("from User", User.class)
                .getSingleResult().getPasswordResetOtpHash()).isNull());
    }

    @Test void resendingCreatesFreshHashAndClearsAttemptCounter() {
        factory.inTransaction(session -> {
            User user = session.createQuery("from User", User.class).getSingleResult();
            user.setPasswordResetOtpAttempts(4);
            user.setResetRequestedAt(LocalDateTime.now().minusMinutes(2));
        });
        service.forgotPassword(new ForgotPasswordRequest(EMAIL));
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(EMAIL), otp.capture());
        assertThat(otp.getValue()).matches("[0-9]{6}");
        factory.inTransaction(session -> {
            User user = session.createQuery("from User", User.class).getSingleResult();
            assertThat(user.getPasswordResetOtpAttempts()).isZero();
            assertThat(encoder.matches(otp.getValue(), user.getPasswordResetOtpHash())).isTrue();
        });
        service.resetPassword(new ResetPasswordRequest(EMAIL, otp.getValue(), "new-password"));
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, otp.getValue(), "new-password")))
                .isInstanceOf(InvalidResetOtpException.class);
    }
}
