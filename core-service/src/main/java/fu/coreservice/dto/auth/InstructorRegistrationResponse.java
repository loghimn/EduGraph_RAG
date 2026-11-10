package fu.coreservice.dto.auth;

import fu.coreservice.entity.ApprovalStatus;
import fu.coreservice.entity.User;
import java.time.LocalDateTime;

public record InstructorRegistrationResponse(Long userId, String email, String username,
        ApprovalStatus approvalStatus, Long reviewedBy, LocalDateTime reviewedAt,
        String rejectionReason, LocalDateTime createdAt) {
    public static InstructorRegistrationResponse from(User user) {
        return new InstructorRegistrationResponse(user.getUserId(), user.getEmail(), user.getUsername(),
                user.getApprovalStatus(), user.getReviewedBy(), user.getReviewedAt(),
                user.getRejectionReason(), user.getCreatedAt());
    }
}
