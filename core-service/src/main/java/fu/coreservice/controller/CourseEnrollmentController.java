package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.course.CourseResponse;
import fu.coreservice.dto.courseEnrollment.CourseLearnerResponse;
import fu.coreservice.service.CourseEnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses/enrollment")
@SecurityRequirements({
        @SecurityRequirement(name = "bearerAuth"),
        @SecurityRequirement(name = "googleOAuth2")
})
@RequiredArgsConstructor
public class CourseEnrollmentController {

    private final CourseEnrollmentService courseEnrollmentService;

    @Operation(
            summary = "Enroll in a course",
            description = "Enrolls the currently authenticated user in a public course. " +
                    "The user cannot enroll in the same course more than once."
    )
    @PostMapping("/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponse<Void>> enrollCourse(@PathVariable Long courseId) {

        courseEnrollmentService.enrollCourse(courseId);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Enrolled in course successfully")
                .data(null)
                .build()
        );
    }

    @Operation(
            summary = "Get my enrolled courses",
            description = "Retrieves a paginated list of courses in which the currently authenticated user is enrolled."
    )
    @GetMapping("/my")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponse<PageResponse<CourseResponse>>> getMyCoursesEnrollment(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "enrollmentAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection
    ) {

        PageResponse<CourseResponse> response = courseEnrollmentService.getMyCoursesEnrollment(page, size, sortBy, sortDirection);

        return ResponseEntity.ok(ApiResponse.<PageResponse<CourseResponse>>builder()
                        .success(true)
                        .message("Enrolled courses retrieved successfully")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Leave a course",
            description = "Removes the enrollment of the currently authenticated user from a course."
    )
    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApiResponse<Void>> leaveCourse(@PathVariable Long courseId) {

        courseEnrollmentService.leaveCourse(courseId);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Left course successfully")
                .data(null)
                .build()
        );
    }

    @Operation(
            summary = "Get course learners",
            description = "Retrieves a paginated list of learners enrolled in a course. " +
                    "Only the course owner can access the list of learners."
    )
    @GetMapping("/{courseId}/list-learners")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<PageResponse<CourseLearnerResponse>>> getCourseLearners(
            @PathVariable Long courseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "enrollmentAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection
    ) {

        PageResponse<CourseLearnerResponse> response = courseEnrollmentService.getCourseLearners(courseId, page, size, sortBy, sortDirection);

        return ResponseEntity.ok(
                ApiResponse.<PageResponse<CourseLearnerResponse>>builder()
                        .success(true)
                        .message("Course learners retrieved successfully")
                        .data(response)
                        .build()
        );
    }

}
