package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.activitylog.ActivityLogFilter;
import fu.coreservice.dto.activitylog.ActivityLogResponse;
import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;
import fu.coreservice.service.ActivityLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/activity-logs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Activity Logs")
public class AdminActivityLogController {
    private final ActivityLogService activityLogService;

    @GetMapping
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

    @GetMapping("/{logId}")
    @Operation(summary = "Get an activity alert", description = "Admin only.")
    public ApiResponse<ActivityLogResponse> get(@PathVariable Long logId) {
        return ApiResponse.<ActivityLogResponse>builder().success(true)
                .message("Activity log retrieved successfully").data(activityLogService.getLog(logId)).build();
    }
}
