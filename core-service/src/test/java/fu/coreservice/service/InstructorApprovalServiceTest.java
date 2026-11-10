package fu.coreservice.service;

import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.impl.InstructorApprovalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstructorApprovalServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final InstructorApprovalService service = new InstructorApprovalServiceImpl(users);
    private User user;

    @BeforeEach void setup() {
        when(users.findByEmail("admin@test.local")).thenReturn(Optional.of(User.builder()
                .userId(5L).role(UserRole.ADMIN).build()));
        user = User.builder().userId(1L).email("teacher@test.local").role(UserRole.INSTRUCTOR)
                .approvalStatus(ApprovalStatus.PENDING).build();
        when(users.findLockedById(1L)).thenReturn(Optional.of(user));
    }

    @Test void approveRecordsReviewerAndEnablesAuthentication() {
        user.setIsActive(false);
        var result = service.review(1L, "admin@test.local", true, null);
        assertThat(result.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(result.reviewedBy()).isEqualTo(5L);
        assertThat(result.reviewedAt()).isNotNull();
        assertThat(user.isApprovalSatisfied()).isTrue();
        assertThat(user.getIsActive()).isFalse(); // Approval must not unlock an account.
        verify(users).save(user);
    }

    @Test void rejectRecordsReasonAndKeepsAuthenticationBlocked() {
        var result = service.review(1L, "admin@test.local", false, "  Invalid credentials  ");
        assertThat(result.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("Invalid credentials");
        assertThat(result.reviewedBy()).isEqualTo(5L);
        assertThat(user.isApprovalSatisfied()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalStatus.class, names = {"APPROVED", "REJECTED", "NOT_REQUIRED"})
    void reviewRequiresPendingStatus(ApprovalStatus status) {
        user.setApprovalStatus(status);
        assertThatThrownBy(() -> service.review(1L, "admin@test.local", true, null))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSTRUCTOR_REVIEW_NOT_PENDING));
        verify(users, never()).save(any());
    }

    @Test void studentCannotBeApprovedAsInstructor() {
        user.setRole(UserRole.STUDENT);
        assertThatThrownBy(() -> service.review(1L, "admin@test.local", true, null)).isInstanceOf(AppException.class);
        verify(users, never()).save(any());
    }

    @Test void nonAdminCannotReview() {
        when(users.findByEmail("student@test.local")).thenReturn(Optional.of(User.builder().role(UserRole.STUDENT).build()));
        assertThatThrownBy(() -> service.review(1L, "student@test.local", true, null))
                .isInstanceOfSatisfying(AppException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verify(users, never()).findLockedById(any());
    }

    @Test void disabledAdminCannotReview() {
        when(users.findByEmail("admin@test.local")).thenReturn(Optional.of(User.builder().role(UserRole.ADMIN).isActive(false).build()));
        assertThatThrownBy(() -> service.review(1L, "admin@test.local", false, null)).isInstanceOf(AppException.class);
        verify(users, never()).save(any());
    }

    @Test void listRejectsInvalidPagination() {
        assertThatThrownBy(() -> service.list(ApprovalStatus.PENDING, -1, 20)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.list(ApprovalStatus.PENDING, 0, 101)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.list(ApprovalStatus.NOT_REQUIRED, 0, 20)).isInstanceOf(AppException.class);
        verifyNoInteractions(users);
    }
}
