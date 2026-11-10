package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.auth.InstructorRegistrationResponse;
import fu.coreservice.entity.ApprovalStatus;
import fu.coreservice.entity.UserRole;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.InstructorApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InstructorApprovalServiceImpl implements InstructorApprovalService {
    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InstructorRegistrationResponse> list(ApprovalStatus status, int page, int size) {
        if (status == null || status == ApprovalStatus.NOT_REQUIRED || page < 0 || size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_PARAMETER);
        }
        var result = users.findByRoleAndApprovalStatus(UserRole.INSTRUCTOR, status,
                PageRequest.of(page, size, Sort.by("createdAt").ascending().and(Sort.by("userId"))));
        return PageResponse.<InstructorRegistrationResponse>builder()
                .content(result.getContent().stream().map(InstructorRegistrationResponse::from).toList())
                .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();
    }

    @Override
    @Transactional
    public InstructorRegistrationResponse review(Long userId, String adminEmail, boolean approve, String reason) {
        var admin = users.findByEmail(adminEmail).orElseThrow(() -> new AppException(ErrorCode.ACCESS_DENIED));
        if (admin.getRole() != UserRole.ADMIN || !Boolean.TRUE.equals(admin.getIsActive())) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (reason != null && reason.length() > 1000) throw new AppException(ErrorCode.INVALID_REQUEST);
        var user = users.findLockedById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() != UserRole.INSTRUCTOR || user.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new AppException(ErrorCode.INSTRUCTOR_REVIEW_NOT_PENDING);
        }
        user.setApprovalStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        user.setReviewedBy(admin.getUserId());
        user.setReviewedAt(LocalDateTime.now());
        user.setRejectionReason(approve || reason == null || reason.isBlank() ? null : reason.trim());
        users.save(user);
        return InstructorRegistrationResponse.from(user);
    }
}
