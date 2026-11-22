package fu.coreservice.controller;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.ai.CourseAiSettings;
import fu.coreservice.service.CourseAiSettingsService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseAiSettingsControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CourseAiSettingsService service;
    private static final String SETTINGS = "/api/courses/26/ai-settings";
    private static final String BODY = "{\"similarityThreshold\":0.8,\"traversalDepth\":2,\"promptTemplate\":\"Use Java\"}";

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "jwt.secret=test-secret-for-local-tests-only", "jwt.access-token.expiration=3600000", "jwt.refresh-token.expiration=604800000",
                "app.oauth2.frontend-success-url=http://localhost/auth/callback", "app.oauth2.frontend-failure-url=http://localhost/auth/error");
        context.register(TestConfig.class); context.refresh();
        service = context.getBean(CourseAiSettingsService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void adminListsInstructorMetadataAndVisibilityAndReadsSettings() throws Exception {
        when(service.listCourses(0)).thenReturn(PageResponse.<CourseAiSettingsService.CourseOption>builder()
                .content(List.of(new CourseAiSettingsService.CourseOption(26L, "Java", 8L, "Teacher", "teacher@test.local", false)))
                .page(0).size(50).totalElements(1).totalPages(1).first(true).last(true).build());
        when(service.get(26L)).thenReturn(CourseAiSettings.defaults());
        mvc.perform(as(get("/api/teaching-courses"), "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].instructorName").value("Teacher"))
                .andExpect(jsonPath("$.data.content[0].instructorEmail").value("teacher@test.local"))
                .andExpect(jsonPath("$.data.content[0].isPublic").value(false));
        mvc.perform(as(get(SETTINGS), "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.similarityThreshold").value(.35));
    }

    @Test void adminSavesTypedSettings() throws Exception {
        var saved = new CourseAiSettings(.8, 2, "Use Java"); when(service.save(26L, saved)).thenReturn(saved);
        mvc.perform(as(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(BODY), "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.promptTemplate").value("Use Java"));
        verify(service).save(26L, saved);
    }

    @Test void studentsAndInstructorsCannotListReadOrSave() throws Exception {
        for (String role : List.of("STUDENT", "INSTRUCTOR")) {
            mvc.perform(as(get("/api/teaching-courses"), role)).andExpect(status().isForbidden());
            mvc.perform(as(get(SETTINGS), role)).andExpect(status().isForbidden());
            mvc.perform(as(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(BODY), role)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void anonymousRequestsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/teaching-courses").servletPath("/api/teaching-courses")).andExpect(status().isUnauthorized());
        mvc.perform(get(SETTINGS).servletPath(SETTINGS)).andExpect(status().isUnauthorized());
        mvc.perform(put(SETTINGS).servletPath(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void invalidValuesAreRejectedBeforeSaving() throws Exception {
        for (String body : List.of(BODY.replace("0.8", "1.1"), BODY.replace("0.8", "-0.1"),
                BODY.replace(":2", ":7"), BODY.replace(":2", ":-1"), BODY.replace("Use Java", "x".repeat(4001)), "{}")) {
            mvc.perform(as(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(body), "ADMIN")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("admin@test.local", null, AuthorityUtils.createAuthorityList("ROLE_" + role))));
        return request.session(session);
    }
    @Configuration
    @Import({AdminControllerTest.TestConfig.class, CourseAiSettingsController.class, TeachingCoursesController.class})
    static class TestConfig {
        @Bean CourseAiSettingsService courseSettings() { return mock(CourseAiSettingsService.class); }
    }
}
