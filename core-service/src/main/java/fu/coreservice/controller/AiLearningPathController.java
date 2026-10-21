package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.request.LearningPathPlanRequest;
import fu.coreservice.service.AiRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Proxy POST /learning-paths/plan của AI core — frontend chỉ gọi qua Gateway.
 * Chủ khóa học hoặc học viên đã enroll được lập lộ trình cho chính mình.
 * Payload/status từ Python được relay nguyên vẹn (kể cả 409warnings).
 */
@RestController
@RequestMapping("/api/courses/{courseId}/learning-path")
@RequiredArgsConstructor
public class AiLearningPathController {

    private final AiRagService aiRagService;

    @PostMapping("/plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> plan(
            @PathVariable Long courseId,
            @RequestBody(required = false) LearningPathPlanRequest request) {
        return aiRagService.planLearningPath(courseId, request == null ? new LearningPathPlanRequest() : request);
    }
}
