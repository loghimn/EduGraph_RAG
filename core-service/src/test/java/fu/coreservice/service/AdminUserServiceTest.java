package fu.coreservice.service;
import fu.coreservice.dto.admin.*;
import fu.coreservice.entity.*;
import fu.coreservice.exception.*;
import fu.coreservice.repository.*;
import fu.coreservice.service.impl.AdminUserServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminUserServiceTest {
    UserRepository users = mock(UserRepository.class);
    UserSessionRepository sessions = mock(UserSessionRepository.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    ActivityLogService logs = mock(ActivityLogService.class);
    AdminUserService service = new AdminUserServiceImpl(users, sessions, encoder, logs);
    LocalDateTime time = LocalDateTime.of(2026, 11, 13, 12, 0);
    User actor, target;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@test.local", null, java.util.List.of()));
        actor = User.builder().userId(1L).email("admin@test.local").username("Admin").role(UserRole.ADMIN).updatedAt(time).build();
        target = User.builder().userId(2L).email("student@test.local").username("Student").role(UserRole.STUDENT).updatedAt(time).build();
        when(users.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        when(users.findAdminsForUpdate()).thenReturn(List.of(actor));
        when(users.findLockedById(2L)).thenReturn(Optional.of(target));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    UpdateAdminUserRequest request(UserRole role, boolean active) {
        return new UpdateAdminUserRequest(target.getEmail(), target.getUsername(), role, active, time, "Reviewed account access");
    }
    @Test void lockingRevokesRefreshSessionsAndAuditsWithoutCredentials() {
        service.update(2L, request(UserRole.STUDENT, false));
        assertThat(target.getIsActive()).isFalse(); verify(sessions).deleteAllByUser(target);
        verify(logs).log(argThat(log -> log.actorId().equals(1L) && log.action().equals("ADMIN_USER_UPDATED")
                && log.message().contains("user #2") && !log.message().contains("student@test.local")));
    }
    @Test void promotingToInstructorRequiresTheExistingApprovalFlow() {
        var result = service.update(2L, request(UserRole.INSTRUCTOR, true));
        assertThat(result.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(target.isApprovalSatisfied()).isFalse(); verify(sessions).deleteAllByUser(target);
    }
    @Test void editingApprovedInstructorDoesNotResetApprovalOrRevokeSessions() {
        target.setRole(UserRole.INSTRUCTOR); target.setApprovalStatus(ApprovalStatus.APPROVED);
        service.update(2L, new UpdateAdminUserRequest(target.getEmail(), "Updated teacher", target.getRole(), true, time, "Corrected display name"));
        assertThat(target.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED); verifyNoInteractions(sessions);
    }
    @Test void reopeningRejectedInstructorDoesNotApproveIt() {
        target.setRole(UserRole.INSTRUCTOR); target.setApprovalStatus(ApprovalStatus.REJECTED); target.setIsActive(false);
        service.update(2L, request(UserRole.INSTRUCTOR, true));
        assertThat(target.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED); assertThat(target.isApprovalSatisfied()).isFalse();
    }
    @Test void roleChangeClearsOldReviewData() {
        target.setRole(UserRole.INSTRUCTOR); target.setApprovalStatus(ApprovalStatus.APPROVED); target.setReviewedBy(1L); target.setReviewedAt(time);
        service.update(2L, request(UserRole.STUDENT, true));
        assertThat(target.getApprovalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED); assertThat(target.getReviewedBy()).isNull(); assertThat(target.getReviewedAt()).isNull();
    }
    @Test void staleSnapshotDoesNotMutateOrRevokeAnything() {
        target.setUpdatedAt(time.plusSeconds(1));
        assertThatThrownBy(() -> service.update(2L, request(UserRole.ADMIN, true))).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ADMIN_USER_CHANGED));
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(sessions, logs);
    }
    @Test void cannotChangeOwnAccessRoleOrEmail() {
        when(users.findLockedById(1L)).thenReturn(Optional.of(actor));
        for (var req : List.of(new UpdateAdminUserRequest(actor.getEmail(), actor.getUsername(), UserRole.ADMIN, false, time, "Test change"),
                new UpdateAdminUserRequest(actor.getEmail(), actor.getUsername(), UserRole.STUDENT, true, time, "Test change"),
                new UpdateAdminUserRequest("new@test.local", actor.getUsername(), UserRole.ADMIN, true, time, "Test change"))) {
            assertThatThrownBy(() -> service.update(1L, req)).isInstanceOfSatisfying(AppException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ADMIN_SELF_ACCESS_CHANGE));
        }
        verifyNoInteractions(sessions, logs); verify(users, never()).saveAndFlush(any());
    }
    @Test void revokedActorIsRecheckedAfterAcquiringTheAdminLocks() {
        actor.setIsActive(false);
        assertThatThrownBy(() -> service.update(2L, request(UserRole.ADMIN, true))).isInstanceOf(AppException.class);
        verify(users, never()).findLockedById(any());
    }
    @Test void identityChangeClearsResetOtpAndRefreshSessions() {
        target.setPasswordResetOtpHash("secret-otp"); target.setPasswordResetOtpExpiresAt(time.plusMinutes(5));
        service.update(2L, new UpdateAdminUserRequest("new@test.local", target.getUsername(), target.getRole(), true, time, "Corrected account email"));
        assertThat(target.getPasswordResetOtpHash()).isNull(); assertThat(target.getPasswordResetOtpExpiresAt()).isNull(); verify(sessions).deleteAllByUser(target);
    }
    @Test void duplicateUsernameAndOversizedUtf8PasswordAreRejected() {
        when(users.existsByUsername("Student")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateAdminUserRequest("new@test.local", "Student", "password123", UserRole.STUDENT))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.create(new CreateAdminUserRequest("new@test.local", "New user", "あ".repeat(30), UserRole.STUDENT))).isInstanceOf(AppException.class);
        verify(users, never()).saveAndFlush(any());
    }
}
