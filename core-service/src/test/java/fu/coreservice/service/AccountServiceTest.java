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
import org.springframework.mail.MailSendException;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock UserRepository users;
    @Mock UserSessionRepository sessions;
    @Mock PasswordResetMailer mailer;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    AccountService service;
    User user;

    @BeforeEach void setup() {
        service = new AccountServiceImpl(users, sessions, encoder, mailer);
        user = User.builder().userId(1L).email("student@example.com").username("student")
                .passwordHash(encoder.encode("old-password")).isActive(true).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
    @Test void forgotSendsSixDigitOtpButStoresOnlyBcryptHash() {
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        service.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(user.getEmail()), token.capture());
        assertThat(token.getValue()).matches("[0-9]{6}");
        assertThat(encoder.matches(token.getValue(), user.getPasswordResetOtpHash())).isTrue();
        assertThat(user.getPasswordResetOtpHash()).isNotEqualTo(token.getValue());
        assertThat(user.getPasswordResetOtpExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(4));
        service.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        verifyNoMoreInteractions(mailer);
    }
    @Test void unknownEmailDoesNotSend() {
        when(users.findLockedByEmail("unknown@example.com")).thenReturn(Optional.empty());
        service.forgotPassword(new ForgotPasswordRequest("unknown@example.com"));
        verifyNoInteractions(mailer, sessions);
    }
    @Test void newRequestReplacesOldOtpAfterCooldown() {
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        user.setPasswordResetOtpHash("old-token-hash");
        user.setResetRequestedAt(LocalDateTime.now().minusMinutes(2));
        service.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        assertThat(user.getPasswordResetOtpHash()).isNotEqualTo("old-token-hash");
    }
    @Test void mailFailureDoesNotExposeAccountAndInvalidatesOtp() {
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        doThrow(new MailSendException("provider failure")).when(mailer).send(anyString(), anyString());
        assertThatCode(() -> service.forgotPassword(new ForgotPasswordRequest(user.getEmail())))
                .doesNotThrowAnyException();
        assertThat(user.getPasswordResetOtpHash()).isNull();
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
    @Test void inactiveAccountCannotReset() {
        user.setIsActive(false);
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        service.forgotPassword(new ForgotPasswordRequest(user.getEmail()));
        verifyNoInteractions(mailer);
    }
}
