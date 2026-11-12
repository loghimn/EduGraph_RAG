package fu.coreservice.entity;

import fu.coreservice.listener.PrivilegedChangeListener;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;

import java.time.LocalDateTime;

@Entity
@EntityListeners(PrivilegedChangeListener.class)
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Transient
    private UserRole privilegeSnapshotRole;
    @Transient
    private Boolean privilegeSnapshotActive;
    @Transient
    private ApprovalStatus privilegeSnapshotApprovalStatus;
    @Transient
    private boolean privilegeSnapshotLoaded;

    public void capturePrivilegeSnapshot() {
        privilegeSnapshotRole = role;
        privilegeSnapshotActive = isActive;
        privilegeSnapshotApprovalStatus = approvalStatus;
        privilegeSnapshotLoaded = true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32,
            columnDefinition = "varchar(32) default 'NOT_REQUIRED'")
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.NOT_REQUIRED;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    public boolean isApprovalSatisfied() {
        return role != UserRole.INSTRUCTOR || approvalStatus == ApprovalStatus.APPROVED;
    }

    public void requireCanAuthenticate() {
        if (!Boolean.TRUE.equals(isActive)) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!isApprovalSatisfied()) {
            throw new AppException(approvalStatus == ApprovalStatus.REJECTED
                    ? ErrorCode.INSTRUCTOR_APPROVAL_REJECTED : ErrorCode.INSTRUCTOR_APPROVAL_PENDING);
        }
    }

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "password_reset_otp_hash", length = 255)
    private String passwordResetOtpHash;

    @Column(name = "password_reset_otp_expires_at")
    private LocalDateTime passwordResetOtpExpiresAt;

    @Column(name = "password_reset_otp_attempts")
    @Builder.Default
    private Integer passwordResetOtpAttempts = 0;

    private LocalDateTime resetRequestedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
