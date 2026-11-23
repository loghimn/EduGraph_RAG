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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
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
    private final MockEnvironment environment = new MockEnvironment();
    private OAuth2LoginSuccessHandler handler = newHandler();

    private OAuth2LoginSuccessHandler newHandler() {
        return new OAuth2LoginSuccessHandler(users, sessions, jwt,
                new CustomUserDetailsService(users), mock(PasswordEncoder.class), failure, environment);
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"default", "prod"})
    void approvedGoogleInstructorReceivesTokens(String profile) throws Exception {
        environment.setActiveProfiles(profile);
        stubApprovedUser();
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "http://localhost/auth/callback");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication());
        assertThat(response.getRedirectedUrl()).contains("accessToken=access", "refreshToken=refresh");
        verify(sessions).save(any());
        verifyNoInteractions(failure);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"flase", "yes", "false", "true", ""})
    void normalGoogleLoginIsIndependentOfSwaggerSetting(String swaggerEnabled) throws Exception {
        environment.setActiveProfiles("dev");
        environment.setProperty("springdoc.api-docs.enabled", "false");
        if (swaggerEnabled != null) { environment.setProperty("springdoc.swagger-ui.enabled", swaggerEnabled); }
        stubApprovedUser();
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "https://edugraph-rag.io.vn/auth/callback");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "https://edugraph-rag.io.vn/auth/callback?accessToken=access&refreshToken=refresh&tokenType=Bearer");
        verify(sessions).save(any());
        verifyNoInteractions(failure);
    }

    @ParameterizedTest
    @CsvSource({"prod,false", "prod,true", "prod,yes", "prod,", "'prod,dev',true", "dev,false", "dev,yes", "dev,on", "dev,1", "dev,''"})
    void disabledSwaggerFlowIgnoresStaleSessionRedirect(String profile, String swaggerEnabled) throws Exception {
        environment.setActiveProfiles(profile.split(","));
        if (swaggerEnabled != null) { environment.setProperty("springdoc.swagger-ui.enabled", swaggerEnabled); }
        stubApprovedUser();
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "https://edugraph-rag.io.vn/auth/callback");
        var request = swaggerRequest();
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, authentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "https://edugraph-rag.io.vn/auth/callback?accessToken=access&refreshToken=refresh&tokenType=Bearer");
        assertSwaggerStateCleared(request);
        verify(sessions).save(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"true", "TRUE"})
    void developmentSwaggerFlowUsesFixedCallbackAndEncodesState(String swaggerEnabled) throws Exception {
        if (swaggerEnabled != null) { environment.setProperty("springdoc.swagger-ui.enabled", swaggerEnabled); }
        stubApprovedUser();
        var request = swaggerRequest("original&access_token=attacker#fragment");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, authentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "/swagger-ui/oauth2-redirect.html#access_token=access&token_type=Bearer&expires_in=0"
                        + "&state=original%26access_token%3Dattacker%23fragment");
        assertSwaggerStateCleared(request);
    }

    @Test
    void swaggerStateQuestionMarkSurvivesCallbackParsing() throws Exception {
        stubApprovedUser();
        var request = swaggerRequest("a?b");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, authentication());
        String redirect = response.getRedirectedUrl();
        assertThat(redirect).endsWith("&state=a%3Fb");
        // Swagger replaces the first question mark before splitting fragment fields.
        String fragment = java.net.URI.create(redirect).getRawFragment().replaceFirst("\\?", "&");
        String stateField = java.util.Arrays.stream(fragment.split("&"))
                .filter(field -> field.startsWith("state=")).findFirst().orElseThrow();
        assertThat(java.net.URLDecoder.decode(stateField.substring("state=".length()),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a?b");
    }

    @Test
    void changingPropertyAfterStartupCannotEnableAnUnregisteredSwaggerFlow() throws Exception {
        environment.setProperty("springdoc.swagger-ui.enabled", "yes");
        stubApprovedUser();
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "https://edugraph-rag.io.vn/auth/callback");
        environment.setProperty("springdoc.swagger-ui.enabled", "true");
        var request = swaggerRequest();
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, authentication());
        assertThat(response.getRedirectedUrl()).startsWith("https://edugraph-rag.io.vn/auth/callback?");
        assertSwaggerStateCleared(request);
    }

    private MockHttpServletRequest swaggerRequest() {
        return swaggerRequest("state");
    }

    private MockHttpServletRequest swaggerRequest(String state) {
        var request = new MockHttpServletRequest();
        var authorization = OAuth2AuthorizationRequest.authorizationCode()
                .clientId("test").authorizationUri("https://accounts.example/authorize")
                .redirectUri("http://localhost/login/oauth2/code/google").state("google-state")
                .attributes(attributes -> {
                    attributes.put(LoginAuthorizationRequestRepository.SWAGGER_MODE, true);
                    attributes.put(LoginAuthorizationRequestRepository.SWAGGER_STATE, state);
                }).build();
        request.setAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST, authorization);
        // Old session flags are ignored and removed even for a matching callback.
        request.getSession().setAttribute("fromSwagger", true);
        request.getSession().setAttribute("swagger_state", "state");
        request.getSession().setAttribute("swagger_redirect_uri", "https://attacker.example/collect");
        return request;
    }

    private void assertSwaggerStateCleared(MockHttpServletRequest request) {
        assertThat(request.getAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST)).isNull();
        assertThat(request.getSession().getAttribute("fromSwagger")).isNull();
        assertThat(request.getSession().getAttribute("swagger_state")).isNull();
        assertThat(request.getSession().getAttribute("swagger_redirect_uri")).isNull();
    }

    @Test
    void legacySwaggerSessionMarkersCannotRedirectNormalLogin() throws Exception {
        stubApprovedUser();
        ReflectionTestUtils.setField(handler, "frontendSuccessUrl", "https://edugraph-rag.io.vn/auth/callback");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("fromSwagger", true);
        request.getSession().setAttribute("swagger_state", "abandoned-popup");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, authentication());
        assertThat(response.getRedirectedUrl()).startsWith("https://edugraph-rag.io.vn/auth/callback?");
        assertSwaggerStateCleared(request);
    }

    private void stubApprovedUser() {
        handler = newHandler();
        var user = User.builder().userId(1L).email("teacher@test.local").passwordHash("hash")
                .role(UserRole.INSTRUCTOR).approvalStatus(ApprovalStatus.APPROVED).build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwt.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken(any(), any(), any())).thenReturn("refresh");
    }

    private void checkBlocked(User user) throws Exception {
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
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
