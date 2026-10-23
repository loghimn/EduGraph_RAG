package fu.coreservice.service;

import fu.coreservice.entity.User;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.impl.PasswordResetDispatcherImpl;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordResetDispatcherTest {
    @Test void dispatchDoesNotPerformAccountLookupOrSmtpOnCallerThread() {
        UserRepository users = mock(UserRepository.class);
        PasswordResetMailer mailer = mock(PasswordResetMailer.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        List<Runnable> tasks = new ArrayList<>();
        var dispatcher = new PasswordResetDispatcherImpl(users, new BCryptPasswordEncoder(4), mailer,
                task -> tasks.add(task), transactions);
        dispatcher.dispatch("unknown@example.com");
        dispatcher.dispatch("existing@example.com");
        assertThat(tasks).hasSize(2);
        verifyNoInteractions(users, mailer, transactions);
    }

    @Test void fullQueueDoesNotChangePublicOutcomeOrRunWorkInline() {
        UserRepository users = mock(UserRepository.class);
        PasswordResetMailer mailer = mock(PasswordResetMailer.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TaskExecutor full = task -> { throw new TaskRejectedException("full"); };
        var dispatcher = new PasswordResetDispatcherImpl(users, new BCryptPasswordEncoder(4), mailer,
                full, transactions);
        assertThatCode(() -> dispatcher.dispatch("existing@example.com")).doesNotThrowAnyException();
        verifyNoInteractions(users, mailer, transactions);
    }

    @Test void failedCommitPreventsSendingUncommittedOtp() {
        UserRepository users = mock(UserRepository.class);
        PasswordResetMailer mailer = mock(PasswordResetMailer.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(users.findLockedByEmail("existing@example.com")).thenReturn(Optional.of(
                User.builder().userId(1L).email("existing@example.com").build()));
        doThrow(new IllegalStateException("commit failed")).when(transactions).commit(status);
        var dispatcher = new PasswordResetDispatcherImpl(users, new BCryptPasswordEncoder(4), mailer,
                Runnable::run, transactions);
        assertThatCode(() -> dispatcher.dispatch("existing@example.com")).doesNotThrowAnyException();
        verify(transactions).commit(status);
        verifyNoInteractions(mailer);
    }
}
