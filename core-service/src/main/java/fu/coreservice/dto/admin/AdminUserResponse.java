package fu.coreservice.dto.admin;

import fu.coreservice.entity.*;
import java.time.LocalDateTime;

/** Explicit allowlist: credentials, OTPs and session tokens never leave this service. */
public record AdminUserResponse(Long userId, String email, String username, UserRole role,
        Boolean isActive, ApprovalStatus approvalStatus, Long reviewedBy, LocalDateTime reviewedAt,
        String rejectionReason, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getUserId(), user.getEmail(), user.getUsername(), user.getRole(),
                user.getIsActive(), user.getApprovalStatus(), user.getReviewedBy(), user.getReviewedAt(),
                user.getRejectionReason(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
