package fu.coreservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.client.GraphEditorAction;
import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;
import fu.coreservice.dto.knowledgegraph.GraphSnapshotResponse;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import fu.coreservice.service.SupabaseStorageService;
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
    @MockitoBean SupabaseStorageService storage;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired MessageCitationRepository citations;
    @Autowired MessageFeedbackRepository feedback;
    @Autowired DocumentRepository documents;
    @Autowired CourseRepository courses;
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

    @Test
    void chatHistorySearchRenameAndDeleteRespectOwnerAndExactScope() throws Exception {
        String email = address();
        String token = register(email, "STUDENT").path("data").path("accessToken").asText();
        User owner = users.findByEmail(email).orElseThrow();
        long courseId = send(post("/api/notebooks"), token, Map.of("title", "Private", "domain", "OOP", "language", "vi"), 201).path("data").path("id").asLong();
        long id = send(post("/api/chat/sessions"), token, Map.of("title", "100% Java"), 201).path("data").path("sessionId").asLong();
        send(post("/api/chat/sessions"), token, Map.of("title", "1000 Java"), 201);
        send(post("/api/courses/" + courseId + "/chat/sessions"), token, Map.of("title", "100% private"), 201);
        JsonNode found = send(get("/api/chat/sessions/search").param("q", "100%"), token, null, 200).path("data");
        assertThat(found.path("totalElements").asInt()).isEqualTo(1);
        assertThat(found.path("content").get(0).path("sessionId").asLong()).isEqualTo(id);
        String other = register(address(), "STUDENT").path("data").path("accessToken").asText();
        String base = "/api/chat/sessions/" + id;
        send(get(base), other, null, 404);
        send(patch(base), other, Map.of("title", "Stolen"), 404);
        send(delete(base), other, null, 404);
        send(get("/api/courses/" + courseId + "/chat/sessions/" + id), token, null, 404);
        assertThat(send(patch(base), token, Map.of("title", " Updated "), 200).path("data").path("title").asText()).isEqualTo("Updated");
        ChatSession session = sessions.findById(id).orElseThrow();
        ChatMessage question = messages.saveAndFlush(ChatMessage.builder().session(session).senderType(SenderType.USER).content("Why?").build());
        assertThat(send(get("/api/chat/sessions/search").param("q", "Why?"), token, null, 200).path("data").path("content").get(0).path("sessionId").asLong()).isEqualTo(id);
        ChatMessage answer = messages.saveAndFlush(ChatMessage.builder().session(session).senderType(SenderType.BOT).content("Because [1]").replyToMessage(question).build());
        long citation = citations.saveAndFlush(MessageCitation.builder().message(answer).citationNumber(1).citationType(CitationType.ANSWER_CONCEPT).build()).getCitationId();
        long rating = feedback.saveAndFlush(MessageFeedback.builder().message(answer).user(owner).rating((short) 1).build()).getFeedbackId();
        send(delete(base), token, null, 200);
        assertThat(sessions.existsById(id)).isFalse();
        assertThat(messages.existsById(answer.getMessageId())).isFalse();
        assertThat(citations.existsById(citation)).isFalse();
        assertThat(feedback.existsById(rating)).isFalse();
    }

    @Test
    void recentHistoryUsesStableCursorAndReloadsClarificationChoices() throws Exception {
        String token = register(address(), "STUDENT").path("data").path("accessToken").asText();
        long id = send(post("/api/chat/sessions"), token, Map.of("title", "Pages"), 201).path("data").path("sessionId").asLong();
        ChatSession session = sessions.findById(id).orElseThrow();
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 35; i++) ids.add(messages.saveAndFlush(ChatMessage.builder().session(session).senderType(SenderType.USER)
                .content("Question " + i).clarificationOptions(i == 34 ? "[{\"id\":\"class-1\",\"name\":\"Class\"}]" : null).build()).getMessageId());
        String base = "/api/chat/sessions/" + id + "/messages/recent";
        JsonNode recent = send(get(base), token, null, 200).path("data");
        assertThat(recent.path("messages").size()).isEqualTo(30);
        assertThat(recent.path("hasOlder").asBoolean()).isTrue();
        assertThat(recent.path("oldestId").asLong()).isEqualTo(ids.get(5));
        assertThat(recent.path("messages").get(29).path("clarificationCandidates").get(0).path("id").asText()).isEqualTo("class-1");
        JsonNode older = send(get(base).param("beforeId", ids.get(5).toString()), token, null, 200).path("data");
        assertThat(older.path("messages").size()).isEqualTo(5);
        assertThat(older.path("hasOlder").asBoolean()).isFalse();
        assertThat(older.path("messages").get(4).path("messageId").asLong()).isEqualTo(ids.get(4));
        send(get(base).param("size", "101"), token, null, 400);
        ChatMessage streaming = messages.saveAndFlush(ChatMessage.builder().session(session).senderType(SenderType.BOT).content("").status(ChatMessageStatus.STREAMING).build());
        send(delete("/api/chat/sessions/" + id), token, null, 409);
        assertThat(messages.existsById(streaming.getMessageId())).isTrue();
    }

    @Test
    void citationSourceChecksOwnershipAndDocumentVisibilityBeforeSigning() throws Exception {
        String email = address();
        String token = register(email, "STUDENT").path("data").path("accessToken").asText();
        long courseId = send(post("/api/notebooks"), token, Map.of("title", "Sources", "domain", "OOP", "language", "vi"), 201).path("data").path("id").asLong();
        Course course = courses.findById(courseId).orElseThrow();
        Document doc = documents.saveAndFlush(Document.builder().course(course).user(users.findByEmail(email).orElseThrow())
                .fileName("OOP.pdf").fileUrlPath("private/oop.pdf").docStatus(UploadStatus.COMPLETED).build());
        String base = "/api/courses/" + courseId + "/chat/sessions";
        long id = send(post(base), token, Map.of("title", "Sources"), 201).path("data").path("sessionId").asLong();
        ChatMessage answer = messages.saveAndFlush(ChatMessage.builder().session(sessions.findById(id).orElseThrow()).senderType(SenderType.BOT).content("[1]").build());
        long citation = citations.saveAndFlush(MessageCitation.builder().message(answer).documentId(doc.getDocumentId()).pageNumber(7)
                .citationNumber(1).citationType(CitationType.ANSWER_CONCEPT).build()).getCitationId();
        when(storage.createSignedUrl("private/oop.pdf", 300)).thenReturn("https://storage.example/oop.pdf?token=test");
        String endpoint = base + "/" + id + "/citations/" + citation + "/source";
        JsonNode source = send(get(endpoint), token, null, 200).path("data");
        assertThat(source.path("fileName").asText()).isEqualTo("OOP.pdf");
        assertThat(source.path("pageNumber").asInt()).isEqualTo(7);
        verify(storage).createSignedUrl("private/oop.pdf", 300);
        clearInvocations(storage);
        String other = register(address(), "STUDENT").path("data").path("accessToken").asText();
        send(get(endpoint), other, null, 404);
        long global = send(post("/api/chat/sessions"), token, Map.of("title", "Global"), 201).path("data").path("sessionId").asLong();
        ChatMessage globalAnswer = messages.saveAndFlush(ChatMessage.builder().session(sessions.findById(global).orElseThrow()).senderType(SenderType.BOT).content("[1]").build());
        long badCitation = citations.saveAndFlush(MessageCitation.builder().message(globalAnswer).documentId(doc.getDocumentId())
                .citationNumber(1).citationType(CitationType.ANSWER_CONCEPT).build()).getCitationId();
        send(get("/api/chat/sessions/" + global + "/citations/" + badCitation + "/source"), token, null, 404);
        verifyNoInteractions(storage);
    }

    @Test
    void notebookDocumentAuthorizationResolvesServiceBeanAndKeepsOtherUsersOut() throws Exception {
        String email = address();
        String owner = register(email, "STUDENT").path("data").path("accessToken").asText();
        String other = register(address(), "STUDENT").path("data").path("accessToken").asText();
        long notebookId = send(post("/api/notebooks"), owner,
                Map.of("title", "Private documents", "domain", "CS", "language", "vi"), 201).path("data").path("id").asLong();
        Course notebook = courses.findById(notebookId).orElseThrow();
        Document document = documents.saveAndFlush(Document.builder().course(notebook)
                .user(users.findByEmail(email).orElseThrow()).fileName("private.pdf").docStatus(UploadStatus.COMPLETED).build());
        String base = "/api/document/course/" + notebookId + "/documents";
        assertThat(send(get(base), owner, null, 200).path("data").path("content").get(0).path("documentId").asLong())
                .isEqualTo(document.getDocumentId());
        send(get(base + "/" + document.getDocumentId()), owner, null, 200);
        send(get(base), other, null, 403);
        send(get(base + "/" + document.getDocumentId()), other, null, 403);
        User teacher = users.saveAndFlush(User.builder().email(address()).username("Teacher " + UUID.randomUUID())
                .role(UserRole.INSTRUCTOR).approvalStatus(ApprovalStatus.APPROVED).passwordHash(encoder.encode(PASSWORD)).build());
        String instructor = login(teacher.getEmail(), PASSWORD);
        send(get(base), instructor, null, 403);
        send(get(base + "/" + document.getDocumentId()), instructor, null, 403);
        Course course = courses.saveAndFlush(Course.builder().user(teacher).courseName("Owned course")
                .description("Test").domain("CS").language("vi").notebook(false).build());
        send(get("/api/document/course/" + course.getCourseId() + "/documents"), instructor, null, 200);
    }

    @Test
    void onlyOwningInstructorCanReviewAndPublishAndDraftsStayHiddenFromOtherTeachers() throws Exception {
        User teacher = users.saveAndFlush(User.builder().email(address()).username("Reviewer " + UUID.randomUUID())
                .role(UserRole.INSTRUCTOR).approvalStatus(ApprovalStatus.APPROVED).passwordHash(encoder.encode(PASSWORD)).build());
        User otherTeacher = users.saveAndFlush(User.builder().email(address()).username("Other " + UUID.randomUUID())
                .role(UserRole.INSTRUCTOR).approvalStatus(ApprovalStatus.APPROVED).passwordHash(encoder.encode(PASSWORD)).build());
        String owner = login(teacher.getEmail(), PASSWORD);
        String other = login(otherTeacher.getEmail(), PASSWORD);
        String student = register(address(), "STUDENT").path("data").path("accessToken").asText();
        Course course = courses.saveAndFlush(Course.builder().user(teacher).courseName("Draft review")
                .description("Test").domain("CS").language("vi").notebook(false).build());
        Document draft = documents.saveAndFlush(Document.builder().course(course).user(teacher)
                .fileName("oop.pdf").docStatus(UploadStatus.AWAITING_REVIEW).build());
        long courseId = course.getCourseId(), documentId = draft.getDocumentId();
        String list = "/api/document/course/" + courseId + "/documents";
        String base = list + "/" + documentId;
        Map<String, Object> body = Map.of("review_id", "draft-1", "excluded_concepts", List.of(), "excluded_edges", List.of());
        when(ai.previewDocument(documentId, courseId, teacher.getUserId()))
                .thenReturn(Map.of("review_id", "draft-1", "concepts", List.of(), "edges", List.of()));
        send(get(base + "/review"), student, null, 403);
        send(post(base + "/publish"), student, body, 403);
        send(get(base + "/review"), other, null, 404);
        send(post(base + "/publish"), other, body, 404);
        send(get(base), other, null, 404);
        assertThat(send(get(list), other, null, 200).path("data").path("totalElements").asInt()).isZero();
        verify(ai, never()).publishDocument(anyLong(), anyLong(), anyLong(), any());
        assertThat(send(get(base + "/review"), owner, null, 200).path("data").path("review_id").asText()).isEqualTo("draft-1");
        assertThat(documents.findById(documentId).orElseThrow().getDocStatus()).isEqualTo(UploadStatus.AWAITING_REVIEW);
        send(post(base + "/publish"), owner, body, 200);
        verify(ai).publishDocument(eq(documentId), eq(courseId), eq(teacher.getUserId()), any());
        assertThat(documents.findById(documentId).orElseThrow().getDocStatus()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(documents.findById(documentId).orElseThrow().getParseAt()).isNotNull();
        assertThat(send(get(list), other, null, 200).path("data").path("totalElements").asInt()).isEqualTo(1);
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
