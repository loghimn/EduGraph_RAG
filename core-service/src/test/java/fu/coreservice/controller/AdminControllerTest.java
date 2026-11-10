package fu.coreservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.config.*;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.activitylog.ActivityLogFilter;
import fu.coreservice.dto.activitylog.ActivityLogResponse;
import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.exception.GlobalExceptionHandler;
import fu.coreservice.repository.*;
import fu.coreservice.service.ActivityAlertService;
import fu.coreservice.service.ActivityLogService;
import fu.coreservice.service.InstructorApprovalService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ActivityLogService service;
    private static final String BASE = "/api/admin/activity-logs";

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "jwt.secret=test-secret-for-local-tests-only", "jwt.access-token.expiration=3600000",
                "jwt.refresh-token.expiration=604800000",
                "app.oauth2.frontend-success-url=http://localhost/auth/callback",
                "app.oauth2.frontend-failure-url=http://localhost/auth/error");
        context.register(TestConfig.class);
        context.refresh();
        service = context.getBean(ActivityLogService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void adminCanReadListWithDefaultsAndNullableSystemActor() throws Exception {
        when(service.listLogs(any())).thenReturn(PageResponse.<ActivityLogResponse>builder()
                .content(List.of(entry())).page(0).size(20).totalElements(1).totalPages(1).first(true).last(true).build());
        mvc.perform(request(BASE, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].logId").value(7))
                .andExpect(jsonPath("$.data.content[0].actorType").value("SYSTEM"))
                .andExpect(jsonPath("$.data.content[0].createdAt").isString())
                .andExpect(jsonPath("$.data.content[0].actor").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist());
        verify(service).listLogs(new ActivityLogFilter(0, 20, null, null, null, null, null, null));
        verify(service, never()).log(any());
    }

    @Test
    void adminCanReadDetailAndMissingLogIs404() throws Exception {
        when(service.getLog(7L)).thenReturn(entry());
        when(service.getLog(999L)).thenThrow(new AppException(ErrorCode.ACTIVITY_LOG_NOT_FOUND));
        mvc.perform(request(BASE + "/7", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("SYSTEM_FAILURE_LIMIT"))
                .andExpect(jsonPath("$.data.actorId").isEmpty())
                .andExpect(jsonPath("$.data.actorUsername").isEmpty())
                .andExpect(jsonPath("$.data.passwordResetOtpHash").doesNotExist());
        mvc.perform(request(BASE + "/999", "ADMIN"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ACTIVITY_LOG_001"));
    }

    @Test
    void bindsEveryFilterIncludingIsoDates() throws Exception {
        mvc.perform(request(BASE, "ADMIN").param("page", "2").param("size", "10")
                        .param("from", "2026-11-10T00:00:00").param("to", "2026-11-11T00:00:00")
                        .param("action", "ACCESS_DENIED").param("status", "FAILED")
                        .param("actorType", "USER").param("actorId", "5"))
                .andExpect(status().isOk());
        verify(service).listLogs(new ActivityLogFilter(2, 10, LocalDateTime.of(2026, 11, 10, 0, 0),
                LocalDateTime.of(2026, 11, 11, 0, 0), "ACCESS_DENIED", ActivityLogStatus.FAILED, ActivityActorType.USER, 5L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STUDENT", "INSTRUCTOR"})
    void nonAdminIsRejectedBeforeAnyRead(String role) throws Exception {
        mvc.perform(request(BASE, role)).andExpect(status().isForbidden());
        mvc.perform(request(BASE + "/7", role)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void anonymousIs401ForBothEndpoints() throws Exception {
        mvc.perform(get(BASE).servletPath(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/7").servletPath(BASE + "/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void controllerMethodSecurityAlsoPreventsDirectNonAdminInvocation() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "student@test.local", null, AuthorityUtils.createAuthorityList("ROLE_STUDENT")));
        assertThatThrownBy(() -> context.getBean(AdminController.class).get(7L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "actorType", "from", "page", "actorId"})
    void invalidQueryTypesAre400BeforeService(String parameter) throws Exception {
        mvc.perform(request(BASE, "ADMIN").param(parameter, "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("COMMON_002"));
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder request(String path, String role) {
        var session = new MockHttpSession(context.getServletContext());
        var authentication = new UsernamePasswordAuthenticationToken("admin@test.local", null,
                AuthorityUtils.createAuthorityList("ROLE_" + role));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return get(path).servletPath(path).session(session);
    }

    private ActivityLogResponse entry() {
        return new ActivityLogResponse(7L, LocalDateTime.of(2026, 11, 10, 12, 30), null, null,
                ActivityActorType.SYSTEM, "SYSTEM_FAILURE_LIMIT", "CHAT_MESSAGE", ActivityLogStatus.FAILED,
                "SYSTEM_FAILURE_LIMIT", "Repeated failures in CHAT_GENERATE reached the configured limit", null);
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, AdminController.class, JacksonConfig.class})
    static class TestConfig {
        @Bean ActivityLogService activityLogService() { return mock(ActivityLogService.class); }
        @Bean InstructorApprovalService instructorApprovalService() { return mock(InstructorApprovalService.class); }
        @Bean ActivityAlertService activityAlertService() { return mock(ActivityAlertService.class); }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean UserDetailsService userDetailsService() { return mock(UserDetailsService.class); }
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwt, UserDetailsService users) {
            return new JwtAuthenticationFilter(jwt, users);
        }
        @Bean OAuth2LoginSuccessHandler successHandler() { return mock(OAuth2LoginSuccessHandler.class); }
        @Bean OAuth2LoginFailureHandler failureHandler() { return mock(OAuth2LoginFailureHandler.class); }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean ClientRegistrationRepository clientRegistrations() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test").clientSecret("test").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/login/oauth2/code/google").authorizationUri("http://localhost/authorize")
                    .tokenUri("http://localhost/token").userInfoUri("http://localhost/userinfo")
                    .userNameAttributeName("email").scope("email").build());
        }
        @Bean GlobalExceptionHandler exceptionHandler(ActivityAlertService alerts) {
            return new GlobalExceptionHandler(alerts, mock(ChatSessionRepository.class),
                    mock(LearningPathPlanRepository.class), mock(ChatMessageRepository.class));
        }
    }
}
