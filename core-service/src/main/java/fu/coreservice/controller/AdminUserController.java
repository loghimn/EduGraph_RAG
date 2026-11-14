package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.admin.*;
import fu.coreservice.entity.*;
import fu.coreservice.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserService users;

    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) ApprovalStatus approvalStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(users.list(new AdminUserFilter(keyword, role, active, approvalStatus, page, size)));
    }
    @GetMapping("/{userId}")
    public ApiResponse<AdminUserResponse> get(@PathVariable Long userId) { return ok(users.get(userId)); }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<AdminUserResponse> create(@Valid @RequestBody CreateAdminUserRequest request) {
        return ok(users.create(request));
    }
    @PatchMapping("/{userId}")
    public ApiResponse<AdminUserResponse> update(@PathVariable Long userId, @Valid @RequestBody UpdateAdminUserRequest request) {
        return ok(users.update(userId, request));
    }
    private <T> ApiResponse<T> ok(T data) { return ApiResponse.<T>builder().success(true).data(data).build(); }
}
