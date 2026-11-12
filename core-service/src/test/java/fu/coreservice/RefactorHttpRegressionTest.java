package fu.coreservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.client.GraphEditorAction;
import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;
import fu.coreservice.dto.knowledgegraph.GraphSnapshotResponse;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.repository.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring Security, controllers, services and JPA; only the external AI process is mocked. */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-context-test.properties",
        properties = "DB_URL=jdbc:h2:mem:refactor-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class RefactorHttpRegressionTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoBean AiCoreClient ai;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private static final String PASSWORD = "test-password-123";

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void studentCanRegisterLoginCreateNotebookChatAndChangePasswordWithoutLosingNotebook() throws Exception {
        String email = address();
        register(email, "STUDENT");
        String token = login(email, PASSWORD);
        JsonNode notebook = send(post("/api/notebooks"), token,
                Map.of("title", " My notebook ", "description", "Notes", "domain", " OOP ", "language", "vi"), 201);
        long id = notebook.path("data").path("id").asLong();
        assertThat(notebook.path("data").path("title").asText()).isEqualTo("My notebook");
        send(get("/api/notebooks/" + id), token, null, 200);
        send(get("/api/notebooks"), null, null, 401);
        send(post("/api/courses/" + id + "/chat/sessions"), token, Map.of("title", "Review OOP"), 201);
        JsonNode history = send(get("/api/courses/" + id + "/chat/sessions"), token, null, 200);
        assertThat(history.path("data").path("content").size()).isEqualTo(1);
        send(patch("/api/users/me"), token, Map.of("username", "Updated learner"), 200);
        send(put("/api/users/me/password"), token,
                Map.of("currentPassword", PASSWORD, "newPassword", "changed-password-456"), 200);
        send(get("/api/notebooks"), token, null, 401);
        String replacementToken = login(email, "changed-password-456");
        JsonNode reloaded = send(get("/api/notebooks/" + id), replacementToken, null, 200);
        assertThat(reloaded.path("data").path("title").asText()).isEqualTo("My notebook");
        verifyNoInteractions(ai);
    }

    @Test
    void instructorRemainsPendingUntilAnAdministratorApprovesThroughHttp() throws Exception {
        String instructorEmail = address();
        JsonNode pending = register(instructorEmail, "INSTRUCTOR");
        assertThat(pending.path("data").path("approvalStatus").asText()).isEqualTo("PENDING");
        assertThat(pending.path("data").path("accessToken").asText("")).isEmpty();
        send(post("/api/auth/login"), null, Map.of("email", instructorEmail, "password", PASSWORD), 403);
        long applicant = users.findByEmail(instructorEmail).orElseThrow().getUserId();
        String student = register(address(), "STUDENT").path("data").path("accessToken").asText();
        send(post("/api/admin/instructor-registrations/" + applicant + "/approve"), student, null, 403);
        String adminEmail = address();
        users.saveAndFlush(User.builder().email(adminEmail).username("Administrator " + UUID.randomUUID())
                .passwordHash(encoder.encode(PASSWORD)).role(UserRole.ADMIN).build());
        String admin = login(adminEmail, PASSWORD);
        send(get("/api/admin/instructor-registrations?status=PENDING"), admin, null, 200);
        send(post("/api/admin/instructor-registrations/" + applicant + "/approve"), admin, null, 200);
        assertThat(login(instructorEmail, PASSWORD)).isNotBlank();
        verifyNoInteractions(ai);
    }

    @Test
    void privateGraphRetainsTypedJsonAndRejectsOtherUsersBeforeCallingAi() throws Exception {
        String token = register(address(), "STUDENT").path("data").path("accessToken").asText();
        long id = send(post("/api/notebooks"), token,
                Map.of("title", "Graph notebook", "description", "", "domain", "OOP", "language", "vi"), 201)
                .path("data").path("id").asLong();
        String base = "/api/courses/" + id + "/knowledge-graph";
        when(ai.getGraphReview(eq(id), anyLong())).thenReturn(new GraphSnapshotResponse(8L,
                List.of(new GraphSnapshotResponse.Concept("a", "Class", List.of(), "vi", "AUTO", "AUTO")), List.of()));
        mvc.perform(get(base + "/review").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.graph_version").value(8))
                .andExpect(jsonPath("$.data.nodes[0].review_status").value("AUTO"));
        when(ai.mutateGraph(eq(id), anyLong(), eq(GraphEditorAction.EDGE_CREATE), isNull(), isNull(), eq(8L), anyMap()))
                .thenReturn(new GraphMutationResponse(9L, null, "a", "b", "MANUAL"));
        mvc.perform(post(base + "/edges?expected_version=8").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source_id\":\"a\",\"target_id\":\"b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.graph_version").value(9))
                .andExpect(jsonPath("$.data.status").value("MANUAL"));
        String other = register(address(), "STUDENT").path("data").path("accessToken").asText();
        clearInvocations(ai);
        send(get("/api/notebooks/" + id), other, null, 404);
        send(get(base + "/review"), other, null, 404);
        send(post(base + "/edges?expected_version=9"), other, Map.of("source_id", "b", "target_id", "a"), 404);
        verifyNoInteractions(ai);
    }

    private JsonNode register(String email, String role) throws Exception {
        return send(post("/api/auth/register"), null,
                Map.of("email", email, "username", "Learner " + UUID.randomUUID(), "password", PASSWORD, "role", role), 201);
    }

    private String login(String email, String password) throws Exception {
        String token = send(post("/api/auth/login"), null, Map.of("email", email, "password", password), 200)
                .path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        byte[] response = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray();
        return response.length == 0 ? json.nullNode() : json.readTree(response);
    }

    private String address() { return "regression-" + UUID.randomUUID() + "@example.invalid"; }
}
