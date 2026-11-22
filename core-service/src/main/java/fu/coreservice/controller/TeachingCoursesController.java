package fu.coreservice.controller;

import fu.coreservice.dto.*;
import fu.coreservice.service.CourseAiSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/teaching-courses")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
public class TeachingCoursesController {
    private final CourseAiSettingsService service;
    @GetMapping
    public ApiResponse<PageResponse<CourseAiSettingsService.CourseOption>> list(@RequestParam(defaultValue = "0") int page) {
        return ApiResponse.<PageResponse<CourseAiSettingsService.CourseOption>>builder().success(true).data(service.listCourses(page)).build();
    }
}
