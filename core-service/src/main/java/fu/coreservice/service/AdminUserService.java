package fu.coreservice.service;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.admin.*;
public interface AdminUserService {
    PageResponse<AdminUserResponse> list(AdminUserFilter filter);
    AdminUserResponse get(Long userId);
    AdminUserResponse create(CreateAdminUserRequest request);
    AdminUserResponse update(Long userId, UpdateAdminUserRequest request);
}
