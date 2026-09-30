package fu.coreservice.service;

import fu.coreservice.dto.AuthResponse;
import fu.coreservice.dto.auth.LoginRequest;
import fu.coreservice.dto.auth.RefreshTokenRequest;
import fu.coreservice.dto.auth.RegisterRequest;
import fu.coreservice.dto.auth.UserInfoResponse;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, HttpServletRequest httpRequest);

    AuthResponse refreshAccessToken(RefreshTokenRequest request);

    void logout(String refreshToken);

    void logoutAllSessions(Long userId);

    UserInfoResponse getCurrentUser(String email);
}
