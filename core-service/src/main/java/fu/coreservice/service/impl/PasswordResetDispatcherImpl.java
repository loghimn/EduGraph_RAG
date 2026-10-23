package fu.coreservice.service.impl;

import fu.coreservice.entity.User;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.PasswordResetDispatcher;
import fu.coreservice.service.PasswordResetMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
public class PasswordResetDispatcherImpl implements PasswordResetDispatcher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordResetMailer mailer;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;

    public PasswordResetDispatcherImpl(UserRepository users, PasswordEncoder encoder,
            PasswordResetMailer mailer, @Qualifier("passwordResetExecutor") TaskExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.encoder = encoder;
        this.mailer = mailer;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void dispatch(String email) {
        try {
            executor.execute(() -> process(email));
        } catch (RejectedExecutionException ex) {
            // Keep the public response identical, without a caller-runs fallback.
            log.warn("Password reset queue is full or shutting down");
        }
    }

    private void process(String email) {
        try {
            Delivery delivery = transactions.execute(status -> prepare(email));
            if (delivery == null) return;
            // TransactionTemplate has committed and released the lock/connection before SMTP starts.
            try {
                mailer.send(delivery.email(), delivery.otp());
            } catch (MailException ex) {
                transactions.executeWithoutResult(status ->
                        users.clearPasswordResetOtpIfMatches(delivery.userId(), delivery.hash()));
                // Never log OTPs, hashes, addresses or mail-provider exception text.
                log.warn("Password reset email delivery failed for userId={}", delivery.userId());
            }
        } catch (RuntimeException ex) {
            // No worker outcome changes the public forgot-password response.
            log.warn("Password reset background processing failed");
        }
    }

    private Delivery prepare(String email) {
        User user = users.findLockedByEmail(email).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())
                || (user.getResetRequestedAt() != null && user.getResetRequestedAt().plusMinutes(1).isAfter(now)))
            return null;
        String otp;
        do {
            otp = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        } while (user.getPasswordResetOtpHash() != null && encoder.matches(otp, user.getPasswordResetOtpHash()));
        String hash = encoder.encode(otp);
        user.setPasswordResetOtpHash(hash);
        user.setPasswordResetOtpExpiresAt(now.plusMinutes(5));
        user.setPasswordResetOtpAttempts(0);
        user.setResetRequestedAt(now);
        return new Delivery(user.getUserId(), user.getEmail(), otp, hash);
    }

    private record Delivery(Long userId, String email, String otp, String hash) {}
}
