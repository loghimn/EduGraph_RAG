package fu.coreservice.controller;

import fu.coreservice.dto.auth.RegisterResponse;
import fu.coreservice.entity.ApprovalStatus;
import fu.coreservice.service.AuthService;
import fu.coreservice.service.InstructorApprovalService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InstructorRegistrationControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private InstructorApprovalService service;
    private AuthService auth;
    private static final String BASE = "/api/admin/instructor-registrations";

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "jwt.secret=test-secret-for-local-tests-only", "jwt.access-token.expiration=3600000",
                "jwt.refresh-token.expiration=604800000",
                "app.oauth2.frontend-success-url=http://localhost/auth/callback",
                "app.oauth2.frontend-failure-url=http://localhost/auth/error");
        context.register(TestConfig.class);
        context.refresh();
        service = context.getBean(InstructorApprovalService.class);
        auth = context.getBean(AuthService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void adminCanListApproveAndReject() throws Exception {
        mvc.perform(as(get(BASE), "ADMIN")).andExpect(status().isOk());
        mvc.perform(as(post(BASE + "/1/approve"), "ADMIN")).andExpect(status().isOk());
        mvc.perform(as(post(BASE + "/2/reject"), "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing information\"}"))
                .andExpect(status().isOk());
        verify(service).list(ApprovalStatus.PENDING, 0, 20);
        verify(service).review(1L, "admin@test.local", true, null);
        verify(service).review(2L, "admin@test.local", false, "Missing information");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STUDENT", "INSTRUCTOR"})
    void nonAdminCannotListOrReview(String role) throws Exception {
        mvc.perform(as(get(BASE), role)).andExpect(status().isForbidden());
        mvc.perform(as(post(BASE + "/1/approve"), role)).andExpect(status().isForbidden());
        mvc.perform(as(post(BASE + "/1/reject"), role)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void anonymousCannotReview() throws Exception {
        mvc.perform(get(BASE).servletPath(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/1/approve").servletPath(BASE + "/1/approve")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void adminRegistrationRequestReturns400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"admin@test.local","username":"admin","password":"secret","role":"ADMIN"}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test void pendingRegistrationIsSuccessfulWithNoTokens() throws Exception {
        when(auth.register(any())).thenReturn(RegisterResponse.builder().approvalStatus(ApprovalStatus.PENDING).build());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"teacher@test.local","username":"teacher","password":"secret","role":"INSTRUCTOR"}
                """)).andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        var principal = org.springframework.security.core.userdetails.User.withUsername("admin@test.local")
                .password("hash").roles(role).build();
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(principal, null,
                        AuthorityUtils.createAuthorityList("ROLE_" + role))));
        return request.session(session);
    }

    @Configuration
    @Import({AdminControllerTest.TestConfig.class, AuthController.class})
    static class TestConfig {
        @Bean AuthService auth() { return mock(AuthService.class); }
    }
}
