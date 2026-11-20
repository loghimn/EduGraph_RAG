package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.service.CourseLearningAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/courses/{courseId}/learning-analytics")
public class CourseLearningAnalyticsController {
    private final CourseLearningAnalyticsService service;
    @GetMapping
    public ApiResponse<CourseLearningAnalyticsService.Report> report(@PathVariable Long courseId,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.<CourseLearningAnalyticsService.Report>builder().success(true).data(service.report(courseId, days)).build();
    }
}
