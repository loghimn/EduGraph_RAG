package fu.coreservice.service;

import fu.coreservice.service.impl.ActivityAlertServiceImpl;

import fu.coreservice.config.ActivityAlertProperties;
import fu.coreservice.security.JwtService;
import fu.coreservice.dto.auth.LoginRequest;
import fu.coreservice.entity.User;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import fu.coreservice.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthLoginAlertTest {
    @Test void failedCredentialsReachThresholdWithoutLoggingEachAttempt() {
        var users = mock(UserRepository.class);
        when(users.findLockedByEmail("student@test.local")).thenReturn(Optional.of(User.builder().userId(7L).build()));
        var authentication = mock(AuthenticationManager.class);
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("private detail"));
        var sink = mock(ActivityLogService.class);
        var alerts = new ActivityAlertServiceImpl(sink, users, new ActivityAlertProperties());
        var auth = new AuthServiceImpl(users, mock(UserSessionRepository.class), mock(PasswordEncoder.class),
                mock(JwtService.class), authentication, mock(UserDetailsService.class), alerts);
        var request = new LoginRequest("student@test.local", "private-password");
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> auth.login(request, new MockHttpServletRequest()))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(sink);
        assertThatThrownBy(() -> auth.login(request, new MockHttpServletRequest()))
                .isInstanceOf(BadCredentialsException.class);
        verify(sink).log(argThat(entry -> entry.action().equals("LOGIN_FAILURE_LIMIT")
                && !entry.message().contains("private-password") && !entry.message().contains("private detail")));
    }
}
