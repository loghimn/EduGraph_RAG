package fu.coreservice.service;

import fu.coreservice.config.ActivityAlertProperties;
import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;
import fu.coreservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayDeque;

/** Only security, destructive operations, privileged changes and operational alerts enter activity_logs. */
@Service
@Slf4j
public class ActivityAlertService {
    private final ActivityLogService logs;
    private final UserRepository users;
    private final ActivityAlertProperties properties;
    private final Clock clock;
    private final Map<String, Window> failures = new LinkedHashMap<>(16, .75f, true);

    @Autowired
    public ActivityAlertService(ActivityLogService logs, UserRepository users, ActivityAlertProperties properties) {
        this(logs, users, properties, Clock.systemUTC());
    }

    public ActivityAlertService(ActivityLogService logs, UserRepository users,
                                ActivityAlertProperties properties, Clock clock) {
        this.logs = logs;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    public void accessDenied(String resourceType) {
        publish(null, ActivityActorType.USER, "ACCESS_DENIED", resourceType, ActivityLogStatus.FAILED,
                "ACCESS_DENIED", "An operation was rejected by an authorization check", false);
    }

    public void loginFailed(String email) {
        String key = loginKey(email);
        if (thresholdReached(key, properties.getLoginFailureThreshold(), properties.getLoginWindowSeconds())) {
            // The attempted account is not proof of the attacker's identity.
            publish(null, ActivityActorType.SYSTEM, "LOGIN_FAILURE_LIMIT", "USER", ActivityLogStatus.FAILED,
                    "LOGIN_FAILURE_LIMIT", "Repeated failed login attempts reached the configured limit", false);
        }
    }

    public synchronized void loginSucceeded(String email) { failures.remove(loginKey(email)); }

    public void otpLimitReached(Long userId) {
        publish(null, ActivityActorType.SYSTEM, "OTP_FAILURE_LIMIT", "USER", ActivityLogStatus.FAILED,
                "OTP_FAILURE_LIMIT", "OTP verification reached the failed-attempt limit for account " + userId, true);
    }

    public void destructiveAfterCommit(Long actorId, String action, String resourceType) {
        publish(actorId, ActivityActorType.USER, action, resourceType, ActivityLogStatus.SUCCESS,
                null, "A destructive operation committed", true);
    }

    public void privilegedChange(String action, String resourceType) {
        publish(null, ActivityActorType.USER, action, resourceType, ActivityLogStatus.SUCCESS,
                null, "A privileged account or system configuration change committed", true);
    }

    public void critical(String action, String resourceType, String errorCode) {
        publish(null, ActivityActorType.SYSTEM, action, resourceType, ActivityLogStatus.FAILED,
                errorCode, "A critical operation failed or left data inconsistent", false);
    }

    public void repeatedSystemFailure(String operation, String resourceType) {
        afterTransaction(true, () -> {
            if (thresholdReached("system:" + operation, properties.getSystemFailureThreshold(),
                    properties.getSystemWindowSeconds())) {
                write(null, ActivityActorType.SYSTEM, "SYSTEM_FAILURE_LIMIT", resourceType, ActivityLogStatus.FAILED,
                        "SYSTEM_FAILURE_LIMIT", "Repeated failures in " + operation + " reached the configured limit");
            }
        });
    }

    private void publish(Long actorId, ActivityActorType actorType, String action, String resourceType,
                         ActivityLogStatus status, String code, String message, boolean committedOnly) {
        afterTransaction(committedOnly, () -> write(actorId, actorType, action, resourceType, status, code, message));
    }

    private void write(Long actorId, ActivityActorType actorType, String action, String resourceType,
                       ActivityLogStatus status, String code, String message) {
        try {
            if (actorId == null && actorType == ActivityActorType.USER) actorId = currentActor();
            logs.log(new ActivityLogRequest(actorId, actorType, action, status, resourceType, code, message, null));
        } catch (RuntimeException ex) {
            log.warn("Unable to persist activity alert (exceptionType={})", ex.getClass().getSimpleName());
        }
    }

    private Long currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        try { return users.findByEmail(auth.getName()).map(user -> user.getUserId()).orElse(null); }
        catch (RuntimeException ignored) { return null; }
    }

    private void afterTransaction(boolean committedOnly, Runnable write) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (!committedOnly || status == STATUS_COMMITTED) safely(write);
                    }
                });
            } else safely(write);
        } catch (RuntimeException ex) {
            log.warn("Unable to schedule activity alert (exceptionType={})", ex.getClass().getSimpleName());
        }
    }

    private void safely(Runnable work) {
        try { work.run(); }
        catch (RuntimeException ex) {
            log.warn("Unable to record activity alert (exceptionType={})", ex.getClass().getSimpleName());
        }
    }

    private synchronized boolean thresholdReached(String key, int limit, int seconds) {
        long now = clock.millis();
        failures.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Window window = failures.get(key);
        if (window == null) {
            if (failures.size() >= Math.max(1, properties.getMaxTrackedSubjects())) {
                failures.remove(failures.keySet().iterator().next());
            }
            window = new Window();
            failures.put(key, window);
        }
        long period = Math.max(1, seconds) * 1000L;
        int threshold = Math.max(2, limit);
        window.expiresAt = now + period;
        while (!window.attempts.isEmpty() && window.attempts.getFirst() <= now - period) window.attempts.removeFirst();
        window.attempts.addLast(now);
        while (window.attempts.size() > threshold) window.attempts.removeFirst();
        if (window.attempts.size() >= threshold && now >= window.nextAlertAt) {
            window.nextAlertAt = now + period;
            return true;
        }
        return false;
    }

    private String loginKey(String email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (email == null ? "" : email.strip().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return "login:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static final class Window {
        private final ArrayDeque<Long> attempts = new ArrayDeque<>();
        private long expiresAt;
        private long nextAlertAt = Long.MIN_VALUE;
    }
}
