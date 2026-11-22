package fu.coreservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.client.GraphEditorAction;
import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;
import fu.coreservice.dto.knowledgegraph.GraphSnapshotResponse;
import fu.coreservice.entity.*;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.AiDocumentProcessingService;
import fu.coreservice.service.SupabaseStorageService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real filter chain, controllers, ownership checks and persistence. */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-context-test.properties",
        properties = "DB_URL=jdbc:h2:mem:workspace-roles;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class WorkspaceRoleHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CourseRepository courses;
    @Autowired PasswordEncoder encoder;
    @MockitoBean AiCoreClient ai;
    @MockitoBean SupabaseStorageService storage;
    @MockitoBean AiDocumentProcessingService pipeline;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private static final String PASSWORD = "test-password-123";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"INSTRUCTOR", "ADMIN"})
    void nonStudentsCannotCallAnyPersonalLearningEndpoint(UserRole role) throws Exception {
        String token = login(user(role));
        for (String endpoint : List.of(
                "GET /api/notebooks", "POST /api/notebooks", "GET /api/notebooks/1",
                "POST /api/chat/sessions", "GET /api/chat/sessions", "GET /api/chat/sessions/search",
                "GET /api/chat/sessions/1", "PATCH /api/chat/sessions/1", "DELETE /api/chat/sessions/1",
                "GET /api/chat/sessions/1/messages", "POST /api/chat/sessions/1/messages",
                "GET /api/chat/sessions/1/messages/recent", "GET /api/chat/sessions/1/citations/1/source",
                "GET /api/chat/sessions/1/messages/1/feedback", "PUT /api/chat/sessions/1/messages/1/feedback",
                "DELETE /api/chat/sessions/1/messages/1/feedback",
                "POST /api/courses/1/chat/sessions", "GET /api/courses/1/chat/sessions",
                "GET /api/courses/1/chat/sessions/search", "GET /api/courses/1/chat/sessions/1",
                "PATCH /api/courses/1/chat/sessions/1", "DELETE /api/courses/1/chat/sessions/1",
                "GET /api/courses/1/chat/sessions/1/messages", "POST /api/courses/1/chat/sessions/1/messages",
                "GET /api/courses/1/chat/sessions/1/messages/recent", "GET /api/courses/1/chat/sessions/1/citations/1/source",
                "GET /api/courses/1/chat/sessions/1/messages/1/feedback", "PUT /api/courses/1/chat/sessions/1/messages/1/feedback",
                "DELETE /api/courses/1/chat/sessions/1/messages/1/feedback",
                "POST /api/courses/1/learning-path/plan", "POST /api/courses/1/learning-paths",
                "GET /api/courses/1/learning-paths", "GET /api/learning-paths/1",
                "PATCH /api/learning-paths/1/steps/1", "GET /api/courses/1/masteries/me",
                "PATCH /api/courses/1/masteries/me/concepts/A", "GET /api/courses/1/masteries/me/concepts/A/history")) {
            String[] parts = endpoint.split(" ", 2);
            send(request(HttpMethod.valueOf(parts[0]), parts[1]), token, null, 403);
        }
        verifyNoInteractions(ai, storage, pipeline);
    }

    @Test void studentCanUseNotebookChatLearningPathsAndMastery() throws Exception {
        String token = login(user(UserRole.STUDENT));
        long notebookId = send(post("/api/notebooks"), token,
                Map.of("title", "Private notebook", "domain", "CS", "language", "vi"), 201).path("data").path("id").asLong();
        send(get("/api/notebooks"), token, null, 200);
        send(get("/api/notebooks/" + notebookId), token, null, 200);
        send(post("/api/chat/sessions"), token, Map.of("title", "Public chat"), 201);
        send(post("/api/courses/" + notebookId + "/chat/sessions"), token, Map.of("title", "Private chat"), 201);
        when(ai.createLearningPathPlan(any())).thenReturn(Map.of(
                "course_id", String.valueOf(notebookId), "graph_version", 1L, "mode", "COURSE",
                "stages", List.of(Map.of("level", 0, "steps", List.of(Map.of("concept_id", "A", "name", "Arrays", "outside_document", false)))),
                "linear_order", List.of("A"), "warnings", List.of()));
        String base = "/api/courses/" + notebookId;
        JsonNode detail = send(post(base + "/learning-paths"), token, Map.of("planName", "Study arrays"), 201).path("data");
        long planId = detail.path("plan").path("planId").asLong();
        long stepId = detail.path("steps").get(0).path("stepId").asLong();
        send(get(base + "/learning-paths"), token, null, 200);
        send(get("/api/learning-paths/" + planId), token, null, 200);
        send(patch("/api/learning-paths/" + planId + "/steps/" + stepId), token, Map.of("status", "COMPLETED"), 200);
        assertThat(send(get(base + "/masteries/me"), token, null, 200).path("data").get(0).path("status").asText()).isEqualTo("MASTERED");
        send(patch(base + "/masteries/me/concepts/A"), token, Map.of("status", "NOT_STARTED"), 200);
        send(get(base + "/masteries/me/concepts/A/history"), token, null, 200);
    }

    @Test void instructorRetainsCourseDocumentLearnerAndGraphManagement() throws Exception {
        User teacher = user(UserRole.INSTRUCTOR);
        String token = login(teacher);
        Map<String, Object> body = Map.of("courseName", "Teaching course", "description", "Test materials", "domain", "CS", "language", "Vietnamese", "isPublic", true);
        long id = send(post("/api/courses"), token, body, 201).path("data").path("courseId").asLong();
        send(get("/api/courses/my"), token, null, 200);
        send(get("/api/courses/my/" + id), token, null, 200);
        send(put("/api/courses/" + id), token, body, 200);
        send(get("/api/courses/enrollment/" + id + "/list-learners"), token, null, 200);
        send(get("/api/document/course/" + id + "/documents"), token, null, 200);
        when(storage.uploadCourseMaterial(any(), eq(id), anyLong())).thenReturn("test/material.txt");
        send(multipart("/api/document/course/" + id).file(new MockMultipartFile("file", "material.txt", "text/plain", "Arrays".getBytes())), token, null, 201);
        verify(pipeline).triggerPipelineAsync(eq(id), anyLong(), eq(teacher.getUserId()), eq("material.txt"), any(), any());
        when(ai.getGraphReview(id, teacher.getUserId())).thenReturn(new GraphSnapshotResponse(1L, List.of(), List.of()));
        String graph = "/api/courses/" + id + "/knowledge-graph";
        send(get(graph + "/review"), token, null, 200);
        when(ai.mutateGraph(eq(id), eq(teacher.getUserId()), eq(GraphEditorAction.EDGE_CREATE), isNull(), isNull(), eq(1L), anyMap()))
                .thenReturn(new GraphMutationResponse(2L, null, "a", "b", "MANUAL"));
        send(post(graph + "/edges?expected_version=1"), token, Map.of("source_id", "a", "target_id", "b"), 200);
    }

    @Test void instructorCannotUseLegacyNotebookThroughSharedCourseDocumentOrGraphApis() throws Exception {
        User teacher = user(UserRole.INSTRUCTOR);
        Course notebook = courses.saveAndFlush(Course.builder().user(teacher).notebook(true).courseName("Legacy notebook")
                .description("").domain("CS").language("vi").build());
        long id = notebook.getCourseId();
        String token = login(teacher);
        send(get("/api/courses/my/" + id), token, null, 404);
        send(delete("/api/courses/" + id), token, null, 404);
        send(get("/api/courses/" + id + "/knowledge-graph"), token, null, 404);
        send(get("/api/courses/" + id + "/knowledge-graph/review"), token, null, 404);
        send(get("/api/document/course/" + id + "/documents"), token, null, 403);
        send(get("/api/document/course/" + id + "/documents/1/download"), token, null, 404);
        send(multipart("/api/document/course/" + id).file(new MockMultipartFile("file", "test.txt", "text/plain", "Arrays".getBytes())), token, null, 404);
        assertThat(courses.findById(id)).isPresent();
        verifyNoInteractions(ai, storage, pipeline);
    }

    private User user(UserRole role) {
        String unique = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().email(unique + "@test.local").username(unique)
                .passwordHash(encoder.encode(PASSWORD)).role(role)
                .approvalStatus(role == UserRole.INSTRUCTOR ? ApprovalStatus.APPROVED : ApprovalStatus.NOT_REQUIRED).build());
    }

    private String login(User user) throws Exception {
        return send(post("/api/auth/login"), null, Map.of("email", user.getEmail(), "password", PASSWORD), 200)
                .path("data").path("accessToken").asText();
    }

    private JsonNode send(AbstractMockHttpServletRequestBuilder<?> request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        byte[] response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray();
        return response.length == 0 ? json.nullNode() : json.readTree(response);
    }
}
