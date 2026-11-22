package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.ai.CourseAiSettings;
import fu.coreservice.service.CourseAiSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses/{courseId}/ai-settings")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
public class CourseAiSettingsController {
    private final CourseAiSettingsService service;
    @GetMapping
    public ApiResponse<CourseAiSettings> get(@PathVariable Long courseId) {
        return ApiResponse.<CourseAiSettings>builder().success(true).data(service.get(courseId)).build();
    }
    @PutMapping
    public ApiResponse<CourseAiSettings> save(@PathVariable Long courseId, @Valid @RequestBody CourseAiSettings request) {
        return ApiResponse.<CourseAiSettings>builder().success(true).data(service.save(courseId, request)).build();
    }
}
