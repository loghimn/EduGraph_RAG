package fu.coreservice.controller;
import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.admin.AdminDashboardResponse;
import fu.coreservice.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminDashboardService dashboard;
    @GetMapping
    public ApiResponse<AdminDashboardResponse> overview() {
        return ApiResponse.<AdminDashboardResponse>builder().success(true).data(dashboard.overview()).build();
    }
}
