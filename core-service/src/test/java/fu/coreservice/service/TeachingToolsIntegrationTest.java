package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.CourseAiSettings;
import fu.coreservice.dto.chat.ChatTurn;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-context-test.properties",
        properties = "DB_URL=jdbc:h2:mem:teaching-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Transactional
class TeachingToolsIntegrationTest {
    @MockitoBean AiCoreClient ai;
    @MockitoBean SupabaseStorageService storage;
    @Autowired CourseAiSettingsService settings;
    @Autowired CourseLearningAnalyticsService analytics;
    @Autowired UserRepository users;
    @Autowired CourseRepository courses;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired MessageFeedbackRepository feedback;
    @Autowired LearnerMasteryRepository masteries;
    @Autowired ConceptQueryRepository queries;
    @Autowired Validator validator;
    @Autowired ChatSessionService chatSessions;
    @Autowired ChatHistoryService chatHistory;
    @Autowired CourseEnrollmentRepository enrollments;
    private User owner, student, other, admin;
    private Course course;

    @BeforeEach void setup() {
        owner = user(UserRole.INSTRUCTOR); student = user(UserRole.STUDENT);
        other = user(UserRole.INSTRUCTOR); admin = user(UserRole.ADMIN);
        course = courses.saveAndFlush(Course.builder().user(owner).courseName("Graph algorithms")
                .description("Test").domain("CS").language("en").build());
        signIn(owner);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void settingsPersistAndOnlyOwnerOrAdminCanManageThem() {
        assertThat(settings.get(course.getCourseId())).isEqualTo(CourseAiSettings.defaults());
        var request = new CourseAiSettings(.81, 5, "Explain using diagrams");
        settings.save(course.getCourseId(), request);
        assertThat(settings.get(course.getCourseId())).isEqualTo(request);
        assertThat(settings.forChat(List.of(course.getCourseId().toString())))
                .containsEntry(course.getCourseId().toString(), request);
        assertThat(settings.forChat(List.of())).isEmpty();
        signIn(other);
        assertThatThrownBy(() -> settings.get(course.getCourseId())).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> settings.save(course.getCourseId(), request)).isInstanceOf(AppException.class);
        assertThat(settings.listCourses(0).getContent()).isEmpty();
        signIn(student);
        assertThatThrownBy(() -> settings.listCourses(0)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> analytics.report(course.getCourseId(), 30)).isInstanceOf(AppException.class);
        signIn(admin);
        assertThat(settings.get(course.getCourseId())).isEqualTo(request);
        assertThat(settings.listCourses(0).getContent()).extracting(CourseAiSettingsService.CourseOption::courseId)
                .contains(course.getCourseId());
    }

    @Test void invalidSettingsAreRejectedByTheHttpValidationContract() {
        assertThat(validator.validate(new CourseAiSettings(1.01, 7, "x".repeat(4001)))).hasSize(3);
        assertThat(validator.validate(new CourseAiSettings(-.1, -1, null))).hasSize(3);
        assertThat(validator.validate(CourseAiSettings.defaults())).isEmpty();
    }

    @Test void analyticsCountsTargetsRepeatLearnersFeedbackAndCurrentMasteryWithoutDoubleCounting() {
        ChatMessage first = answer(student);
        var target = new CourseLearningAnalyticsService.Target("C1", "Prerequisites", course.getCourseId().toString());
        ChatTurn turn = new ChatTurn(first.getSession().getSessionId(), student.getUserId(), 1L, first.getMessageId());
        analytics.record(turn, List.of(target, target), List.of(course.getCourseId().toString()));
        analytics.record(turn, List.of(target), List.of(course.getCourseId().toString()));
        ChatMessage second = answer(student);
        analytics.record(new ChatTurn(second.getSession().getSessionId(), student.getUserId(), 1L, second.getMessageId()),
                List.of(target), List.of(course.getCourseId().toString()));
        feedback.saveAndFlush(MessageFeedback.builder().message(first).user(student).rating((short)-1).build());
        masteries.saveAndFlush(LearnerMastery.builder().learner(student).course(course).neo4jConceptId("C1")
                .masteryStatus(MasteryStatus.IN_PROGRESS).build());
        // Teaching staff's own questions must not inflate learner demand.
        ChatMessage teacher = answer(owner);
        analytics.record(new ChatTurn(teacher.getSession().getSessionId(), owner.getUserId(), 1L, teacher.getMessageId()),
                List.of(target), List.of(course.getCourseId().toString()));
        var report = analytics.report(course.getCourseId(), 30);
        assertThat(report.concepts()).hasSize(1);
        var row = report.concepts().getFirst();
        assertThat(row.queries()).isEqualTo(2);
        assertThat(row.learners()).isEqualTo(1);
        assertThat(row.repeatLearners()).isEqualTo(1);
        assertThat(row.negativeFeedback()).isEqualTo(1);
        assertThat(row.inProgress()).isEqualTo(1);
        assertThat(row.mastered()).isZero();
        assertThat(row.name()).isEqualTo("Prerequisites");
        assertThatThrownBy(() -> analytics.report(course.getCourseId(), 0)).isInstanceOf(AppException.class);
    }

    @Test void analyticsCannotRecordOutsideTheAuthorizedScopeOrOnAnUnfinishedAnswer() {
        ChatMessage answer = answer(student);
        var target = new CourseLearningAnalyticsService.Target("C1", "Target", course.getCourseId().toString());
        ChatTurn turn = new ChatTurn(answer.getSession().getSessionId(), student.getUserId(), 1L, answer.getMessageId());
        analytics.record(turn, List.of(target), List.of("999999"));
        assertThat(queries.existsByMessage_MessageId(answer.getMessageId())).isFalse();
        answer.setStatus(ChatMessageStatus.FAILED); messages.saveAndFlush(answer);
        analytics.record(turn, List.of(target), List.of(course.getCourseId().toString()));
        assertThat(queries.existsByMessage_MessageId(answer.getMessageId())).isFalse();
    }

    @Test void publicSuggestionsPersistAndRecheckVisibilityNameAndEnrollmentOnHistoryReload() {
        course.setPublic(true); courses.saveAndFlush(course);
        Course hidden = courses.saveAndFlush(Course.builder().user(owner).courseName("Private")
                .description("Private").domain("CS").language("en").build());
        Course notebook = courses.saveAndFlush(Course.builder().user(owner).courseName("Notebook")
                .description("Notebook").domain("CS").language("en").notebook(true).isPublic(true).build());
        signIn(student);
        assertThat(chatSessions.getPublicChatCatalog()).containsKey(course.getCourseId().toString())
                .doesNotContainKeys(hidden.getCourseId().toString(), notebook.getCourseId().toString());
        assertThat(chatSessions.getPublicChatCatalog().get(course.getCourseId().toString()).enrolled()).isFalse();
        var session = chatSessions.createGlobalSession(new fu.coreservice.dto.chat.ChatSessionCreateRequest());
        ChatTurn turn = chatSessions.beginGlobalTurn(session.getSessionId(), fu.coreservice.dto.chat.ChatRequest.builder().message("Gradient?").build());
        var suggestion = new fu.coreservice.dto.chat.CourseSuggestion(course.getCourseId(), "Stale title", false,
                "Gradient", List.of("gradient"), null, "Explain Gradient");
        chatSessions.finishSuggestedTurn(turn, "Would you like to join?", 10, List.of(suggestion));
        var saved = chatHistory.recent(null, session.getSessionId(), null, 20).messages().getLast();
        assertThat(saved.getCourseSuggestions()).hasSize(1);
        assertThat(saved.getCourseSuggestions().getFirst().courseName()).isEqualTo("Graph algorithms");
        enrollments.saveAndFlush(CourseEnrollment.builder().course(course).user(student).build());
        assertThat(chatSessions.getPublicChatCatalog().get(course.getCourseId().toString()).enrolled()).isTrue();
        var joined = chatHistory.recent(null, session.getSessionId(), null, 20).messages().getLast().getCourseSuggestions();
        assertThat(joined.getFirst().enrolled()).isTrue();
        course.setPublic(false); courses.saveAndFlush(course);
        assertThat(chatHistory.recent(null, session.getSessionId(), null, 20).messages().getLast().getCourseSuggestions()).isEmpty();
    }

    private User user(UserRole role) {
        String id = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().username(id).email(id + "@test.local").passwordHash("test")
                .role(role).approvalStatus(role == UserRole.INSTRUCTOR ? ApprovalStatus.APPROVED : ApprovalStatus.NOT_REQUIRED).build());
    }
    private void signIn(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
    }
    private ChatMessage answer(User learner) {
        ChatSession session = sessions.saveAndFlush(ChatSession.builder().learner(learner).course(course).title("Question").build());
        return messages.saveAndFlush(ChatMessage.builder().session(session).senderType(SenderType.BOT)
                .content("Answer").status(ChatMessageStatus.COMPLETED).build());
    }
}
