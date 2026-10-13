package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiChatRequest;
import fu.coreservice.dto.ai.AiLearningPathRequest;
import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.dto.request.LearningPathPlanRequest;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.ClientHttpResponse;

import java.util.Map;

/**
 * Proxy logic cho các endpoint AI (chat SSE, learning-path) — frontend chỉ gọi qua
 * Gateway -> Core Service -> Python. Quyền truy cập được kiểm tại Java (chủ khóa học)
 * trước khi chạm tới Python để tránh call thừa.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiRagService {

    private final AiCoreClient aiCoreClient;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;

    public ClientHttpResponse openChatStream(Long courseId, ChatRequest request) {
        User owner = requireCourseOwner(courseId);

        AiChatRequest aiRequest = AiChatRequest.builder()
                .courseId(String.valueOf(courseId))
                .userId(String.valueOf(owner.getUserId()))
                .message(request.getMessage())
                .conceptIds(request.getConceptIds())
                .build();

        return aiCoreClient.openChatStream(aiRequest);
    }

    public ResponseEntity<ApiResponse<Map<String, Object>>> planLearningPath(Long courseId,
                                                                             LearningPathPlanRequest request) {
        User owner = requireCourseOwner(courseId);

        AiLearningPathRequest aiRequest = AiLearningPathRequest.builder()
                .courseId(String.valueOf(courseId))
                .userId(String.valueOf(owner.getUserId()))
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

    private User requireCourseOwner(Long courseId) {
        String email = SecurityUtils.getCurrentUserEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        if (!course.getUser().getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }
        return user;
    }
}
