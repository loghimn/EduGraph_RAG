package fu.coreservice.service.impl;

import fu.coreservice.service.AccountService;
import fu.coreservice.service.PasswordResetMailer;

import org.springframework.mail.MailException;

import fu.coreservice.dto.auth.ChangePasswordRequest;
import fu.coreservice.dto.auth.ForgotPasswordRequest;
import fu.coreservice.dto.auth.ResetPasswordRequest;
import fu.coreservice.dto.auth.UpdateProfileRequest;
import fu.coreservice.dto.auth.UserInfoResponse;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.exception.InvalidResetOtpException;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final PasswordResetMailer mailer;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    @Transactional
    public UserInfoResponse update(UpdateProfileRequest request) {
        User user = currentUser();
        String username = request.username().strip();
        if (username.length() < 3) throw new AppException(ErrorCode.INVALID_REQUEST);
        if (!username.equals(user.getUsername()) && users.existsByUsername(username))
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        user.setUsername(username);
        return UserInfoResponse.builder().userId(user.getUserId()).email(user.getEmail())
                .username(username).role(user.getRole()).isActive(user.getIsActive()).build();
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = currentUser();
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash()))
            throw new AppException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        replacePassword(user, request.newPassword());
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        User user = users.findLockedByEmail(request.email().strip()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        // A persistent per-account cooldown applies across application instances.
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())
                || (user.getResetRequestedAt() != null && user.getResetRequestedAt().plusMinutes(1).isAfter(now))) return;
        String otp;
        do {
            otp = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        } while (user.getPasswordResetOtpHash() != null && encoder.matches(otp, user.getPasswordResetOtpHash()));
        user.setPasswordResetOtpHash(encoder.encode(otp));
        user.setPasswordResetOtpExpiresAt(now.plusMinutes(5));
        user.setPasswordResetOtpAttempts(0);
        user.setResetRequestedAt(now);
        try {
            mailer.send(user.getEmail(), otp);
        } catch (MailException ex) {
            // Never log the OTP, password or mail-provider exception text.
            clearOtp(user);
            log.warn("Password reset email delivery failed for userId={}", user.getUserId());
        }
    }

    @Override
    @Transactional(noRollbackFor = InvalidResetOtpException.class)
    public void resetPassword(ResetPasswordRequest request) {
        User user = users.findLockedByEmail(request.email().strip())
                .orElseThrow(InvalidResetOtpException::new);
        int attempts = user.getPasswordResetOtpAttempts() == null ? 0 : user.getPasswordResetOtpAttempts();
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getPasswordResetOtpHash() == null
                || user.getPasswordResetOtpExpiresAt() == null
                || !user.getPasswordResetOtpExpiresAt().isAfter(LocalDateTime.now())
                || attempts >= 5) {
            clearOtp(user);
            throw new InvalidResetOtpException();
        }
        if (!encoder.matches(request.otp(), user.getPasswordResetOtpHash())) {
            user.setPasswordResetOtpAttempts(attempts + 1);
            if (attempts + 1 >= 5) clearOtp(user);
            // This exception must not roll back the persisted failed-attempt counter.
            throw new InvalidResetOtpException();
        }
        replacePassword(user, request.newPassword());
    }

    private void clearOtp(User user) {
        user.setPasswordResetOtpHash(null);
        user.setPasswordResetOtpExpiresAt(null);
        user.setPasswordResetOtpAttempts(0);
    }

    private User currentUser() {
        User user = users.findLockedByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (!Boolean.TRUE.equals(user.getIsActive())) throw new AppException(ErrorCode.ACCOUNT_DISABLED);
        return user;
    }

    private void replacePassword(User user, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AppException(ErrorCode.INVALID_REQUEST);
        user.setPasswordHash(encoder.encode(password));
        clearOtp(user);
        sessions.deleteAllByUser(user);
    }

}
