package fu.coreservice.service.impl;

import fu.coreservice.service.ActivityAlertService;

import fu.coreservice.config.JwtService;
import fu.coreservice.dto.auth.AuthResponse;
import fu.coreservice.dto.auth.LoginRequest;
import fu.coreservice.dto.auth.RefreshTokenRequest;
import fu.coreservice.dto.auth.RegisterRequest;
import fu.coreservice.dto.auth.RegisterResponse;
import fu.coreservice.dto.auth.RegistrationRole;
import fu.coreservice.entity.ApprovalStatus;
import fu.coreservice.entity.UserRole;
import fu.coreservice.dto.auth.UserInfoResponse;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserSession;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import fu.coreservice.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    private final ActivityAlertService alerts;

    public AuthServiceImpl(UserRepository userRepository, UserSessionRepository userSessionRepository,
                           PasswordEncoder passwordEncoder, JwtService jwtService,
                           AuthenticationManager authenticationManager, UserDetailsService userDetailsService,
                           ActivityAlertService alerts) {
        this.alerts = alerts;
        this.userRepository = userRepository;
        this.userSessionRepository = userSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (request.getRole() == null) throw new AppException(ErrorCode.INVALID_REQUEST);
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(request.getEmail())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.valueOf(request.getRole().name()))
                .approvalStatus(request.getRole() == RegistrationRole.INSTRUCTOR
                        ? ApprovalStatus.PENDING : ApprovalStatus.NOT_REQUIRED)
                .isActive(true)
                .build();

        user = userRepository.save(user);
        if (user.getApprovalStatus() == ApprovalStatus.PENDING) {
            return RegisterResponse.builder().approvalStatus(ApprovalStatus.PENDING).build();
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails, user.getUserId(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(userDetails, user.getUserId(), user.getRole().name());

        UserSession session = UserSession.builder()
                .user(user)
                .refreshToken(refreshToken)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000))
                .build();
        userSessionRepository.save(session);

        return RegisterResponse.builder().approvalStatus(user.getApprovalStatus())
                .accessToken(accessToken).refreshToken(refreshToken).tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpiration() / 1000).build();
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        try {
            // Serialize credential verification and issuance with password changes.
            User user = userRepository.findLockedByEmail(request.getEmail())
                    .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));
            try {
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
            } catch (DisabledException ex) {
                user.requireCanAuthenticate();
                throw ex;
            }
            user.requireCanAuthenticate();

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
            String accessToken = jwtService.generateAccessToken(userDetails, user.getUserId(), user.getRole().name());
            String refreshToken = jwtService.generateRefreshToken(userDetails, user.getUserId(), user.getRole().name());

            UserSession session = UserSession.builder()
                    .user(user)
                    .refreshToken(refreshToken)
                    .ipAddress(getClientIp(httpRequest))
                    .userAgent(httpRequest.getHeader("User-Agent"))
                    .expiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000))
                    .build();
            userSessionRepository.save(session);

            alerts.loginSucceeded(request.getEmail());
            return buildAuthResponse(accessToken, refreshToken);
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            alerts.loginFailed(request.getEmail());
            throw ex;
        } catch (AppException ex) {
            if (ex.getErrorCode() == ErrorCode.INVALID_CREDENTIALS) alerts.loginFailed(request.getEmail());
            throw ex;
        }
    }

    @Override
    @Transactional
    public AuthResponse refreshAccessToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        String email = userSessionRepository.findEmailByRefreshToken(refreshToken)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_REFRESH_TOKEN));
        User user = userRepository.findLockedByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_REFRESH_TOKEN));
        UserSession session = userSessionRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            userSessionRepository.delete(session);
            throw new AppException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        user.requireCanAuthenticate();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String newAccessToken = jwtService.generateAccessToken(userDetails, user.getUserId(), user.getRole().name());
        String newRefreshToken = jwtService.generateRefreshToken(userDetails, user.getUserId(), user.getRole().name());

        session.setRefreshToken(newRefreshToken);
        session.setExpiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000));
        userSessionRepository.save(session);

        return buildAuthResponse(newAccessToken, newRefreshToken);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            userSessionRepository.deleteByRefreshToken(refreshToken);
        }
    }

    @Override
    @Transactional
    public void logoutAllSessions(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        userSessionRepository.deleteAllByUser(user);
    }

    @Override
    public UserInfoResponse getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        return UserInfoResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .approvalStatus(user.getApprovalStatus())
                .build();
    }

    private AuthResponse buildAuthResponse(String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpiration() / 1000)
                .build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
