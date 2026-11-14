package fu.coreservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.security.CustomUserDetailsService;
import fu.coreservice.security.JwtService;
import fu.coreservice.dto.auth.*;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import fu.coreservice.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationApprovalTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserSessionRepository sessions = mock(UserSessionRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final CustomUserDetailsService details = new CustomUserDetailsService(users);
    private final AuthServiceImpl service = new AuthServiceImpl(users, sessions, encoder, jwt, manager,
            details, mock(ActivityAlertService.class));

    @BeforeEach void setup() {
        when(encoder.encode(any())).thenReturn("hash");
        when(users.save(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            user.setUserId(1L);
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
            return user;
        });
        when(jwt.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken(any(), any(), any())).thenReturn("refresh");
        when(jwt.getAccessTokenExpiration()).thenReturn(3600000L);
        when(jwt.getRefreshTokenExpiration()).thenReturn(86400000L);
    }

    @Test void publicRequestCannotDeserializeAdminRole() {
        assertThatThrownBy(() -> new ObjectMapper().readValue("""
                {"email":"admin@test.local","username":"admin","password":"secret","role":"ADMIN"}
                """, RegisterRequest.class)).isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    @Test void studentReceivesTokensAndSessionImmediately() {
        var result = service.register(new RegisterRequest("student@test.local", "student", "secret", RegistrationRole.STUDENT));
        assertThat(result.getApprovalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(result.getAccessToken()).isEqualTo("access");
        assertThat(result.getRefreshToken()).isEqualTo("refresh");
        assertThat(result.getTokenType()).isEqualTo("Bearer");
        assertThat(result.getExpiresIn()).isEqualTo(3600);
        verify(users).save(argThat(u -> u.getRole() == UserRole.STUDENT && Boolean.TRUE.equals(u.getIsActive())));
        verify(sessions).save(any());
    }

    @Test void instructorRegistersWithoutTokensOrSession() {
        var result = service.register(new RegisterRequest("teacher@test.local", "teacher", "secret", RegistrationRole.INSTRUCTOR));
        assertThat(result.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(result.getAccessToken()).isNull();
        assertThat(result.getRefreshToken()).isNull();
        verify(users).save(argThat(u -> u.getRole() == UserRole.INSTRUCTOR && u.getApprovalStatus() == ApprovalStatus.PENDING));
        verifyNoInteractions(jwt, sessions, manager);
    }

    @Test void duplicateEmailStillRejected() {
        when(users.existsByEmail("teacher@test.local")).thenReturn(true);
        assertError(() -> service.register(new RegisterRequest("teacher@test.local", "teacher", "secret", RegistrationRole.INSTRUCTOR)),
                ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(users, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalStatus.class, names = {"PENDING", "REJECTED", "NOT_REQUIRED"})
    void unapprovedInstructorCannotLoginOrUseUserDetails(ApprovalStatus status) {
        User user = instructor(status);
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(manager.authenticate(any())).thenThrow(new DisabledException("disabled"));
        assertError(() -> service.login(new LoginRequest(user.getEmail(), "secret"), new MockHttpServletRequest()),
                status == ApprovalStatus.REJECTED ? ErrorCode.INSTRUCTOR_APPROVAL_REJECTED : ErrorCode.INSTRUCTOR_APPROVAL_PENDING);
        assertThat(details.loadUserByUsername(user.getEmail()).isEnabled()).isFalse();
        verifyNoInteractions(jwt, sessions);
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalStatus.class, names = {"PENDING", "REJECTED"})
    void unapprovedInstructorCannotRefreshExistingSession(ApprovalStatus status) {
        User user = instructor(status);
        when(sessions.findEmailByRefreshToken("old")).thenReturn(Optional.of(user.getEmail()));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(sessions.findByRefreshToken("old")).thenReturn(Optional.of(UserSession.builder()
                .user(user).expiresAt(LocalDateTime.now().plusDays(1)).build()));
        assertError(() -> service.refreshAccessToken(new RefreshTokenRequest("old")),
                status == ApprovalStatus.REJECTED ? ErrorCode.INSTRUCTOR_APPROVAL_REJECTED : ErrorCode.INSTRUCTOR_APPROVAL_PENDING);
        verifyNoInteractions(jwt);
        verify(sessions, never()).save(any());
    }

    @Test void approvedInstructorCanLogin() {
        User user = instructor(ApprovalStatus.APPROVED);
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        var result = service.login(new LoginRequest(user.getEmail(), "secret"), new MockHttpServletRequest());
        assertThat(result.getAccessToken()).isEqualTo("access");
        assertThat(details.loadUserByUsername(user.getEmail()).isEnabled()).isTrue();
        verify(sessions).save(any());
    }

    @Test void approvalDoesNotOverrideAccountDisabled() {
        User user = instructor(ApprovalStatus.APPROVED);
        user.setIsActive(false);
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(manager.authenticate(any())).thenThrow(new DisabledException("disabled"));
        assertError(() -> service.login(new LoginRequest(user.getEmail(), "secret"), new MockHttpServletRequest()),
                ErrorCode.ACCOUNT_DISABLED);
        verifyNoInteractions(jwt, sessions);
    }

    private User instructor(ApprovalStatus status) {
        return User.builder().userId(1L).email("teacher@test.local").username("teacher")
                .passwordHash("hash").role(UserRole.INSTRUCTOR).approvalStatus(status).build();
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode error) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(error));
    }
}
