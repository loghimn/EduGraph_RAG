package fu.coreservice.service.impl;

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
import fu.coreservice.security.CourseAccessPolicy;
import fu.coreservice.service.ActivityAlertService;
import fu.coreservice.service.AiRagService;
import fu.coreservice.service.LearnerMasteryService;
import fu.coreservice.utils.SecurityUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Knowledge graph and learning-path operations via the internal AI core.
 * Session-based chat is handled by ChatMessageService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiRagServiceImpl implements AiRagService {

    private final AiCoreClient aiCoreClient;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final DocumentRepository documentRepository;
    private final LearnerMasteryService masteryService;
    private final ActivityAlertService alerts;

    @Override
    public Map<String, Object> getKnowledgeGraph(Long courseId) {
        requireCourseAccess(courseId);
        Map<String, Object> graph = aiCoreClient.getKnowledgeGraph(courseId);
        return graph;
    }

    @Override
    public Map<String, Object> getKnowledgeGraphConcept(Long courseId,
                                                                                     String conceptId) {
        requireCourseAccess(courseId);
        Map<String, Object> concept = aiCoreClient.getKnowledgeGraphConcept(courseId, conceptId);
        enrichSourceFileNames(concept, courseId);
        return concept;
    }

    private void enrichSourceFileNames(Map<String, Object> concept, Long courseId) {
        Object rawSources = concept.get("sources");
        if (!(rawSources instanceof List<?> sources)) {
            return;
        }

        List<Map<String, Object>> normalizedSources = new ArrayList<>();
        Set<Long> documentIds = new LinkedHashSet<>();
        for (Object rawSource : sources) {
            if (!(rawSource instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> normalizedSource = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    normalizedSource.put(stringKey, value);
                }
            });
            normalizedSources.add(normalizedSource);

            Object documentId = normalizedSource.get("document_id");
            if (documentId != null) {
                try {
                    documentIds.add(Long.valueOf(documentId.toString()));
                } catch (NumberFormatException e) {
                    log.warn("Ignoring invalid source document id in knowledge graph response. courseId={}, documentId={}",
                            courseId, documentId);
                }
            }
        }

        if (documentIds.isEmpty()) {
            concept.put("sources", normalizedSources);
            return;
        }

        Map<Long, String> fileNamesByDocumentId = new LinkedHashMap<>();
        for (Document document : documentRepository.findByDocumentIdInAndCourseCourseId(
                new ArrayList<>(documentIds), courseId)) {
            fileNamesByDocumentId.put(document.getDocumentId(), document.getFileName());
        }
        for (Map<String, Object> source : normalizedSources) {
            Object documentId = source.get("document_id");
            if (documentId != null) {
                try {
                    String fileName = fileNamesByDocumentId.get(Long.valueOf(documentId.toString()));
                    if (fileName != null) {
                        source.put("filename", fileName);
                    }
                } catch (NumberFormatException e) {
                    // The invalid id was logged when collecting document ids above.
                }
            }
        }
        concept.put("sources", normalizedSources);
    }

    @Override
    public Map<String, Object> planLearningPath(Long courseId,
                                                                             LearningPathPlanRequest request) {
        User learner = requireCourseAccess(courseId);

        AiLearningPathRequest aiRequest = AiLearningPathRequest.builder()
                .courseId(String.valueOf(courseId))
                .userId(String.valueOf(learner.getUserId()))
                .documentId(request.getDocumentId() == null ? null : String.valueOf(request.getDocumentId()))
                .goalConceptIds(request.getGoalConceptIds())
                .knownConceptIds(request.getKnownConceptIds() == null ? masteryService.knownConcepts(courseId) : request.getKnownConceptIds())
                .includeExternalPrereqs(request.getIncludeExternalPrereqs() == null || request.getIncludeExternalPrereqs())
                .assumeKnownPrerequisites(request.getAssumeKnownPrerequisites() == null || request.getAssumeKnownPrerequisites())
                .build();

        Map<String, Object> plan = aiCoreClient.createLearningPathPlan(aiRequest);
        return plan;
    }


    @Override
    public Map<String, Object> createConceptDraft(
            Long courseId, ConceptDraftRequest request) {
        return writeConceptDraft(courseId, null, Map.of(
                "name", request.getName(), "aliases", request.getAliases(), "language", request.getLanguage()));
    }

    @Override
    public Map<String, Object> publishConceptDraft(
            Long courseId, String draftId) {
        return writeConceptDraft(courseId, draftId, Map.of());
    }

    private Map<String, Object> writeConceptDraft(
            Long courseId, String draftId, Map<String, Object> request) {
        User user = requireCourseOwner(courseId);
        Map<String, Object> result = aiCoreClient.postConceptDraft(
                courseId, user.getUserId(), draftId, request);
        return result;
    }


    private User requireCourseOwner(Long courseId) {
        User user = userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        CourseAccessPolicy.requireGraphWriteAccess(course, user);
        return user;
    }

    @Override
    public Map<String, Object> getConceptDeletionImpact(Long courseId, String conceptId) {
        return conceptLifecycle(courseId, conceptId, "impact", null);
    }

    @Override
    public Map<String, Object> getArchivedConcepts(Long courseId) {
        return conceptLifecycle(courseId, null, "archived", null);
    }

    @Override
    public Map<String, Object> archiveConcept(Long courseId, String conceptId, Long expectedVersion) {
        return conceptLifecycle(courseId, conceptId, "archive", expectedVersion);
    }

    @Override
    public Map<String, Object> restoreConcept(Long courseId, String conceptId, Long expectedVersion) {
        return conceptLifecycle(courseId, conceptId, "restore", expectedVersion);
    }

    private Map<String, Object> conceptLifecycle(
            Long courseId, String conceptId, String action, Long expectedVersion) {
        User owner = requireCourseOwner(courseId);
        Map<String, Object> result = aiCoreClient.conceptLifecycle(
                courseId, owner.getUserId(), conceptId, action, expectedVersion);
        if ("archive".equals(action)) alerts.destructiveAfterCommit(owner.getUserId(), "CONCEPT_ARCHIVE", "CONCEPT");
        return result;
    }

    private User requireCourseAccess(Long courseId) {
        String email = SecurityUtils.getCurrentUserEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        CourseAccessPolicy.requireReadAccess(course, user,
                courseEnrollmentRepository.existsByCourse_CourseIdAndUser(courseId, user));
        return user;
    }

}
