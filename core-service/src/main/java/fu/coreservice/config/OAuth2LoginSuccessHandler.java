package fu.coreservice.config;

import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.entity.UserSession;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.oauth2.frontend-success-url}")
    private String frontendSuccessUrl;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");

        log.info("OAuth2 login successful for email: {}", email);

        // Create or find existing user
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createOAuth2User(email, name));

        // Generate JWT tokens
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtService.generateAccessToken(userDetails, user.getUserId(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(userDetails, user.getUserId(), user.getRole().name());

        // Save session (with ipAddress + userAgent for consistency with AuthService.login)
        UserSession session = UserSession.builder()
                .user(user)
                .refreshToken(refreshToken)
                .ipAddress(getClientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .expiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpiration() / 1000))
                .build();
        userSessionRepository.save(session);

        log.info("JWT tokens generated for OAuth2 user: {}", email);

        // Check if this request came from Swagger's Authorize button flow
        // (proxy endpoint stores fromSwagger=true in session)
        jakarta.servlet.http.HttpSession httpSession = request.getSession(false);
        boolean fromSwagger = httpSession != null
                && Boolean.TRUE.equals(httpSession.getAttribute("fromSwagger"));

        String targetUrl;
        if (fromSwagger) {
            // Swagger Authorize button flow: redirect to Swagger's oauth2-redirect.html
            // Swagger extracts JWT from URL hash fragment and auto-sets it in Authorize header
            String state = httpSession.getAttribute("swagger_state") != null
                    ? (String) httpSession.getAttribute("swagger_state") : "";
            String swaggerRedirectUri = httpSession.getAttribute("swagger_redirect_uri") != null
                    ? (String) httpSession.getAttribute("swagger_redirect_uri") : "";

            // Clean up session
            httpSession.removeAttribute("fromSwagger");
            httpSession.removeAttribute("swagger_state");
            httpSession.removeAttribute("swagger_redirect_uri");

            // If Swagger provided a redirect_uri, use it; otherwise use the built-in one
            if (swaggerRedirectUri != null && !swaggerRedirectUri.isBlank()) {
                targetUrl = swaggerRedirectUri
                        + "#access_token=" + accessToken
                        + "&token_type=Bearer"
                        + "&expires_in=" + (jwtService.getAccessTokenExpiration() / 1000)
                        + "&state=" + state;
            } else {
                targetUrl = "/swagger-ui/oauth2-redirect.html"
                        + "#access_token=" + accessToken
                        + "&token_type=Bearer"
                        + "&expires_in=" + (jwtService.getAccessTokenExpiration() / 1000)
                        + "&state=" + state;
            }
            log.info("Redirecting to Swagger with JWT token");
        } else if (frontendSuccessUrl != null && !frontendSuccessUrl.isBlank()) {
            // Frontend flow: redirect to React frontend with tokens
            targetUrl = UriComponentsBuilder.fromUriString(frontendSuccessUrl)
                    .queryParam("accessToken", accessToken)
                    .queryParam("refreshToken", refreshToken)
                    .queryParam("tokenType", "Bearer")
                    .build().toUriString();
            log.info("Redirecting to frontend: {}", targetUrl);
        } else {
            // Fallback: show token in HTML page
            response.setContentType("text/html");
            response.getWriter().write("""
                    <html><body>
                    <h2>Login Successful!</h2>
                    <p>Your access token:</p>
                    <textarea readonly rows="4" cols="80">%s</textarea>
                    <br><br>
                    <p>Use this as Bearer token in Swagger or API calls.</p>
                    </body></html>""".formatted(accessToken));
            return;
        }

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private User createOAuth2User(String email, String name) {
        String baseUsername = email.split("@")[0];
        String username = baseUsername;
        int counter = 1;
        while (userRepository.existsByUsername(username)) {
            username = baseUsername + counter;
            counter++;
        }

        User user = User.builder()
                .email(email)
                .username(username)
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(UserRole.STUDENT)
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);
        log.info("New OAuth2 user created: {} with email: {}", savedUser.getUsername(), email);
        return savedUser;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
