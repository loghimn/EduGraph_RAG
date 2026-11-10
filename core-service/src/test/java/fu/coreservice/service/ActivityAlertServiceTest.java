package fu.coreservice.service;

import fu.coreservice.config.ActivityAlertProperties;
import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.entity.ActivityLogStatus;
import fu.coreservice.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityAlertServiceTest {
    private final ActivityLogService sink = mock(ActivityLogService.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final ActivityAlertService alerts = new ActivityAlertService(sink, mock(UserRepository.class),
            new ActivityAlertProperties(), clock);

    @Test
    void singleCredentialFailureAndNormalSuccessProduceNoLogs() {
        alerts.loginFailed("student@test.local");
        alerts.loginSucceeded("student@test.local");
        verifyNoInteractions(sink);
    }

    @Test
    void fifthFailureAlertsOnceWithNormalizedAccountAndNoCredentials() {
        for (int i = 0; i < 4; i++) alerts.loginFailed("Student@Test.local");
        verifyNoInteractions(sink);
        alerts.loginFailed(" student@test.local ");
        for (int i = 0; i < 10; i++) alerts.loginFailed("student@test.local");
        verify(sink).log(argThat(entry -> entry.action().equals("LOGIN_FAILURE_LIMIT")
                && entry.actorId() == null && !entry.message().contains("student@test.local")));
        verifyNoMoreInteractions(sink);
    }

    @Test
    void successfulLoginResetsCountAndExpiredWindowStartsFresh() {
        for (int i = 0; i < 4; i++) alerts.loginFailed("student@test.local");
        alerts.loginSucceeded("student@test.local");
        for (int i = 0; i < 4; i++) alerts.loginFailed("student@test.local");
        now.addAndGet(901_000);
        alerts.loginFailed("student@test.local");
        verifyNoInteractions(sink);
        for (int i = 0; i < 4; i++) alerts.loginFailed("student@test.local");
        verify(sink).log(any(ActivityLogRequest.class));
    }

    @Test
    void repeatedSystemFailuresAlertOnlyAtThresholdAndOncePerWindow() {
        alerts.repeatedSystemFailure("CHAT_GENERATE", "CHAT_MESSAGE");
        alerts.repeatedSystemFailure("CHAT_GENERATE", "CHAT_MESSAGE");
        verifyNoInteractions(sink);
        for (int i = 0; i < 6; i++) alerts.repeatedSystemFailure("CHAT_GENERATE", "CHAT_MESSAGE");
        verify(sink).log(argThat(entry -> entry.action().equals("SYSTEM_FAILURE_LIMIT")
                && entry.message().contains("CHAT_GENERATE") && entry.status() == ActivityLogStatus.FAILED));
        verifyNoMoreInteractions(sink);
    }

    @Test
    void failuresNearWindowBoundaryAreNotLost() {
        alerts.loginFailed("student@test.local");
        now.addAndGet(899_000);
        for (int i = 0; i < 3; i++) alerts.loginFailed("student@test.local");
        now.addAndGet(2_000);
        alerts.loginFailed("student@test.local");
        verifyNoInteractions(sink);
        alerts.loginFailed("student@test.local");
        verify(sink).log(any(ActivityLogRequest.class));
    }

    @Test
    void concurrentFailuresDoNotCreateDuplicateThresholdAlerts() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 30; i++) tasks.add(executor.submit(() -> alerts.loginFailed("same@test.local")));
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(sink).log(any(ActivityLogRequest.class));
        verifyNoMoreInteractions(sink);
    }

    @Test
    void logPersistenceFailureDoesNotEscapeAlertCall() {
        when(sink.log(any())).thenThrow(new IllegalStateException("private database detail"));
        assertThatCode(() -> alerts.accessDenied("COURSE")).doesNotThrowAnyException();
        verify(sink).log(argThat(entry -> !entry.message().contains("private database detail")));
    }
}
