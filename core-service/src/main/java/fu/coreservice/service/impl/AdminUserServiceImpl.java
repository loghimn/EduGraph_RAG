package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.admin.*;
import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.*;
import fu.coreservice.repository.*;
import fu.coreservice.service.*;
import fu.coreservice.utils.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final ActivityLogService logs;

    @Override @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(AdminUserFilter filter) {
        requireAdmin();
        if (filter == null || filter.page() < 0 || filter.size() < 1 || filter.size() > 100
                || (long) filter.page() * filter.size() > Integer.MAX_VALUE
                || filter.keyword() != null && filter.keyword().length() > 100) throw new AppException(ErrorCode.INVALID_PARAMETER);
        Specification<User> specification = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                String keyword = filter.keyword().strip().toLowerCase(Locale.ROOT);
                String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                var text = cb.or(cb.like(cb.lower(root.get("username")), "%" + escaped + "%", '\\'),
                        cb.like(cb.lower(root.get("email")), "%" + escaped + "%", '\\'));
                if (keyword.matches("[0-9]{1,18}")) text = cb.or(text, cb.equal(root.get("userId"), Long.parseLong(keyword)));
                predicates.add(text);
            }
            if (filter.role() != null) predicates.add(cb.equal(root.get("role"), filter.role()));
            if (filter.active() != null) predicates.add(cb.equal(root.get("isActive"), filter.active()));
            if (filter.approvalStatus() != null) predicates.add(cb.equal(root.get("approvalStatus"), filter.approvalStatus()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var page = users.findAll(specification, PageRequest.of(filter.page(), filter.size(), Sort.by(Sort.Direction.DESC, "createdAt", "userId")));
        return PageResponse.<AdminUserResponse>builder().content(page.map(AdminUserResponse::from).getContent())
                .page(page.getNumber()).size(page.getSize()).totalElements(page.getTotalElements()).totalPages(page.getTotalPages())
                .first(page.isFirst()).last(page.isLast()).build();
    }

    @Override @Transactional(readOnly = true)
    public AdminUserResponse get(Long userId) {
        requireAdmin(); validateId(userId);
        return users.findById(userId).map(AdminUserResponse::from).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    @Override @Transactional
    public AdminUserResponse create(CreateAdminUserRequest request) {
        User actor = requireLockedAdmin();
        String email = request.email().strip(), username = request.username().strip();
        if (username.length() < 3 || request.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AppException(ErrorCode.INVALID_REQUEST);
        if (users.existsByEmailIgnoreCase(email)) throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        if (users.existsByUsername(username)) throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        User user = User.builder().email(email).username(username).passwordHash(encoder.encode(request.password()))
                .role(request.role()).isActive(true)
                .approvalStatus(request.role() == UserRole.INSTRUCTOR ? ApprovalStatus.PENDING : ApprovalStatus.NOT_REQUIRED).build();
        save(user);
        audit(actor, "ADMIN_USER_CREATED", "Created user #" + user.getUserId() + "; role=" + user.getRole() + "; approval=" + user.getApprovalStatus());
        return AdminUserResponse.from(user);
    }

    @Override @Transactional
    public AdminUserResponse update(Long userId, UpdateAdminUserRequest request) {
        validateId(userId);
        User actor = requireLockedAdmin();
        User user = users.findLockedById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        // Both supported databases store timestamps to microseconds. Compare at that precision after locking.
        if (request.expectedUpdatedAt() == null || user.getUpdatedAt() == null
                || !user.getUpdatedAt().truncatedTo(ChronoUnit.MICROS).equals(request.expectedUpdatedAt().truncatedTo(ChronoUnit.MICROS)))
            throw new AppException(ErrorCode.ADMIN_USER_CHANGED);
        String email = request.email().strip(), username = request.username().strip();
        if (username.length() < 3 || request.reason() == null || request.reason().strip().length() < 3)
            throw new AppException(ErrorCode.INVALID_REQUEST);
        boolean roleChanged = user.getRole() != request.role();
        boolean activeChanged = !Objects.equals(user.getIsActive(), request.isActive());
        boolean emailChanged = !email.equals(user.getEmail());
        boolean nameChanged = !username.equals(user.getUsername());
        if (user.getUserId().equals(actor.getUserId()) && (roleChanged || activeChanged || emailChanged))
            throw new AppException(ErrorCode.ADMIN_SELF_ACCESS_CHANGE);
        if (users.existsByEmailIgnoreCaseAndUserIdNot(email, userId)) throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        if (users.existsByUsernameAndUserIdNot(username, userId)) throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        if (!roleChanged && !activeChanged && !emailChanged && !nameChanged) return AdminUserResponse.from(user);
        String before = "role=" + user.getRole() + "; active=" + user.getIsActive() + "; approval=" + user.getApprovalStatus();
        user.setEmail(email); user.setUsername(username); user.setRole(request.role()); user.setIsActive(request.isActive());
        if (roleChanged) {
            user.setApprovalStatus(request.role() == UserRole.INSTRUCTOR ? ApprovalStatus.PENDING : ApprovalStatus.NOT_REQUIRED);
            user.setReviewedAt(null); user.setReviewedBy(null); user.setRejectionReason(null);
        }
        if (emailChanged) {
            user.setPasswordResetOtpHash(null); user.setPasswordResetOtpExpiresAt(null); user.setPasswordResetOtpAttempts(0);
        }
        if (roleChanged || activeChanged || emailChanged) sessions.deleteAllByUser(user);
        save(user);
        audit(actor, "ADMIN_USER_UPDATED", "Updated user #" + userId + "; before: " + before
                + "; after: role=" + user.getRole() + "; active=" + user.getIsActive() + "; approval=" + user.getApprovalStatus()
                + "; emailChanged=" + emailChanged + "; usernameChanged=" + nameChanged + "; reason=" + request.reason().strip());
        return AdminUserResponse.from(user);
    }

    private User requireAdmin() {
        return users.findByEmail(SecurityUtils.getCurrentUserEmail()).filter(this::isActiveAdmin)
                .orElseThrow(() -> new AppException(ErrorCode.ACCESS_DENIED));
    }
    private User requireLockedAdmin() {
        String email = SecurityUtils.getCurrentUserEmail();
        // Cross-locking two admins cannot remove the final active admin: the waiting actor is checked again.
        return users.findAdminsForUpdate().stream().filter(user -> email.equals(user.getEmail()) && isActiveAdmin(user)).findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.ACCESS_DENIED));
    }
    private boolean isActiveAdmin(User user) { return user.getRole() == UserRole.ADMIN && Boolean.TRUE.equals(user.getIsActive()); }
    private void validateId(Long id) { if (id == null || id <= 0) throw new AppException(ErrorCode.INVALID_PARAMETER); }
    private void save(User user) {
        try { users.saveAndFlush(user); }
        catch (DataIntegrityViolationException conflict) { throw new AppException(ErrorCode.ADMIN_USER_IDENTIFIER_CONFLICT); }
    }
    private void audit(User actor, String action, String message) {
        var request = new ActivityLogRequest(actor.getUserId(), ActivityActorType.USER, action, ActivityLogStatus.SUCCESS, "USER", null, message, null);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { logs.log(request); }
            });
        } else logs.log(request);
    }
}
