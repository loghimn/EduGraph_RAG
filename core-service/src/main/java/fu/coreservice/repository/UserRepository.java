package fu.coreservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.entity.ApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<User> {

    /** Serialize privileged mutations in a stable order, then revalidate the acting admin. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = fu.coreservice.entity.UserRole.ADMIN order by u.userId")
    java.util.List<User> findAdminsForUpdate();

    boolean existsByEmailIgnoreCaseAndUserIdNot(String email, Long userId);
    boolean existsByUsernameAndUserIdNot(String username, Long userId);
    boolean existsByEmailIgnoreCase(String email);
    long countByCreatedAtGreaterThanEqual(java.time.LocalDateTime since);
    interface Population {
        UserRole getRole(); Boolean getActive(); ApprovalStatus getApproval(); long getCount();
    }
    @Query("select u.role as role, u.isActive as active, u.approvalStatus as approval, count(u) as count from User u group by u.role, u.isActive, u.approvalStatus")
    java.util.List<Population> population();

    Page<User> findByRoleAndApprovalStatus(UserRole role, ApprovalStatus approvalStatus, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId")
    Optional<User> findLockedById(@Param("userId") Long userId);

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findLockedByEmail(@Param("email") String email);

    @Modifying
    @Query("update User u set u.passwordResetOtpHash = null, u.passwordResetOtpExpiresAt = null, "
            + "u.passwordResetOtpAttempts = 0 where u.userId = :userId and u.passwordResetOtpHash = :hash")
    int clearPasswordResetOtpIfMatches(@Param("userId") Long userId, @Param("hash") String hash);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
