package fu.coreservice.service;

import fu.coreservice.service.impl.AiRagServiceImpl;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiLearningPathRequest;
import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.dto.learningpath.LearningPathPlanRequest;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseEnrollmentRepository;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.DocumentRepository;
import fu.coreservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import fu.coreservice.exception.AiCoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRagServiceTest {
    @Test
    void studentCanEditOwnedNotebookGraph() {
        User owner = authenticate(15L);
        owner.setRole(fu.coreservice.entity.UserRole.STUDENT);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().courseId(7L).user(owner).notebook(true).build()));
        when(aiCoreClient.editGraph(7L, 15L, "edge-create", null, null, 8L, Map.of()))
                .thenReturn(Map.of("graph_version", 9));
        assertEquals(200, service.editGraph(7L, "edge-create", null, null, 8L, Map.of()).getStatusCode().value());
        verify(aiCoreClient).editGraph(7L, 15L, "edge-create", null, null, 8L, Map.of());
    }

    @Test
    void studentCanArchiveAndRestoreOwnedNotebookConcept() {
        User owner = authenticate(15L);
        owner.setRole(fu.coreservice.entity.UserRole.STUDENT);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).notebook(true).build()));
        when(aiCoreClient.conceptLifecycle(7L, 15L, "a", "archive", 8L)).thenReturn(Map.of("graph_version", 9));
        when(aiCoreClient.conceptLifecycle(7L, 15L, "a", "restore", 9L)).thenReturn(Map.of("graph_version", 10));
        assertEquals(200, service.archiveConcept(7L, "a", 8L).getStatusCode().value());
        assertEquals(200, service.restoreConcept(7L, "a", 9L).getStatusCode().value());
    }

    @Test
    void studentCanCreateOwnedNotebookConceptDraft() {
        User owner = authenticate(15L);
        owner.setRole(fu.coreservice.entity.UserRole.STUDENT);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).notebook(true).build()));
        ConceptDraftRequest request = new ConceptDraftRequest(); request.setName("Object");
        when(aiCoreClient.postConceptDraft(7L, 15L, null,
                Map.of("name", "Object", "aliases", List.of(), "language", "mixed")))
                .thenReturn(Map.of("draft_id", "draft"));
        assertEquals(200, service.createConceptDraft(7L, request).getStatusCode().value());
    }

    @Test
    void notebookNonOwnerCannotUseAnyGraphWriteEvenWithEnrollment() {
        User other = authenticate(15L); other.setRole(fu.coreservice.entity.UserRole.STUDENT);
        User owner = new User(); owner.setUserId(16L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).notebook(true).build()));
        assertThrows(AppException.class, () -> service.editGraph(7L, "snapshot", null, null, null, Map.of()));
        assertThrows(AppException.class, () -> service.archiveConcept(7L, "a", 8L));
        assertThrows(AppException.class, () -> service.restoreConcept(7L, "a", 8L));
        assertThrows(AppException.class, () -> service.publishConceptDraft(7L, "draft"));
        verifyNoInteractions(aiCoreClient);
    }

    @Test
    void editorForwardsAuthenticatedInstructorIdentity() {
        User owner = authenticate(15L);
        owner.setRole(fu.coreservice.entity.UserRole.INSTRUCTOR);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        when(aiCoreClient.editGraph(7L, 15L, "edge-create", null, null, 8L, Map.of()))
                .thenReturn(Map.of("graph_version", 9));
        assertEquals(200, service.editGraph(7L, "edge-create", null, null, 8L, Map.of()).getStatusCode().value());
        verify(aiCoreClient).editGraph(7L, 15L, "edge-create", null, null, 8L, Map.of());
    }

    @Test
    void enrolledInstructorCannotEditAnotherOwnersGraph() {
        User instructor = authenticate(15L);
        instructor.setRole(fu.coreservice.entity.UserRole.INSTRUCTOR);
        User owner = new User(); owner.setUserId(16L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        assertThrows(AppException.class, () -> service.editGraph(7L, "snapshot", null, null, null, Map.of()));
        verifyNoInteractions(aiCoreClient);
    }

    @Test
    void studentOwnerCannotUseInstructorEditor() {
        User owner = authenticate(15L);
        owner.setRole(fu.coreservice.entity.UserRole.STUDENT);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        assertThrows(AppException.class, () -> service.editGraph(7L, "concept-update", "a", null, 8L, Map.of()));
        verifyNoInteractions(aiCoreClient);
    }

    @Mock ActivityAlertService alerts;

    @Mock
    private AiCoreClient aiCoreClient;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private CourseEnrollmentRepository courseEnrollmentRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private LearnerMasteryService masteryService;

    @InjectMocks
    private AiRagServiceImpl service;

    @ParameterizedTest
    @ValueSource(strings = {"Invalid Internal Key", "Forbidden"})
    void relayedInternalOrUnconfirmed403IsNotAttributedToUser(String detail) {
        User owner = authenticate(15L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        when(aiCoreClient.conceptLifecycle(7L, 15L, "concept-a", "archive", 8L))
                .thenThrow(new AiCoreException(403, detail, Map.of()));
        assertEquals(403, service.archiveConcept(7L, "concept-a", 8L).getStatusCode().value());
        verify(alerts).repeatedSystemFailure("AI_CORE", "SYSTEM");
        org.mockito.Mockito.verifyNoMoreInteractions(alerts);
    }

    @Test
    void relayedOwnerDenialRemainsUserAccessAlert() {
        User owner = authenticate(15L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        when(aiCoreClient.conceptLifecycle(7L, 15L, "concept-a", "archive", 8L))
                .thenThrow(new AiCoreException(403, "Permission denied: user is not course owner", Map.of()));
        assertEquals(403, service.archiveConcept(7L, "concept-a", 8L).getStatusCode().value());
        verify(alerts).accessDenied("KNOWLEDGE_GRAPH");
        org.mockito.Mockito.verifyNoMoreInteractions(alerts);
    }


    @Test
    void createConceptDraft_forwardsOwnerIdentity() {
        User owner = authenticate(15L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        ConceptDraftRequest dto = new ConceptDraftRequest();
        dto.setName("HTTP Requests");
        Map<String, Object> request = Map.of("name", "HTTP Requests", "aliases", List.of(), "language", "mixed");
        when(aiCoreClient.postConceptDraft(7L, 15L, null, request)).thenReturn(Map.of("draft_id", "draft-1"));
        assertEquals(200, service.createConceptDraft(7L, dto).getStatusCode().value());
        verify(aiCoreClient).postConceptDraft(7L, 15L, null, request);
    }

    @Test
    void publishConceptDraft_deniesNonOwner() {
        authenticate(22L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().user(User.builder().userId(15L).build()).build()));
        AppException error = assertThrows(AppException.class,
                () -> service.publishConceptDraft(7L, "draft-1"));
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, error.getErrorCode());
        verifyNoInteractions(aiCoreClient);
    }


    @Test
    void archiveConcept_forwardsOwnerAndExpectedVersion() {
        User owner = authenticate(15L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(Course.builder().user(owner).build()));
        when(aiCoreClient.conceptLifecycle(7L, 15L, "concept-a", "archive", 8L))
                .thenReturn(Map.of("status", "ARCHIVED", "graph_version", 9));
        assertEquals(200, service.archiveConcept(7L, "concept-a", 8L).getStatusCode().value());
        verify(aiCoreClient).conceptLifecycle(7L, 15L, "concept-a", "archive", 8L);
        verify(alerts).destructiveAfterCommit(15L, "CONCEPT_ARCHIVE", "CONCEPT");
    }

    @Test
    void restoreConcept_deniesNonOwner() {
        authenticate(22L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().user(User.builder().userId(15L).build()).build()));
        assertThrows(AppException.class, () -> service.restoreConcept(7L, "concept-a", 8L));
        verifyNoInteractions(aiCoreClient);
    }

    @Test
    void getArchivedConcepts_deniesNonOwner() {
        authenticate(22L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().user(User.builder().userId(15L).build()).build()));
        assertThrows(AppException.class, () -> service.getArchivedConcepts(7L));
        verifyNoInteractions(aiCoreClient);
    }

    @Test
    void deletionImpact_deniesNonOwner() {
        authenticate(22L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().user(User.builder().userId(15L).build()).build()));
        assertThrows(AppException.class, () -> service.getConceptDeletionImpact(7L, "concept-a"));
        verifyNoInteractions(aiCoreClient);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void planLearningPath_allowsOwner() {
        User owner = authenticate(15L);
        when(courseRepository.findById(7L))
                .thenReturn(Optional.of(Course.builder().user(owner).build()));
        when(aiCoreClient.createLearningPathPlan(any())).thenReturn(Map.of("mode", "course"));

        var response = service.planLearningPath(7L, new LearningPathPlanRequest());

        assertEquals(200, response.getStatusCode().value());
        assertForwardedLearner("15");
    }

    @Test
    void planLearningPath_allowsEnrolledLearnerAndForwardsTheirIdentity() {
        User learner = authenticate(22L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().user(User.builder().userId(15L).build()).build()));
        when(courseEnrollmentRepository.existsByCourse_CourseIdAndUser(7L, learner)).thenReturn(true);
        when(aiCoreClient.createLearningPathPlan(any())).thenReturn(Map.of("mode", "course"));

        var response = service.planLearningPath(7L, new LearningPathPlanRequest());

        assertEquals(200, response.getStatusCode().value());
        assertForwardedLearner("22");
    }

    @Test
    void planLearningPath_deniesNonEnrolledUserBeforeCallingAiCore() {
        authenticate(22L);
        when(courseRepository.findById(7L)).thenReturn(Optional.of(
                Course.builder().user(User.builder().userId(15L).build()).build()));

        AppException error = assertThrows(AppException.class,
                () -> service.planLearningPath(7L, new LearningPathPlanRequest()));

        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, error.getErrorCode());
        verifyNoInteractions(aiCoreClient);
    }

    @Test
    void planLearningPath_requiresAuthentication() {
        SecurityContextHolder.clearContext();

        AppException error = assertThrows(AppException.class,
                () -> service.planLearningPath(7L, new LearningPathPlanRequest()));

        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
        verifyNoInteractions(aiCoreClient, userRepository, courseRepository, courseEnrollmentRepository);
    }

    private User authenticate(Long userId) {
        String email = "learner@example.com";
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
        User user = User.builder().userId(userId).role(fu.coreservice.entity.UserRole.INSTRUCTOR).build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        return user;
    }

    private void assertForwardedLearner(String userId) {
        ArgumentCaptor<AiLearningPathRequest> request = ArgumentCaptor.forClass(AiLearningPathRequest.class);
        verify(aiCoreClient).createLearningPathPlan(request.capture());
        assertEquals(userId, request.getValue().getUserId());
        assertEquals("7", request.getValue().getCourseId());
    }

    @Test
    void enrichSourceFileNames_resolvesNamesFromJavaDocumentMetadata() {
        Map<String, Object> source = new HashMap<>();
        source.put("document_id", "123");
        source.put("filename", null);
        Map<String, Object> concept = new HashMap<>();
        concept.put("sources", List.of(source));

        Document document = Document.builder()
                .documentId(123L)
                .fileName("algebra.pdf")
                .build();
        when(documentRepository.findByDocumentIdInAndCourseCourseId(List.of(123L), 7L))
                .thenReturn(List.of(document));

        ReflectionTestUtils.invokeMethod(service, "enrichSourceFileNames", concept, 7L);

        List<?> sources = (List<?>) concept.get("sources");
        Map<?, ?> enrichedSource = (Map<?, ?>) sources.get(0);
        assertEquals("algebra.pdf", enrichedSource.get("filename"));
        verify(documentRepository).findByDocumentIdInAndCourseCourseId(List.of(123L), 7L);
    }
    @Test
    void notebookGraphRejectsNonOwnerEvenWithEnrollment() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("other@test.com", null, List.of()));
        User owner = new User(); owner.setUserId(1L);
        User other = new User(); other.setUserId(2L);
        Course notebook = Course.builder().courseId(99L).user(owner).notebook(true).build();
        when(userRepository.findByEmail("other@test.com")).thenReturn(Optional.of(other));
        when(courseRepository.findById(99L)).thenReturn(Optional.of(notebook));
        when(courseEnrollmentRepository.existsByCourse_CourseIdAndUser(99L, other)).thenReturn(true);
        AppException error = assertThrows(AppException.class, () -> service.getKnowledgeGraph(99L));
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, error.getErrorCode());
        verifyNoInteractions(aiCoreClient);
    }
}
