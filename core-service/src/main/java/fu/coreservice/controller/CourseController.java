package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.course.CourseCreateRequest;
import fu.coreservice.dto.course.CourseResponse;
import fu.coreservice.dto.course.CourseUpdateRequest;
import fu.coreservice.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @Operation(
            summary = "Create a new course",
            description = "Creates a new course for the currently authenticated user. " +
                    "The authenticated user will be assigned as the course owner."
    )
    @PreAuthorize("hasRole('INSTRUCTOR')")
    @PostMapping
    public ResponseEntity<ApiResponse<CourseResponse>> createCourse(@Valid @RequestBody CourseCreateRequest request) {
        CourseResponse response = courseService.createCourse(request);
        ApiResponse<CourseResponse> apiResponse = ApiResponse.<CourseResponse>builder()
                .success(true)
                .message("Course created successfully")
                .data(response)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(apiResponse);
    }

    @Operation(
            summary = "Update a course",
            description = "Updates an existing course owned by the currently authenticated user. " +
                    "Only the course owner can update the course."
    )
    @PutMapping("/{courseId}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<CourseResponse>> updateCourse(@PathVariable Long courseId, @Valid @RequestBody CourseUpdateRequest request) {
        CourseResponse response = courseService.updateCourse(courseId, request);

        ApiResponse<CourseResponse> apiResponse = ApiResponse.<CourseResponse>builder()
                .success(true)
                .message("Course updated successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(apiResponse);
    }

    @Operation(
            summary = "Delete a course",
            description = "Deletes a course owned by the currently authenticated user. " +
                    "Only the course owner can delete the course."
    )
    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<Void>> deleteCourse(@PathVariable Long courseId) {

        courseService.deleteCourse(courseId);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Course deleted successfully")
                .data(null)
                .build()
        );
    }

    @Operation(
            summary = "Get my courses",
            description = "Retrieves a paginated list of courses created by the currently authenticated instructor."
    )
    @GetMapping("/my")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<PageResponse<CourseResponse>>> getCourses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) Boolean isPublic
    ) {

        PageResponse<CourseResponse> response = courseService.getMyCourses(page, size, sortBy, sortDirection, isPublic);

        return ResponseEntity.ok(
                ApiResponse.<PageResponse<CourseResponse>>builder()
                        .success(true)
                        .message("Courses retrieved successfully")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Get my course detail",
            description = "Retrieves the details of a course owned by the currently authenticated user."
    )
    @GetMapping("/my/{courseId}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<CourseResponse>> getCourseDetail(@PathVariable Long courseId) {

        CourseResponse response = courseService.getCourseDetail(courseId);

        return ResponseEntity.ok(ApiResponse.<CourseResponse>builder()
                .success(true)
                .message("Course retrieved successfully")
                .data(response)
                .build()
        );
    }

    @Operation(
            summary = "Search public courses",
            description = "Retrieves a paginated list of publicly available courses. " +
                    "Courses can be searched by keyword and filtered by domain and language."
    )
    @GetMapping("/public")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponse<PageResponse<CourseResponse>>> searchPublicCourses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection
    ) {

        PageResponse<CourseResponse> response = courseService.searchPublicCourses(keyword, domain, language, page, size, sortBy, sortDirection);

        return ResponseEntity.ok(
                ApiResponse.<PageResponse<CourseResponse>>builder()
                        .success(true)
                        .message("Public courses retrieved successfully")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Get public course detail",
            description = "Retrieves the details of a publicly available course. " +
                    "Only courses marked as public can be accessed through this endpoint."
    )
    @GetMapping("/public/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponse<CourseResponse>> getPublicCourseDetail(@PathVariable Long courseId) {

        CourseResponse response = courseService.getPublicCourseDetail(courseId);

        return ResponseEntity.ok(ApiResponse.<CourseResponse>builder()
                .success(true)
                .message("Course retrieved successfully")
                .data(response)
                .build()
        );
    }

}
