package fu.coreservice.dto.admin;
import fu.coreservice.entity.ApprovalStatus;
import fu.coreservice.entity.UserRole;
public record AdminUserFilter(String keyword, UserRole role, Boolean active, ApprovalStatus approvalStatus, int page, int size) {}
