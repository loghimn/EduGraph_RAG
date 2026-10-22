package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.learningpath.LearningPathDetail;
import fu.coreservice.dto.learningpath.LearningPathSummary;
import fu.coreservice.dto.learningpath.CreateLearningPathRequest;
import fu.coreservice.entity.LearningPathStatus;
import fu.coreservice.service.LearningPathService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequiredArgsConstructor
public class LearningPathController {
    private final LearningPathService service;

    @PostMapping("/api/courses/{courseId}/learning-paths")
    public ResponseEntity<ApiResponse<LearningPathDetail>> create(
            @PathVariable Long courseId, @Valid @RequestBody CreateLearningPathRequest request) {
        LearningPathDetail detail = service.create(courseId, request);
        return ResponseEntity.created(URI.create("/api/learning-paths/" + detail.plan().planId()))
                .body(ApiResponse.<LearningPathDetail>builder().success(true)
                        .message("Learning path created successfully").data(detail).build());
    }

    @GetMapping("/api/courses/{courseId}/learning-paths")
    public ResponseEntity<ApiResponse<PageResponse<LearningPathSummary>>> list(
            @PathVariable Long courseId, @RequestParam(required = false) LearningPathStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.<PageResponse<LearningPathSummary>>builder().success(true)
                .message("Learning paths retrieved successfully")
                .data(service.list(courseId, status, page, size)).build());
    }

    @GetMapping("/api/learning-paths/{planId}")
    public ResponseEntity<ApiResponse<LearningPathDetail>> get(@PathVariable Long planId) {
        return ResponseEntity.ok(ApiResponse.<LearningPathDetail>builder().success(true)
                .message("Learning path retrieved successfully").data(service.get(planId)).build());
    }
}
