package fu.coreservice.security;

import fu.coreservice.security.OAuth2LoginFailureHandler;

import fu.coreservice.security.OAuth2LoginSuccessHandler;

import fu.coreservice.security.CustomUserDetailsService;

import fu.coreservice.security.JwtService;

import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuth2ApprovalTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserSessionRepository sessions = mock(UserSessionRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final OAuth2LoginFailureHandler failure = mock(OAuth2LoginFailureHandler.class);
    private final OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(users, sessions, jwt,
            new CustomUserDetailsService(users), mock(PasswordEncoder.class), failure);

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(value = ApprovalStatus.class, names = {"PENDING", "REJECTED"})
    void googleCannotBypassInstructorApproval(ApprovalStatus status) throws Exception {
        checkBlocked(User.builder().email("teacher@test.local").role(UserRole.INSTRUCTOR).approvalStatus(status).build());
    }

    @Test void googleCannotBypassDisabledAccount() throws Exception {
        checkBlocked(User.builder().email("teacher@test.local").role(UserRole.INSTRUCTOR)
                .approvalStatus(ApprovalStatus.APPROVED).isActive(false).build());
    }

    @Test void approvedGoogleInstructorReceivesTokens() throws Exception {
        var user = User.builder().userId(1L).email("teacher@test.local").passwordHash("hash")
                .role(UserRole.INSTRUCTOR).approvalStatus(ApprovalStatus.APPROVED).build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwt.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken(any(), any(), any())).thenReturn("refresh");
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "http://localhost/auth/callback");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication());
        assertThat(response.getRedirectedUrl()).contains("accessToken=access", "refreshToken=refresh");
        verify(sessions).save(any());
        verifyNoInteractions(failure);
    }

    private void checkBlocked(User user) throws Exception {
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        var request = new MockHttpServletRequest();
        var session = request.getSession();
        var response = new MockHttpServletResponse();
        var authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
        assertThat(session).isNotNull();
        verify(failure).onAuthenticationFailure(eq(request), eq(response), any());
        verifyNoInteractions(jwt, sessions);
    }

    private OAuth2AuthenticationToken authentication() {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_USER");
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,
                Map.of("email", "teacher@test.local", "name", "Teacher"), "email"), authorities, "google");
    }
}
