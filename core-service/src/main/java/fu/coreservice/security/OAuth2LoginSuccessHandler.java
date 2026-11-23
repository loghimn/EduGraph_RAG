package fu.coreservice.security;

import fu.coreservice.config.SwaggerOAuthCondition;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.entity.UserSession;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.UserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final OAuth2LoginFailureHandler failureHandler;
    private final boolean swaggerEnabled;

    public OAuth2LoginSuccessHandler(UserRepository userRepository, UserSessionRepository userSessionRepository,
                                    JwtService jwtService, UserDetailsService userDetailsService,
                                    PasswordEncoder passwordEncoder, OAuth2LoginFailureHandler failureHandler,
                                    Environment environment) {
        this.userRepository = userRepository;
        this.userSessionRepository = userSessionRepository;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.failureHandler = failureHandler;
        this.swaggerEnabled = SwaggerOAuthCondition.isEnabled(environment);
    }

    @Value("${app.oauth2.frontend-success-url}")
    private String frontendSuccessUrl;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");

        log.info("OAuth2 login successful for email: {}", email);

        // Lock before loading account state, serializing issuance with logout-all.
        User user = userRepository.findLockedByEmail(email)
                .orElseGet(() -> createOAuth2User(email, name));

        // OAuth authentication must not bypass account approval or leave a usable session.
        try {
            user.requireCanAuthenticate();
        } catch (AppException ex) {
            SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            failureHandler.onAuthenticationFailure(request, response,
                    new DisabledException(ex.getMessage()));
            return;
        }

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
        // Bind cookie authentication to the account state at OAuth2 sign-in.
        request.getSession().setAttribute(SessionUserDetails.OAUTH2_SESSION_ACCOUNT,
                new SessionUserDetails(user.getEmail(), user.getPasswordHash(), true,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                        user.getSessionVersion()));


        log.info("JWT tokens generated for OAuth2 user: {}", email);

        // Only trust context recovered from the authorization request with matching state.
        Object callbackContext = request.getAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST);
        request.removeAttribute(LoginAuthorizationRequestRepository.CALLBACK_REQUEST);
        var authorizationRequest = callbackContext instanceof OAuth2AuthorizationRequest value ? value : null;
        boolean fromSwagger = authorizationRequest != null
                && Boolean.TRUE.equals(authorizationRequest.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_MODE));
        Object storedState = authorizationRequest == null ? null
                : authorizationRequest.getAttribute(LoginAuthorizationRequestRepository.SWAGGER_STATE);
        String swaggerState = storedState instanceof String value ? value : "";
        // Remove legacy session flags without honoring them.
        jakarta.servlet.http.HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.removeAttribute("fromSwagger");
            httpSession.removeAttribute("swagger_state");
            httpSession.removeAttribute("swagger_redirect_uri");
        }

        String targetUrl;
        if (fromSwagger && swaggerEnabled) {
            // The development Swagger callback is fixed to this origin and path.
            targetUrl = "/swagger-ui/oauth2-redirect.html"
                    + "#access_token=" + accessToken
                    + "&token_type=Bearer"
                    + "&expires_in=" + (jwtService.getAccessTokenExpiration() / 1000)
                    + "&state=" + UriUtils.encode(swaggerState, StandardCharsets.UTF_8);
            log.info("Redirecting to Swagger with JWT token");
        } else if (frontendSuccessUrl != null && !frontendSuccessUrl.isBlank()) {
            // Frontend flow: redirect to React frontend with tokens
            targetUrl = UriComponentsBuilder.fromUriString(frontendSuccessUrl)
                    .queryParam("accessToken", accessToken)
                    .queryParam("refreshToken", refreshToken)
                    .queryParam("tokenType", "Bearer")
                    .build().toUriString();
            log.info("Redirecting to configured frontend callback");
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
