package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.auth.InstructorRegistrationResponse;
import fu.coreservice.entity.ApprovalStatus;

public interface InstructorApprovalService {
    PageResponse<InstructorRegistrationResponse> list(ApprovalStatus status, int page, int size);

    InstructorRegistrationResponse review(Long userId, String adminEmail, boolean approve, String reason);
}
