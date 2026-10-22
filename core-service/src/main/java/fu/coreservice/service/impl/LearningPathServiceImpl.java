package fu.coreservice.service.impl;

import fu.coreservice.service.LearningPathService;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.ai.AiLearningPathRequest;
import fu.coreservice.dto.ai.AiLearningPathResult;
import fu.coreservice.dto.learningpath.LearningPathDetail;
import fu.coreservice.dto.learningpath.LearningPathSummary;
import fu.coreservice.dto.learningpath.CreateLearningPathRequest;
import fu.coreservice.dto.learningpath.UpdateLearningPathStepRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LearningPathServiceImpl implements LearningPathService {
    private final AiCoreClient aiCoreClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository enrollmentRepository;
    private final DocumentRepository documentRepository;
    private final LearningPathPlanRepository planRepository;
    private final LearningPathStepRepository stepRepository;
    private final PlatformTransactionManager transactionManager;

    // No database transaction is held while waiting for Python.
    @Override
    public LearningPathDetail create(Long courseId, CreateLearningPathRequest request) {
        Access access = requireAccess(courseId, currentUser());
        List<String> goals = distinct(request.goalConceptIds());
        if (request.documentId() != null && !goals.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        requireDocument(courseId, request.documentId());
        LearningPathMode mode = !goals.isEmpty() ? LearningPathMode.GOAL
                : request.documentId() != null ? LearningPathMode.DOCUMENT : LearningPathMode.COURSE;

        var raw = aiCoreClient.createLearningPathPlan(AiLearningPathRequest.builder()
                .courseId(courseId.toString()).userId(access.user().getUserId().toString())
                .documentId(request.documentId() == null ? null : request.documentId().toString())
                .goalConceptIds(goals).knownConceptIds(distinct(request.knownConceptIds()))
                .includeExternalPrereqs(!Boolean.FALSE.equals(request.includeExternalPrereqs()))
                .assumeKnownPrerequisites(!Boolean.FALSE.equals(request.assumeKnownPrerequisites()))
                .build());
        AiLearningPathResult generated;
        try {
            generated = objectMapper.convertValue(raw, AiLearningPathResult.class);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.AI_CORE_ERROR);
        }
        Map<String, LocatedStep> steps = validateGenerated(generated, courseId, mode);
        if (request.expectedGraphVersion() != null
                && !request.expectedGraphVersion().equals(generated.graphVersion())) {
            throw new AppException(ErrorCode.LEARNING_PATH_GRAPH_CHANGED);
        }
        if (generated.linearOrder().isEmpty()) throw new AppException(ErrorCode.LEARNING_PATH_EMPTY);

        return new TransactionTemplate(transactionManager).execute(transaction -> {
            // Recheck access and document scope after the external call.
            Access current = requireAccess(courseId, currentUser());
            LearningPathPlan plan = LearningPathPlan.builder()
                    .learner(current.user()).course(current.course()).mode(mode)
                    .document(requireDocument(courseId, request.documentId()))
                    .graphVersion(generated.graphVersion())
                    .planName(request.planName() == null ? "Learning path: " + mode.name()
                            : request.planName().strip()).build();
            for (String goal : goals) {
                plan.getGoals().add(LearningPathGoal.builder().plan(plan).neo4jConceptId(goal).build());
            }
            int order = 1;
            for (String conceptId : generated.linearOrder()) {
                LocatedStep located = steps.get(conceptId);
                String label = located.step().name();
                if (label == null || label.isBlank()) label = conceptId;
                plan.getSteps().add(LearningPathStep.builder().plan(plan).neo4jConceptId(conceptId)
                        .conceptLabel(label.substring(0, Math.min(label.length(), 255)))
                        .stepOrder(order++).stageLevel(located.level())
                        .outsideDocument(located.step().outsideDocument()).build());
            }
            LearningPathPlan saved = planRepository.saveAndFlush(plan);
            return detail(saved, generated.warnings() == null ? List.of()
                    : generated.warnings().stream().filter(Objects::nonNull).toList());
        });
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<LearningPathSummary> list(Long courseId, LearningPathStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new AppException(ErrorCode.INVALID_PARAMETER);
        Access access = requireAccess(courseId, currentUser());
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "generatedAt", "planId"));
        Page<LearningPathPlan> plans = status == null
                ? planRepository.findByLearnerAndCourse(access.user(), access.course(), pageable)
                : planRepository.findByLearnerAndCourseAndStatus(access.user(), access.course(), status, pageable);
        List<Long> ids = plans.getContent().stream().map(LearningPathPlan::getPlanId).toList();
        Map<Long, LearningPathStepRepository.PlanStepCounts> counts = ids.isEmpty() ? Map.of()
                : stepRepository.countStepsForPlans(ids, PathStepStatus.COMPLETED).stream()
                .collect(Collectors.toMap(LearningPathStepRepository.PlanStepCounts::getPlanId, Function.identity()));
        return PageResponse.<LearningPathSummary>builder()
                .content(plans.getContent().stream().map(plan -> {
                    var count = counts.get(plan.getPlanId());
                    return summary(plan, count == null ? 0 : count.getTotal(),
                            count == null ? 0 : count.getCompleted());
                }).toList())
                .page(plans.getNumber()).size(plans.getSize()).totalElements(plans.getTotalElements())
                .totalPages(plans.getTotalPages()).first(plans.isFirst()).last(plans.isLast()).build();
    }

    @Transactional(readOnly = true)
    @Override
    public LearningPathDetail get(Long planId) {
        LearningPathPlan plan = planRepository.findByPlanIdAndLearner(planId, currentUser())
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_PATH_NOT_FOUND));
        requireAccess(plan.getCourse().getCourseId(), plan.getLearner());
        return detail(plan, List.of());
    }

    @Transactional
    @Override
    public LearningPathDetail updateStep(Long planId, Long stepId, UpdateLearningPathStepRequest request) {
        User learner = currentUser();
        if (request == null || request.status() == null || request.status() == PathStepStatus.SKIPPED) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        LearningPathPlan plan = planRepository.findOwnedForUpdate(planId, learner)
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_PATH_NOT_FOUND));
        requireAccess(plan.getCourse().getCourseId(), learner);
        LearningPathStep step = plan.getSteps().stream().filter(s -> s.getStepId().equals(stepId)).findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_PATH_STEP_NOT_FOUND));
        if (plan.getStatus() == LearningPathStatus.ARCHIVED) {
            throw new AppException(ErrorCode.LEARNING_PATH_NOT_ACTIVE);
        }
        // Retrying a completion is safe and preserves the original timestamp.
        if (step.getStatus() == PathStepStatus.COMPLETED) {
            if (request.status() != PathStepStatus.COMPLETED) {
                throw new AppException(ErrorCode.LEARNING_PATH_STEP_COMPLETED);
            }
            return detail(plan, List.of());
        }
        if (plan.getStatus() != LearningPathStatus.ACTIVE) {
            throw new AppException(ErrorCode.LEARNING_PATH_NOT_ACTIVE);
        }
        if (!hasValidStages(plan)) throw new AppException(ErrorCode.LEARNING_PATH_STAGES_UNAVAILABLE);
        if (!isUnlocked(plan, step)) throw new AppException(ErrorCode.LEARNING_PATH_STEP_LOCKED);

        step.setStatus(request.status());
        // Match database timestamp precision, including the response before reload.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        step.setCompletedAt(request.status() == PathStepStatus.COMPLETED ? now : null);
        if (plan.getSteps().stream().allMatch(s -> s.getStatus() == PathStepStatus.COMPLETED)) {
            plan.setStatus(LearningPathStatus.COMPLETED);
            plan.setCompletedAt(now);
        }
        planRepository.flush();
        return detail(plan, List.of());
    }

    private boolean hasValidStages(LearningPathPlan plan) {
        return plan.getSteps().stream().allMatch(s -> s.getStageLevel() != null && s.getStageLevel() >= 0);
    }

    private boolean isUnlocked(LearningPathPlan plan, LearningPathStep step) {
        return plan.getStatus() != LearningPathStatus.ARCHIVED && hasValidStages(plan)
                && plan.getSteps().stream().noneMatch(s -> s.getStageLevel() < step.getStageLevel()
                        && s.getStatus() != PathStepStatus.COMPLETED);
    }

    private LearningPathDetail detail(LearningPathPlan plan, List<String> warnings) {
        var orderedSteps = plan.getSteps().stream()
                .sorted(Comparator.comparing(LearningPathStep::getStepOrder)).toList();
        long completed = orderedSteps.stream().filter(s -> s.getStatus() == PathStepStatus.COMPLETED).count();
        return new LearningPathDetail(summary(plan, orderedSteps.size(), completed),
                plan.getGoals().stream().map(LearningPathGoal::getNeo4jConceptId).sorted().toList(),
                orderedSteps.stream().map(s -> new LearningPathDetail.Step(s.getStepId(), s.getNeo4jConceptId(),
                        s.getConceptLabel(), s.getStepOrder(), s.getStageLevel(), s.getOutsideDocument(),
                        s.getStatus(), s.getCompletedAt(), isUnlocked(plan, s))).toList(), warnings);
    }

    private LearningPathSummary summary(LearningPathPlan plan, long total, long completed) {
        return new LearningPathSummary(plan.getPlanId(), plan.getCourse().getCourseId(), plan.getPlanName(),
                plan.getMode(), plan.getStatus(), plan.getDocument() == null ? null : plan.getDocument().getDocumentId(),
                plan.getGraphVersion(), plan.getGeneratedAt(), plan.getCompletedAt(), total, completed,
                total == 0 ? 0 : Math.round(completed * 10000.0 / total) / 100.0);
    }

    private User currentUser() {
        return userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private Access requireAccess(Long courseId, User user) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        if (!course.getUser().getUserId().equals(user.getUserId())
                && !enrollmentRepository.existsByCourse_CourseIdAndUser(courseId, user)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        return new Access(user, course);
    }

    private Document requireDocument(Long courseId, Long documentId) {
        if (documentId == null) return null;
        return documentRepository.findByDocumentIdAndCourseCourseId(documentId, courseId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private List<String> distinct(List<String> ids) {
        return ids == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private Map<String, LocatedStep> validateGenerated(AiLearningPathResult result, Long courseId,
                                                      LearningPathMode mode) {
        if (result == null || !courseId.toString().equals(result.courseId())
                || result.graphVersion() == null || result.graphVersion() < 0
                || !mode.name().equalsIgnoreCase(result.mode())
                || result.linearOrder() == null || result.stages() == null) {
            throw new AppException(ErrorCode.AI_CORE_ERROR);
        }
        Map<String, LocatedStep> steps = new HashMap<>();
        Set<Integer> levels = new HashSet<>();
        for (var stage : result.stages()) {
            if (stage == null || stage.level() == null || stage.level() < 0
                    || !levels.add(stage.level()) || stage.steps() == null) {
                throw new AppException(ErrorCode.AI_CORE_ERROR);
            }
            for (var step : stage.steps()) {
                if (step == null || step.conceptId() == null || step.conceptId().isBlank()
                        || step.conceptId().length() > 100
                        || steps.putIfAbsent(step.conceptId(), new LocatedStep(stage.level(), step)) != null) {
                    throw new AppException(ErrorCode.AI_CORE_ERROR);
                }
            }
        }
        if (result.linearOrder().size() != steps.size()
                || !new HashSet<>(result.linearOrder()).equals(steps.keySet())) {
            throw new AppException(ErrorCode.AI_CORE_ERROR);
        }
        return steps;
    }

    private record Access(User user, Course course) {}
    private record LocatedStep(Integer level, AiLearningPathResult.Step step) {}
}
