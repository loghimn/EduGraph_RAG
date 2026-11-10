package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.activitylog.ActivityLogFilter;
import fu.coreservice.dto.activitylog.ActivityLogResponse;
import fu.coreservice.dto.auth.InstructorRegistrationResponse;
import fu.coreservice.dto.auth.RejectInstructorRequest;
import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;
import fu.coreservice.entity.ApprovalStatus;
import fu.coreservice.service.ActivityLogService;
import fu.coreservice.service.InstructorApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class AdminController {
    private final ActivityLogService activityLogService;
    private final InstructorApprovalService instructorApprovalService;

    @GetMapping("/activity-logs")
    @Operation(summary = "List activity alerts", description = "Admin only. Newest first; from is inclusive and to is exclusive.")
    public ApiResponse<PageResponse<ActivityLogResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) ActivityLogStatus status,
            @RequestParam(required = false) ActivityActorType actorType,
            @RequestParam(required = false) Long actorId) {
        return ApiResponse.<PageResponse<ActivityLogResponse>>builder().success(true)
                .message("Activity logs retrieved successfully")
                .data(activityLogService.listLogs(new ActivityLogFilter(page, size, from, to,
                        action, status, actorType, actorId))).build();
    }

    @GetMapping("/activity-logs/{logId}")
    @Operation(summary = "Get an activity alert", description = "Admin only.")
    public ApiResponse<ActivityLogResponse> get(@PathVariable Long logId) {
        return ApiResponse.<ActivityLogResponse>builder().success(true)
                .message("Activity log retrieved successfully").data(activityLogService.getLog(logId)).build();
    }

    @GetMapping("/instructor-registrations")
    @Operation(summary = "List instructor registrations", description = "Admin only. Filter by approval status.")
    public ApiResponse<PageResponse<InstructorRegistrationResponse>> listInstructorRegistrations(
            @RequestParam(defaultValue = "PENDING") ApprovalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.<PageResponse<InstructorRegistrationResponse>>builder().success(true)
                .message("Instructor registrations retrieved successfully")
                .data(instructorApprovalService.list(status, page, size)).build();
    }

    @PostMapping("/instructor-registrations/{userId}/approve")
    @Operation(summary = "Approve an instructor registration", description = "Admin only.")
    public ApiResponse<InstructorRegistrationResponse> approveInstructorRegistration(@PathVariable Long userId,
            @AuthenticationPrincipal UserDetails admin) {
        return ApiResponse.<InstructorRegistrationResponse>builder().success(true)
                .message("Instructor registration approved")
                .data(instructorApprovalService.review(userId, admin.getUsername(), true, null)).build();
    }

    @PostMapping("/instructor-registrations/{userId}/reject")
    @Operation(summary = "Reject an instructor registration", description = "Admin only.")
    public ApiResponse<InstructorRegistrationResponse> rejectInstructorRegistration(@PathVariable Long userId,
            @AuthenticationPrincipal UserDetails admin,
            @Valid @RequestBody(required = false) RejectInstructorRequest request) {
        return ApiResponse.<InstructorRegistrationResponse>builder().success(true)
                .message("Instructor registration rejected")
                .data(instructorApprovalService.review(userId, admin.getUsername(), false,
                        request == null ? null : request.reason())).build();
    }
}
