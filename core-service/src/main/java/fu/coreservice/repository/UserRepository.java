package fu.coreservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

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
