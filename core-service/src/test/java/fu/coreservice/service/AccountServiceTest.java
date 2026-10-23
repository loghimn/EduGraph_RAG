package fu.coreservice.service;

import fu.coreservice.service.impl.AccountServiceImpl;

import java.util.List;

import fu.coreservice.dto.auth.*;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock UserRepository users;
    @Mock UserSessionRepository sessions;
    @Mock PasswordResetDispatcher dispatcher;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    AccountService service;
    User user;

    @BeforeEach void setup() {
        service = new AccountServiceImpl(users, sessions, encoder, dispatcher);
        user = User.builder().userId(1L).email("student@example.com").username("student")
                .passwordHash(encoder.encode("old-password")).isActive(true).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
    @Test void forgotQueuesEmailWithoutLookingUpAccount() {
        service.forgotPassword(new ForgotPasswordRequest("  student@example.com  "));
        verify(dispatcher).dispatch("student@example.com");
        verifyNoInteractions(users, sessions);
    }
    @Test void unknownEmailUsesSameDispatchPath() {
        service.forgotPassword(new ForgotPasswordRequest("unknown@example.com"));
        verify(dispatcher).dispatch("unknown@example.com");
        verifyNoInteractions(users, sessions);
    }
    @Test void resetUpdatesPasswordClearsOtpAndRevokesRefreshSessions() {
        user.setPasswordResetOtpHash(encoder.encode("012345"));
        user.setPasswordResetOtpExpiresAt(LocalDateTime.now().plusMinutes(15));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        service.resetPassword(new ResetPasswordRequest(user.getEmail(), "012345", "new-password"));
        assertThat(encoder.matches("new-password", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordResetOtpHash()).isNull();
        assertThat(user.getPasswordResetOtpExpiresAt()).isNull();
        verify(sessions).deleteAllByUser(user);
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(user.getEmail(), "012345", "another-password")))
                .isInstanceOf(AppException.class);
    }
    @Test void expiredOtpCannotChangePassword() {
        user.setPasswordResetOtpExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(user.getEmail(), "012345", "new-password")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(sessions);
        assertThat(encoder.matches("old-password", user.getPasswordHash())).isTrue();
    }
    @Test void wrongCurrentPasswordCannotChangePassword() {
        authenticate();
        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest("wrong", "new-password")))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(sessions);
    }
    @Test void changePasswordRevokesSessionsAndPendingReset() {
        authenticate();
        user.setPasswordResetOtpHash("pending");
        service.changePassword(new ChangePasswordRequest("old-password", "new-password"));
        assertThat(encoder.matches("new-password", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordResetOtpHash()).isNull();
        verify(sessions).deleteAllByUser(user);
    }
    @Test void updateRejectsDuplicateUsername() {
        authenticate();
        when(users.existsByUsername("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.update(new UpdateProfileRequest("taken")))
                .isInstanceOf(AppException.class);
        assertThat(user.getUsername()).isEqualTo("student");
    }
    @Test void updateTrimsUsernameAndKeepsEmail() {
        authenticate();
        var response = service.update(new UpdateProfileRequest("  newname  "));
        assertThat(response.getUsername()).isEqualTo("newname");
        assertThat(response.getEmail()).isEqualTo("student@example.com");
    }
}
