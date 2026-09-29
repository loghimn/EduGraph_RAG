package fu.coreservice.service;

import fu.coreservice.config.JwtService;
import fu.coreservice.dto.AuthResponse;
import fu.coreservice.dto.LoginRequest;
import fu.coreservice.dto.RefreshTokenRequest;
import fu.coreservice.dto.RegisterRequest;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserSession;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already taken");
        }

        User user = User.builder()
                .email(request.getEmail())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .isActive(true)
                .build();

        user = userRepository.save(user);
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        return buildAuthResponse(accessToken, refreshToken);
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.getIsActive()) {
            throw new RuntimeException("Account is deactivated");
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        UserSession session = UserSession.builder()
                .user(user)
                .refreshToken(refreshToken)
                .ipAddress(getClientIp(httpRequest))
                .userAgent(httpRequest.getHeader("User-Agent"))
                .expiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000))
                .build();
        userSessionRepository.save(session);

        return buildAuthResponse(accessToken, refreshToken);
    }

    @Transactional
    public AuthResponse refreshAccessToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        UserSession session = userSessionRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            userSessionRepository.delete(session);
            throw new RuntimeException("Refresh token expired");
        }

        User user = session.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String newAccessToken = jwtService.generateAccessToken(userDetails);
        String newRefreshToken = jwtService.generateRefreshToken(userDetails);

        session.setRefreshToken(newRefreshToken);
        session.setExpiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000));
        userSessionRepository.save(session);

        return buildAuthResponse(newAccessToken, newRefreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            userSessionRepository.deleteByRefreshToken(refreshToken);
        }
    }

    @Transactional
    public void logoutAllSessions(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        userSessionRepository.deleteAllByUser(user);
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
