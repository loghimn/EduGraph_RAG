package fu.coreservice;

import com.fasterxml.jackson.databind.*;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.admin.UpdateAdminUserRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.*;
import fu.coreservice.service.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-context-test.properties", properties = "DB_URL=jdbc:h2:mem:admin-management-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AdminManagementHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired UserSessionRepository sessions;
    @Autowired CourseRepository courses;
    @Autowired DocumentRepository documents;
    @Autowired ActivityLogRepository logs;
    @Autowired AdminUserService service;
    @Autowired PasswordEncoder encoder;
    @MockitoBean AiCoreClient ai;
    @MockitoBean SupabaseStorageService storage;
    MockMvc mvc;
    ObjectMapper json = new ObjectMapper();
    User admin;
    String token;
    static final String PASSWORD = "admin-test-password-123";
    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        admin = seed(UserRole.ADMIN); token = login(admin.getEmail()).path("accessToken").asText();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    User seed(UserRole role) {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().email(id + "@example.invalid").username("User " + id).role(role)
                .approvalStatus(role == UserRole.INSTRUCTOR ? ApprovalStatus.APPROVED : ApprovalStatus.NOT_REQUIRED)
                .passwordHash(encoder.encode(PASSWORD)).build());
    }
    JsonNode send(MockHttpServletRequestBuilder request, String bearer, Object body, int expected) throws Exception {
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    JsonNode login(String email) throws Exception { return send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD), 200).path("data"); }
    JsonNode detail(long id) throws Exception { return send(get("/api/admin/users/" + id), token, null, 200).path("data"); }
    Map<String,Object> update(JsonNode user) {
        var input = new HashMap<String,Object>();
        for (String key : List.of("email", "username", "role")) input.put(key, user.path(key).asText());
        input.put("isActive", user.path("isActive").asBoolean()); input.put("expectedUpdatedAt", user.path("updatedAt").asText());
        input.put("reason", "Integration test account review"); return input;
    }
    @Test void allEndpointsRequireAdminAndNeverExposeCredentials() throws Exception {
        var student = seed(UserRole.STUDENT); var studentToken = login(student.getEmail()).path("accessToken").asText();
        for (String url : List.of("/api/admin/dashboard", "/api/admin/users", "/api/admin/users/" + student.getUserId())) {
            send(get(url), null, null, 401); send(get(url), studentToken, null, 403);
            String body = send(get(url), token, null, 200).toString();
            assertThat(body).doesNotContain("passwordHash", "passwordResetOtp", "refreshToken", PASSWORD);
        }
        send(post("/api/admin/users"), studentToken, Map.of(), 403);
        send(patch("/api/admin/users/" + student.getUserId()), studentToken, update(detail(student.getUserId())), 403);
    }
    @Test void createInstructorUsesPendingApprovalAndExistingReviewFlow() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        JsonNode created = send(post("/api/admin/users"), token, Map.of("email", email, "username", "New instructor " + UUID.randomUUID(), "role", "INSTRUCTOR", "password", PASSWORD), 201).path("data");
        assertThat(created.path("approvalStatus").asText()).isEqualTo("PENDING");
        assertThat(created.toString()).doesNotContain("password", "refreshToken");
        assertThat(encoder.matches(PASSWORD, users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
        send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD), 403);
        send(post("/api/admin/instructor-registrations/" + created.path("userId").asLong() + "/approve"), token, null, 200);
        assertThat(login(email).path("accessToken").asText()).isNotBlank();
    }
    @Test void lockBlocksExistingAccessAndRefreshWhileUnlockRetainsLearningData() throws Exception {
        var student = seed(UserRole.STUDENT); var auth = login(student.getEmail());
        var notebook = send(post("/api/notebooks"), auth.path("accessToken").asText(), Map.of("title", "Retained notebook", "domain", "CS", "language", "vi"), 201).path("data");
        var input = update(detail(student.getUserId())); input.put("isActive", false);
        JsonNode locked = send(patch("/api/admin/users/" + student.getUserId()), token, input, 200).path("data");
        send(get("/api/auth/me"), auth.path("accessToken").asText(), null, 401);
        send(post("/api/auth/refresh"), null, Map.of("refreshToken", auth.path("refreshToken").asText()), 401);
        assertThat(sessions.findByUser(student)).isEmpty(); assertThat(courses.existsById(notebook.path("id").asLong())).isTrue();
        input = update(locked); input.put("isActive", true);
        send(patch("/api/admin/users/" + student.getUserId()), token, input, 200);
        send(get("/api/notebooks/" + notebook.path("id").asLong()), login(student.getEmail()).path("accessToken").asText(), null, 200);
    }
    @Test void staleEditCannotOverwriteProfileOrInstructorApproval() throws Exception {
        var student = seed(UserRole.STUDENT); JsonNode before = detail(student.getUserId());
        var input = update(before); input.put("username", "Updated " + UUID.randomUUID());
        JsonNode changed = send(patch("/api/admin/users/" + student.getUserId()), token, input, 200).path("data");
        assertThat(changed.path("updatedAt").asText()).isNotEqualTo(before.path("updatedAt").asText());
        input = update(before); input.put("role", "ADMIN");
        assertThat(send(patch("/api/admin/users/" + student.getUserId()), token, input, 409).path("errorCode").asText()).isEqualTo("USER_005");
        assertThat(users.findById(student.getUserId()).orElseThrow().getRole()).isEqualTo(UserRole.STUDENT);
        input = update(changed); input.put("role", "INSTRUCTOR");
        JsonNode pending = send(patch("/api/admin/users/" + student.getUserId()), token, input, 200).path("data");
        send(post("/api/admin/instructor-registrations/" + student.getUserId() + "/approve"), token, null, 200);
        var stale = update(pending); stale.put("isActive", false);
        send(patch("/api/admin/users/" + student.getUserId()), token, stale, 409);
        assertThat(users.findById(student.getUserId()).orElseThrow().getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }
    @Test void selfAccessChangesAreRejectedAndAuditDescribesSuccessfulEdits() throws Exception {
        for (String field : List.of("role", "isActive", "email")) {
            var input = update(detail(admin.getUserId()));
            input.put(field, field.equals("role") ? "STUDENT" : field.equals("email") ? "new@example.invalid" : false);
            assertThat(send(patch("/api/admin/users/" + admin.getUserId()), token, input, 409).path("errorCode").asText()).isEqualTo("USER_006");
        }
        var student = seed(UserRole.STUDENT); var input = update(detail(student.getUserId())); input.put("username", "Corrected " + UUID.randomUUID());
        send(patch("/api/admin/users/" + student.getUserId()), token, input, 200);
        var recorded = logs.findAll().stream().filter(log -> log.getAction().equals("ADMIN_USER_UPDATED") && log.getMessage().contains("user #" + student.getUserId())).toList();
        assertThat(recorded).hasSize(1); assertThat(recorded.getFirst().getMessage()).contains("Integration test account review").doesNotContain(PASSWORD);
    }
    @Test void filtersEscapeWildcardsAndValidateBounds() throws Exception {
        var student = seed(UserRole.STUDENT); student.setUsername("Literal%_" + UUID.randomUUID()); student.setIsActive(false); users.saveAndFlush(student);
        JsonNode page = send(get("/api/admin/users").param("keyword", "%_").param("role", "STUDENT").param("active", "false"), token, null, 200).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1); assertThat(page.path("content").get(0).path("userId").asLong()).isEqualTo(student.getUserId());
        assertThat(send(get("/api/admin/users").param("keyword", student.getEmail().toUpperCase()), token, null, 200).path("data").path("totalElements").asLong()).isEqualTo(1);
        for (var parameter : List.of(Map.entry("size", "101"), Map.entry("page", "-1"), Map.entry("keyword", "x".repeat(101)), Map.entry("role", "OWNER")))
            send(get("/api/admin/users").param(parameter.getKey(), parameter.getValue()), token, null, 400);
    }
    @Test void dashboardUsesRealAggregatesAndSeparatesCoursesFromNotebooks() throws Exception {
        var before = send(get("/api/admin/dashboard"), token, null, 200).path("data");
        var teacher = seed(UserRole.INSTRUCTOR);
        Course course = courses.saveAndFlush(Course.builder().user(teacher).courseName("Course").description("test").domain("CS").language("vi").notebook(false).build());
        courses.saveAndFlush(Course.builder().user(teacher).courseName("Notebook").description("test").domain("CS").language("vi").notebook(true).build());
        documents.saveAndFlush(Document.builder().course(course).user(teacher).docStatus(UploadStatus.FAILED).fileName("test.pdf").build());
        var after = send(get("/api/admin/dashboard"), token, null, 200).path("data");
        assertThat(after.path("totalUsers").asLong()).isEqualTo(before.path("totalUsers").asLong() + 1);
        for (String field : List.of("instructors", "courses", "notebooks", "documents", "failedDocuments")) assertThat(after.path(field).asLong()).isEqualTo(before.path(field).asLong() + 1);
        assertThat(after.path("students").asLong() + after.path("instructors").asLong() + after.path("admins").asLong()).isEqualTo(after.path("totalUsers").asLong());
    }
    @Test void concurrentAdminsCannotDisableEachOtherAtTheSameTime() throws Exception {
        User other = seed(UserRole.ADMIN);
        var actorView = detail(admin.getUserId()); var otherView = detail(other.getUserId());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> disable(other, otherView, admin, start);
            Callable<Boolean> second = () -> disable(admin, actorView, other, start);
            var a = executor.submit(first); var b = executor.submit(second); start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        long enabled = List.of(admin.getUserId(), other.getUserId()).stream().map(id -> users.findById(id).orElseThrow()).filter(user -> Boolean.TRUE.equals(user.getIsActive())).count();
        assertThat(enabled).isEqualTo(1);
    }
    boolean disable(User target, JsonNode view, User actor, CountDownLatch start) throws Exception {
        start.await(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getEmail(), null, java.util.List.of()));
        try {
            service.update(target.getUserId(), new UpdateAdminUserRequest(target.getEmail(), target.getUsername(), target.getRole(), false,
                    java.time.LocalDateTime.parse(view.path("updatedAt").asText()), "Concurrent access review")); return true;
        } catch (AppException denied) { return false; } finally { SecurityContextHolder.clearContext(); }
    }
}
