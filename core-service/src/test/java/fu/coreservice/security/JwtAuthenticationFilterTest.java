package fu.coreservice.security;

import fu.coreservice.security.JwtAuthenticationFilter;

import fu.coreservice.security.JwtService;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {
    private JwtService jwtService;
    private UserDetailsService users;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private OAuth2AuthenticationToken sessionAuth;

    @BeforeEach void setup() {
        jwtService = mock(JwtService.class);
        users = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtService, users);
        chain = mock(FilterChain.class);
        request = new MockHttpServletRequest("GET", "/api/courses/my");
        request.setServletPath("/api/courses/my");
        response = new MockHttpServletResponse();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR"));
        var principal = new DefaultOAuth2User(authorities, Map.of("email", "session@example.com"), "email");
        sessionAuth = new OAuth2AuthenticationToken(principal, authorities, "google");
        SecurityContextHolder.getContext().setAuthentication(sessionAuth);
        var saved = new SessionUserDetails("session@example.com", "hash", true, authorities, 0);
        request.getSession().setAttribute(SessionUserDetails.OAUTH2_SESSION_ACCOUNT, saved);
        when(users.loadUserByUsername("session@example.com")).thenReturn(saved);
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void noBearerPreservesOAuth2SessionAuthentication() throws Exception {
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(sessionAuth);
        verifyNoInteractions(jwtService);
        verify(chain).doFilter(request, response);
    }

    @Test void nonBearerHeaderPreservesOAuth2SessionAuthentication() throws Exception {
        request.addHeader("Authorization", "Basic ignored");
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(sessionAuth);
        verifyNoInteractions(jwtService);
    }

    @Test void validBearerReplacesOAuth2Authentication() throws Exception {
        request.addHeader("Authorization", "Bearer valid");
        var user = User.withUsername("jwt@example.com").password("hash").roles("STUDENT").build();
        when(jwtService.extractUsername("valid")).thenReturn(user.getUsername());
        when(users.loadUserByUsername(user.getUsername())).thenReturn(user);
        when(jwtService.isTokenValid("valid", user)).thenReturn(true);
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
        verify(chain).doFilter(request, response);
    }

    @Test void invalidBearerDoesNotFallBackToOAuth2Session() throws Exception {
        request.addHeader("Authorization", "Bearer invalid");
        // No subject is a rejected token, even if an authenticated session exists.
        when(jwtService.extractUsername("invalid")).thenReturn(null);
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test void revokedCookieSessionCannotAuthenticateWithoutBearer() throws Exception {
        var session = request.getSession();
        when(users.loadUserByUsername("session@example.com")).thenReturn(new SessionUserDetails(
                "session@example.com", "hash", true, sessionAuth.getAuthorities(), 1));
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isTrue();
        verify(chain).doFilter(request, response);
    }

    @Test void legacyOAuthCookieWithoutAccountSnapshotIsRejected() throws Exception {
        request.getSession().removeAttribute(SessionUserDetails.OAUTH2_SESSION_ACCOUNT);
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void cookieSessionsCannotBypassPasswordRoleOrAccountChanges() throws Exception {
        for (var current : List.of(
                new SessionUserDetails("session@example.com", "changed", true, sessionAuth.getAuthorities(), 0),
                new SessionUserDetails("session@example.com", "hash", false, sessionAuth.getAuthorities(), 0),
                new SessionUserDetails("session@example.com", "hash", true, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")), 0))) {
            var req = new MockHttpServletRequest("GET", "/api/courses/my"); req.setServletPath("/api/courses/my");
            req.getSession().setAttribute(SessionUserDetails.OAUTH2_SESSION_ACCOUNT,
                    new SessionUserDetails("session@example.com", "hash", true, sessionAuth.getAuthorities(), 0));
            SecurityContextHolder.getContext().setAuthentication(sessionAuth);
            when(users.loadUserByUsername("session@example.com")).thenReturn(current);
            filter.doFilter(req, new MockHttpServletResponse(), chain);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test void nonApiOAuthFlowDoesNotRequireAnAccountSnapshot() throws Exception {
        request.setServletPath("/oauth2/authorization/google");
        request.getSession().removeAttribute(SessionUserDetails.OAUTH2_SESSION_ACCOUNT);
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(sessionAuth);
        verifyNoInteractions(jwtService, users);
    }
}
