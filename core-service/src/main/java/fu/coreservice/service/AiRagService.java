package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiLearningPathRequest;
import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.learningpath.LearningPathPlanRequest;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.CourseEnrollmentRepository;
import fu.coreservice.repository.DocumentRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowledge graph and learning-path operations via the internal AI core.
 * Session-based chat is handled by ChatMessageService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiRagService {

    private final AiCoreClient aiCoreClient;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final DocumentRepository documentRepository;

    public ResponseEntity<ApiResponse<Map<String, Object>>> getKnowledgeGraph(Long courseId) {
        requireCourseAccess(courseId);
        try {
            Map<String, Object> graph = aiCoreClient.getKnowledgeGraph(courseId);
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                    .success(true)
                    .message("Knowledge graph retrieved successfully")
                    .data(graph)
                    .build());
        } catch (AiCoreException e) {
            return relayAiCoreError(e, courseId, "Knowledge graph retrieval failed");
        }
    }

    public ResponseEntity<ApiResponse<Map<String, Object>>> getKnowledgeGraphConcept(Long courseId,
                                                                                     String conceptId) {
        requireCourseAccess(courseId);
        try {
            Map<String, Object> concept = aiCoreClient.getKnowledgeGraphConcept(courseId, conceptId);
            enrichSourceFileNames(concept, courseId);
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                    .success(true)
                    .message("Knowledge graph concept retrieved successfully")
                    .data(concept)
                    .build());
        } catch (AiCoreException e) {
            return relayAiCoreError(e, courseId, "Knowledge graph concept retrieval failed");
        }
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

    public ResponseEntity<ApiResponse<Map<String, Object>>> planLearningPath(Long courseId,
                                                                             LearningPathPlanRequest request) {
        User learner = requireCourseAccess(courseId);

        AiLearningPathRequest aiRequest = AiLearningPathRequest.builder()
                .courseId(String.valueOf(courseId))
                .userId(String.valueOf(learner.getUserId()))
                .documentId(request.getDocumentId() == null ? null : String.valueOf(request.getDocumentId()))
                .goalConceptIds(request.getGoalConceptIds())
                .knownConceptIds(request.getKnownConceptIds())
                .includeExternalPrereqs(request.getIncludeExternalPrereqs() == null || request.getIncludeExternalPrereqs())
                .assumeKnownPrerequisites(request.getAssumeKnownPrerequisites() == null || request.getAssumeKnownPrerequisites())
                .build();

        try {
            Map<String, Object> plan = aiCoreClient.createLearningPathPlan(aiRequest);
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                    .success(true)
                    .message("Learning path planned successfully")
                    .data(plan)
                    .build());
        } catch (AiCoreException e) {
            // Relay status + payload gốc từ Python (403/404/409 có payload quan trọng cho frontend)
            HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
            if (status == null || status.is2xxSuccessful()) {
                status = HttpStatus.BAD_GATEWAY;
            }
            log.warn("Learning path plan failed. courseId={}, status={}", courseId, e.getHttpStatus());
            return ResponseEntity.status(status).body(ApiResponse.<Map<String, Object>>builder()
                    .success(false)
                    .errorCode("AI_CORE_" + e.getHttpStatus())
                    .message(e.getDetail())
                    .data(e.getBody().isEmpty() ? null : e.getBody())
                    .build());
        }
    }

    private User requireCourseAccess(Long courseId) {
        String email = SecurityUtils.getCurrentUserEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        boolean isOwner = course.getUser().getUserId().equals(user.getUserId());
        boolean isEnrolled = courseEnrollmentRepository.existsByCourse_CourseIdAndUser(courseId, user);
        if (!isOwner && !isEnrolled) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }
        return user;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> relayAiCoreError(
            AiCoreException e, Long courseId, String message) {
        HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
        if (status == null || status.is2xxSuccessful()) {
            status = HttpStatus.BAD_GATEWAY;
        }
        log.warn("{}, courseId={}, status={}", message, courseId, e.getHttpStatus());
        return ResponseEntity.status(status).body(ApiResponse.<Map<String, Object>>builder()
                .success(false)
                .errorCode("AI_CORE_" + e.getHttpStatus())
                .message(e.getDetail())
                .data(e.getBody().isEmpty() ? null : e.getBody())
                .build());
    }
}
