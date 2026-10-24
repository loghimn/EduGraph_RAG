package fu.coreservice.service;

import fu.coreservice.service.impl.LearningPathServiceImpl;
import fu.coreservice.service.impl.LearnerMasteryServiceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.learningpath.CreateLearningPathRequest;
import fu.coreservice.dto.learningpath.UpdateLearningPathStepRequest;
import fu.coreservice.dto.learningpath.LearningPathDetail;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

class LearningPathServiceTest {
    private SessionFactory factory;
    private TransactionTemplate tx;
    private LearningPathService service;
    private AiCoreClient ai;
    private LearningPathPlanRepository plans;
    private LearningPathPlanRepository realPlans;
    private Long courseId;
    private Long documentId;
    private Long learnerId;
    private String learnerEmail;
    private UserRepository users;
    private CourseEnrollmentRepository enrollments;
    private LearnerMasteryService mastery;

    @BeforeEach
    void setup() {
        factory = new Configuration().addAnnotatedClass(User.class).addAnnotatedClass(Course.class)
                .addAnnotatedClass(Document.class).addAnnotatedClass(CourseEnrollment.class)
                .addAnnotatedClass(LearningPathPlan.class).addAnnotatedClass(LearningPathGoal.class)
                .addAnnotatedClass(LearningPathStep.class).addAnnotatedClass(LearnerMastery.class).addAnnotatedClass(LearnerMasteryHistory.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true").buildSessionFactory();
        JpaTransactionManager manager = new JpaTransactionManager(factory);
        tx = new TransactionTemplate(manager);
        var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var repositories = new JpaRepositoryFactory(em);
        users = repositories.getRepository(UserRepository.class);
        enrollments = repositories.getRepository(CourseEnrollmentRepository.class);
        realPlans = repositories.getRepository(LearningPathPlanRepository.class);
        plans = mock(LearningPathPlanRepository.class, delegatesTo(realPlans));
        ai = mock(AiCoreClient.class);
        mastery = new LearnerMasteryServiceImpl(repositories.getRepository(LearnerMasteryRepository.class),
                repositories.getRepository(LearnerMasteryHistoryRepository.class), users,
                repositories.getRepository(CourseRepository.class), enrollments);
        var target = new LearningPathServiceImpl(ai, new ObjectMapper(), users,
                repositories.getRepository(CourseRepository.class), enrollments,
                repositories.getRepository(DocumentRepository.class), plans,
                repositories.getRepository(LearningPathStepRepository.class), manager,
                mastery);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (LearningPathService) proxy.getProxy();

        tx.executeWithoutResult(status -> {
            User owner = user("owner");
            User learner = user("learner");
            em.persist(owner);
            em.persist(learner);
            learnerId = learner.getUserId();
            learnerEmail = learner.getEmail();
            Course course = Course.builder().user(owner).courseName("Math").description("Math")
                    .domain("Math").language("en").build();
            em.persist(course);
            courseId = course.getCourseId();
            em.persist(CourseEnrollment.builder().course(course).user(learner).build());
            Document document = Document.builder().course(course).docStatus(UploadStatus.COMPLETED).build();
            em.persist(document);
            documentId = document.getDocumentId();
        });
        login(learnerEmail);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        if (factory != null) factory.close();
    }

    @ParameterizedTest
    @EnumSource(LearningPathMode.class)
    void createsAndReadsAllModesWithoutTransactionDuringAiCall(LearningPathMode mode) {
        when(ai.createLearningPathPlan(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var request = (fu.coreservice.dto.ai.AiLearningPathRequest) invocation.getArgument(0);
            assertEquals(learnerId.toString(), request.getUserId());
            assertTrue(request.getIncludeExternalPrereqs());
            return generated(mode);
        });

        var created = service.create(courseId, request(mode, 3L));
        assertNotNull(created.plan().planId());
        assertEquals(mode, created.plan().mode());
        assertEquals(learnerId, tx.execute(status -> plans.findById(created.plan().planId())
                .orElseThrow().getLearner().getUserId()));
        assertEquals(mode == LearningPathMode.GOAL ? List.of("C5") : List.of(), created.goalConceptIds());
        assertEquals(mode == LearningPathMode.DOCUMENT ? documentId : null, created.plan().documentId());
        assertEquals(List.of("C2", "C1", "C5"), created.steps().stream().map(s -> s.conceptId()).toList());
        // Stage order is deliberately different from linear order.
        assertEquals(List.of(0, 0, 1), created.steps().stream().map(s -> s.stageLevel()).toList());
        assertEquals(List.of(1, 2, 3), created.steps().stream().map(s -> s.stepOrder()).toList());
        assertEquals(List.of("Example warning"), created.warnings());
        assertEquals(3, created.plan().totalSteps());
        assertEquals(0, created.plan().progressPercentage());

        var loaded = service.get(created.plan().planId());
        assertEquals(created.steps(), loaded.steps());
        assertEquals(List.of(), loaded.warnings());
        var list = service.list(courseId, LearningPathStatus.ACTIVE, 0, 20);
        assertEquals(1, list.getTotalElements());
        assertEquals(3, list.getContent().getFirst().totalSteps());
        assertEquals(0, service.list(courseId, LearningPathStatus.ARCHIVED, 0, 20).getTotalElements());
    }

    @Test
    void restrictsPlansToTheirLearnerEvenForCourseOwner() {
        when(ai.createLearningPathPlan(any())).thenReturn(generated(LearningPathMode.GOAL));
        Long id = service.create(courseId, request(LearningPathMode.GOAL, null)).plan().planId();
        login("owner@test.local");
        assertEquals(ErrorCode.LEARNING_PATH_NOT_FOUND, assertThrows(AppException.class,
                () -> service.get(id)).getErrorCode());
        assertEquals(0, service.list(courseId, null, 0, 20).getTotalElements());
    }

    @Test
    void rechecksEnrollmentForSavedPlan() {
        when(ai.createLearningPathPlan(any())).thenReturn(generated(LearningPathMode.COURSE));
        Long id = service.create(courseId, request(LearningPathMode.COURSE, null)).plan().planId();
        removeEnrollment();
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.get(id)).getErrorCode());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.list(courseId, null, 0, 20)).getErrorCode());
    }

    @Test
    void deniesNonEnrolledUserAndUnauthenticatedUserBeforeAiCall() {
        removeEnrollment();
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.create(courseId, request(LearningPathMode.COURSE, null))).getErrorCode());
        SecurityContextHolder.clearContext();
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(AppException.class,
                () -> service.create(courseId, request(LearningPathMode.COURSE, null))).getErrorCode());
        verifyNoInteractions(ai);
    }

    @Test
    void staleGraphAndEmptyOrMalformedAiResponseDoNotSave() {
        when(ai.createLearningPathPlan(any())).thenReturn(generated(LearningPathMode.GOAL));
        assertEquals(ErrorCode.LEARNING_PATH_GRAPH_CHANGED, assertThrows(AppException.class,
                () -> service.create(courseId, request(LearningPathMode.GOAL, 2L))).getErrorCode());

        Map<String, Object> empty = new HashMap<>(generated(LearningPathMode.GOAL));
        empty.put("stages", List.of());
        empty.put("linear_order", List.of());
        when(ai.createLearningPathPlan(any())).thenReturn(empty);
        assertEquals(ErrorCode.LEARNING_PATH_EMPTY, assertThrows(AppException.class,
                () -> service.create(courseId, request(LearningPathMode.GOAL, null))).getErrorCode());

        when(ai.createLearningPathPlan(any())).thenReturn(Map.of("mode", "goal"));
        assertEquals(ErrorCode.AI_CORE_ERROR, assertThrows(AppException.class,
                () -> service.create(courseId, request(LearningPathMode.GOAL, null))).getErrorCode());
        assertEquals(0L, tx.execute(status -> plans.count()).longValue());
    }

    @Test
    void aiConflictIsPropagatedWithoutSaving() {
        AiCoreException conflict = new AiCoreException(409, "Cycle", Map.of("conflicts", List.of("C1->C2")));
        when(ai.createLearningPathPlan(any())).thenThrow(conflict);
        assertSame(conflict, assertThrows(AiCoreException.class,
                () -> service.create(courseId, request(LearningPathMode.GOAL, null))));
        assertEquals(0L, tx.execute(status -> plans.count()).longValue());
    }

    @Test
    void rollsBackPlanGoalsAndStepsIfWriteFailsAfterFlush() {
        when(ai.createLearningPathPlan(any())).thenReturn(generated(LearningPathMode.GOAL));
        doAnswer(invocation -> {
            realPlans.saveAndFlush(invocation.getArgument(0));
            throw new IllegalStateException("Simulated failure after inserts");
        }).when(plans).saveAndFlush(any());
        assertThrows(IllegalStateException.class,
                () -> service.create(courseId, request(LearningPathMode.GOAL, null)));
        tx.executeWithoutResult(status -> {
            var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
            for (String entity : List.of("LearningPathPlan", "LearningPathGoal", "LearningPathStep")) {
                assertEquals(0L, em.createQuery("select count(e) from " + entity + " e", Long.class)
                        .getSingleResult());
            }
        });
    }

    @Test
    void rejectsMixedModeForeignDocumentAndBadPagination() {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(AppException.class,
                () -> service.create(courseId, new CreateLearningPathRequest(null, documentId,
                        List.of("C5"), null, null, null, null))).getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, assertThrows(AppException.class,
                () -> service.create(courseId, new CreateLearningPathRequest(null, Long.MAX_VALUE,
                        null, null, null, null, null))).getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(AppException.class,
                () -> service.list(courseId, null, 0, 101)).getErrorCode());
        verifyNoInteractions(ai);
    }

    @Test
    void unlocksByStageRatherThanStepOrderAndCompletesPlan() {
        Map<String, Object> result = new HashMap<>(generated(LearningPathMode.COURSE));
        result.put("linear_order", List.of("C1", "C5", "C2"));
        when(ai.createLearningPathPlan(any())).thenReturn(result);
        var created = service.create(courseId, request(LearningPathMode.COURSE, null));
        Long id = created.plan().planId();
        assertEquals(List.of(true, false, true), created.steps().stream().map(s -> s.isUnlocked()).toList());
        Long first = created.steps().get(0).stepId();
        Long locked = created.steps().get(1).stepId();
        Long laterButOpen = created.steps().get(2).stepId();
        assertUpdateError(ErrorCode.LEARNING_PATH_STEP_LOCKED, id, locked, PathStepStatus.IN_PROGRESS);
        assertUpdateError(ErrorCode.LEARNING_PATH_STEP_LOCKED, id, locked, PathStepStatus.COMPLETED);

        service.updateStep(id, laterButOpen, update(PathStepStatus.COMPLETED));
        assertFalse(service.get(id).steps().get(1).isUnlocked());
        var stageFinished = service.updateStep(id, first, update(PathStepStatus.COMPLETED));
        assertTrue(stageFinished.steps().get(1).isUnlocked());
        assertEquals(LearningPathStatus.ACTIVE, stageFinished.plan().status());
        assertNull(stageFinished.plan().completedAt());

        var allFinished = service.updateStep(id, locked, update(PathStepStatus.COMPLETED));
        assertEquals(LearningPathStatus.COMPLETED, allFinished.plan().status());
        assertNotNull(allFinished.plan().completedAt());
        assertEquals(3, allFinished.plan().completedSteps());
        assertTrue(allFinished.steps().stream().allMatch(s -> s.completedAt() != null));
        var retry = service.updateStep(id, locked, update(PathStepStatus.COMPLETED));
        assertEquals(allFinished.steps(), retry.steps());
        assertEquals(allFinished.plan().completedAt(), retry.plan().completedAt());
        assertEquals(1, service.list(courseId, LearningPathStatus.COMPLETED, 0, 20).getTotalElements());
        assertUpdateError(ErrorCode.LEARNING_PATH_STEP_COMPLETED, id, first, PathStepStatus.PENDING);
    }

    @Test
    void supportsStartingAndResettingAnUnfinishedStepButNotSkippingOrReopening() {
        var plan = createSavedPlan();
        Long id = plan.plan().planId();
        Long stepId = plan.steps().getFirst().stepId();
        var started = service.updateStep(id, stepId, update(PathStepStatus.IN_PROGRESS));
        assertEquals(PathStepStatus.IN_PROGRESS, started.steps().getFirst().status());
        assertNull(started.steps().getFirst().completedAt());
        var reset = service.updateStep(id, stepId, update(PathStepStatus.PENDING));
        assertEquals(PathStepStatus.PENDING, reset.steps().getFirst().status());
        assertUpdateError(ErrorCode.INVALID_REQUEST, id, stepId, PathStepStatus.SKIPPED);
        assertUpdateError(ErrorCode.INVALID_REQUEST, id, stepId, null);
        service.updateStep(id, stepId, update(PathStepStatus.COMPLETED));
        assertUpdateError(ErrorCode.LEARNING_PATH_STEP_COMPLETED, id, stepId, PathStepStatus.IN_PROGRESS);
    }

    @Test
    void cannotUpdateAnotherLearnersPlanOrStepFromAnotherPlan() {
        var first = createSavedPlan();
        var other = createSavedPlan();
        assertUpdateError(ErrorCode.LEARNING_PATH_STEP_NOT_FOUND, first.plan().planId(),
                other.steps().getFirst().stepId(), PathStepStatus.COMPLETED);
        login("owner@test.local");
        assertUpdateError(ErrorCode.LEARNING_PATH_NOT_FOUND, first.plan().planId(),
                first.steps().getFirst().stepId(), PathStepStatus.COMPLETED);
        login(learnerEmail);
        removeEnrollment();
        assertUpdateError(ErrorCode.ACCESS_DENIED, first.plan().planId(),
                first.steps().getFirst().stepId(), PathStepStatus.COMPLETED);
        SecurityContextHolder.clearContext();
        assertUpdateError(ErrorCode.UNAUTHORIZED, first.plan().planId(),
                first.steps().getFirst().stepId(), PathStepStatus.COMPLETED);
    }

    @Test
    void archivedPlansAndLegacyPlansWithoutStagesCannotBeUpdated() {
        var archived = createSavedPlan();
        tx.executeWithoutResult(status -> plans.findById(archived.plan().planId()).orElseThrow()
                .setStatus(LearningPathStatus.ARCHIVED));
        assertTrue(service.get(archived.plan().planId()).steps().stream().noneMatch(s -> s.isUnlocked()));
        assertUpdateError(ErrorCode.LEARNING_PATH_NOT_ACTIVE, archived.plan().planId(),
                archived.steps().getFirst().stepId(), PathStepStatus.COMPLETED);

        var legacy = createSavedPlan();
        tx.executeWithoutResult(status -> plans.findById(legacy.plan().planId()).orElseThrow()
                .getSteps().getLast().setStageLevel(null));
        assertTrue(service.get(legacy.plan().planId()).steps().stream().noneMatch(s -> s.isUnlocked()));
        assertUpdateError(ErrorCode.LEARNING_PATH_STAGES_UNAVAILABLE, legacy.plan().planId(),
                legacy.steps().getFirst().stepId(), PathStepStatus.COMPLETED);
    }

    @Test
    void failedUpdateRollsBackBothFinalStepAndPlanCompletion() {
        var plan = createSavedPlan();
        Long id = plan.plan().planId();
        service.updateStep(id, plan.steps().get(0).stepId(), update(PathStepStatus.COMPLETED));
        service.updateStep(id, plan.steps().get(1).stepId(), update(PathStepStatus.COMPLETED));
        doAnswer(invocation -> {
            realPlans.flush();
            throw new IllegalStateException("Simulated failure after update flush");
        }).when(plans).flush();
        assertThrows(IllegalStateException.class, () -> service.updateStep(id,
                plan.steps().get(2).stepId(), update(PathStepStatus.COMPLETED)));
        var loaded = service.get(id);
        assertEquals(LearningPathStatus.ACTIVE, loaded.plan().status());
        assertNull(loaded.plan().completedAt());
        assertEquals(PathStepStatus.PENDING, loaded.steps().get(2).status());
        assertNull(loaded.steps().get(2).completedAt());
    }

    @Test
    void concurrentCompletionsDoNotMissFinalPlanCompletion() throws Exception {
        var plan = createSavedPlan();
        Long id = plan.plan().planId();
        tx.executeWithoutResult(status -> plans.findById(id).orElseThrow().getSteps()
                .forEach(step -> step.setStageLevel(0)));
        service.updateStep(id, plan.steps().get(0).stepId(), update(PathStepStatus.COMPLETED));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i : List.of(1, 2)) {
                tasks.add(executor.submit(() -> {
                    login(learnerEmail);
                    try {
                        ready.countDown();
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        service.updateStep(id, plan.steps().get(i).stepId(), update(PathStepStatus.COMPLETED));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        var loaded = service.get(id);
        assertEquals(3, loaded.plan().completedSteps());
        assertEquals(LearningPathStatus.COMPLETED, loaded.plan().status());
        assertNotNull(loaded.plan().completedAt());
    }

    @Test
    void persistsMasteryHistoryAndUsesDefaultsWithoutOverridingExplicitEmptyList() {
        var plan = createSavedPlan();
        tx.executeWithoutResult(t -> {
            assertEquals(3, mastery.list(courseId).size());
            assertTrue(mastery.list(courseId).stream().allMatch(m -> m.status() == MasteryStatus.NOT_STARTED));
            assertEquals(1, mastery.history(courseId, "C2").size());
            assertNull(mastery.history(courseId, "C2").get(0).oldStatus());
        });
        var step = plan.steps().stream().filter(s -> s.conceptId().equals("C2")).findFirst().orElseThrow();
        service.updateStep(plan.plan().planId(), step.stepId(), update(PathStepStatus.COMPLETED));
        tx.executeWithoutResult(t -> {
            assertEquals(List.of("C2"), mastery.knownConcepts(courseId));
            assertEquals(2, mastery.history(courseId, "C2").size());
        });
        service.updateStep(plan.plan().planId(), step.stepId(), update(PathStepStatus.COMPLETED));
        doAnswer(call -> {
            assertEquals(List.of("C2"), ((fu.coreservice.dto.ai.AiLearningPathRequest) call.getArgument(0)).getKnownConceptIds());
            return generated(LearningPathMode.COURSE);
        }).when(ai).createLearningPathPlan(any());
        service.create(courseId, request(LearningPathMode.COURSE, null));
        doAnswer(call -> {
            assertEquals(List.of(), ((fu.coreservice.dto.ai.AiLearningPathRequest) call.getArgument(0)).getKnownConceptIds());
            return generated(LearningPathMode.COURSE);
        }).when(ai).createLearningPathPlan(any());
        service.create(courseId, new CreateLearningPathRequest("Explicit", null, null, List.of(), null, null, null));
        tx.executeWithoutResult(t -> {
            assertEquals(List.of("C2"), mastery.knownConcepts(courseId));
            assertEquals(2, mastery.history(courseId, "C2").size());
        });
    }
    private LearningPathDetail createSavedPlan() {
        when(ai.createLearningPathPlan(any())).thenReturn(generated(LearningPathMode.COURSE));
        return service.create(courseId, request(LearningPathMode.COURSE, null));
    }

    private UpdateLearningPathStepRequest update(PathStepStatus status) {
        return new UpdateLearningPathStepRequest(status);
    }

    private void assertUpdateError(ErrorCode expected, Long planId, Long stepId, PathStepStatus status) {
        assertEquals(expected, assertThrows(AppException.class,
                () -> service.updateStep(planId, stepId, update(status))).getErrorCode());
    }

    private CreateLearningPathRequest request(LearningPathMode mode, Long version) {
        return new CreateLearningPathRequest("My plan", mode == LearningPathMode.DOCUMENT ? documentId : null,
                mode == LearningPathMode.GOAL ? List.of("C5", "C5") : null,
                null, null, null, version);
    }

    private Map<String, Object> generated(LearningPathMode mode) {
        return Map.of("course_id", courseId.toString(), "graph_version", 3,
                "mode", mode.name().toLowerCase(Locale.ROOT), "linear_order", List.of("C2", "C1", "C5"),
                "stages", List.of(Map.of("level", 0, "steps", List.of(step("C1", mode), step("C2", mode))),
                        Map.of("level", 1, "steps", List.of(step("C5", mode)))),
                "warnings", List.of("Example warning"));
    }

    private Map<String, Object> step(String id, LearningPathMode mode) {
        var result = new HashMap<String, Object>();
        result.put("concept_id", id);
        result.put("name", "Concept " + id);
        result.put("outside_document", mode == LearningPathMode.GOAL ? null : mode == LearningPathMode.DOCUMENT);
        return result;
    }

    private User user(String name) {
        return User.builder().email(name + "@test.local").username(name).passwordHash("test")
                .role(UserRole.values()[0]).build();
    }

    private void login(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private void removeEnrollment() {
        tx.executeWithoutResult(status -> enrollments.deleteAll());
    }
}
