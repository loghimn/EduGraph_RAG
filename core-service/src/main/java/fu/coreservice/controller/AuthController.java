package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.auth.AuthResponse;
import fu.coreservice.dto.auth.LoginRequest;
import fu.coreservice.dto.auth.RefreshTokenRequest;
import fu.coreservice.dto.auth.RegisterRequest;
import fu.coreservice.dto.auth.UserInfoResponse;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.service.AuthService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "API for user registration, login, and token management")
public class AuthController {

    private final AuthService authService;
    // ── Google OAuth2 Login (Swagger Authorize button) ─────────────────
    /**
     * Proxy endpoint for Swagger's OAuth2 Authorize button.
     * Stores a flag in HTTP session so OAuth2LoginSuccessHandler knows
     * to redirect back to Swagger's oauth2-redirect.html with JWT.
     */
    @Hidden
    @GetMapping("/google/authorize")
    public void googleAuthorizeProxy(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        // Store Swagger's state + redirect_uri in session
        request.getSession().setAttribute("fromSwagger", true);
        request.getSession().setAttribute("swagger_state", state);
        request.getSession().setAttribute("swagger_redirect_uri", redirectUri);

        // Redirect to Spring Security's built-in OAuth2 login flow
        response.sendRedirect("/oauth2/authorization/google");
    }

    // ── Standard JWT endpoints ─────────────────────────────────────────

    @Operation(
            summary = "Register a new account",
            description = "Create a new user account with email, username, password, and role",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created successfully"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Email/username already exists or invalid input")
            }
    )
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<AuthResponse>builder()
                        .success(true)
                        .message("Account created successfully")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Login",
            description = "Authenticate with email and password, returns access & refresh tokens",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid credentials")
            }
    )
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(
                ApiResponse.<AuthResponse>builder()
                        .success(true)
                        .message("Login successful")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Refresh access token",
            description = "Use a valid refresh token to get a new access token + refresh token",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token refreshed successfully"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or expired refresh token")
            }
    )
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        AuthResponse response = authService.refreshAccessToken(request);
        return ResponseEntity.ok(
                ApiResponse.<AuthResponse>builder()
                        .success(true)
                        .message("Token refreshed successfully")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Logout",
            description = "Invalidate the current session by deleting the refresh token",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logged out successfully")
            }
    )
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .success(true)
                        .message("Logged out successfully")
                        .build()
        );
    }

    @Operation(
            summary = "Logout all sessions",
            description = "Invalidate all sessions for a given user ID",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "All sessions logged out")
            }
    )
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
            @Parameter(description = "User ID to logout all sessions") @RequestParam Long userId
    ) {
        authService.logoutAllSessions(userId);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .success(true)
                        .message("All sessions logged out successfully")
                        .build()
        );
    }

    @Operation(
            summary = "Get current user info",
            description = "Returns the profile of the currently authenticated user. "
                    + "Requires Bearer token in Authorization header.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User info returned"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
            }
    )
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserInfoResponse>> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.<UserInfoResponse>builder()
                            .success(false)
                            .errorCode(ErrorCode.UNAUTHORIZED.getCode())
                            .message("Not authenticated")
                            .build()
            );
        }

        UserInfoResponse response = authService.getCurrentUser(userDetails.getUsername());
        return ResponseEntity.ok(
                ApiResponse.<UserInfoResponse>builder()
                        .success(true)
                        .message("Success")
                        .data(response)
                        .build()
        );
    }

}

