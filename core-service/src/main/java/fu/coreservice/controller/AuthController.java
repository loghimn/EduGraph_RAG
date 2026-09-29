package fu.coreservice.controller;

import fu.coreservice.dto.AuthResponse;
import fu.coreservice.dto.LoginRequest;
import fu.coreservice.dto.RefreshTokenRequest;
import fu.coreservice.dto.RegisterRequest;
import fu.coreservice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "API for user registration, login, and token management")
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "Register a new account",
            description = "Create a new user account with email, username, password, and role",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Account created successfully"),
                    @ApiResponse(responseCode = "400", description = "Email/username already exists or invalid input")
            }
    )
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Login",
            description = "Authenticate with email and password, returns access & refresh tokens",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Login successful"),
                    @ApiResponse(responseCode = "401", description = "Invalid credentials")
            }
    )
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Refresh access token",
            description = "Use a valid refresh token to get a new access token + refresh token",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Token refreshed successfully"),
                    @ApiResponse(responseCode = "400", description = "Invalid or expired refresh token")
            }
    )
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        AuthResponse response = authService.refreshAccessToken(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Logout",
            description = "Invalidate the current session by deleting the refresh token",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Logged out successfully")
            }
    )
    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok("Logged out successfully");
    }

    @Operation(
            summary = "Logout all sessions",
            description = "Invalidate all sessions for a given user ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "All sessions logged out")
            }
    )
    @PostMapping("/logout-all")
    public ResponseEntity<String> logoutAll(
            @Parameter(description = "User ID to logout all sessions") @RequestParam Long userId
    ) {
        authService.logoutAllSessions(userId);
        return ResponseEntity.ok("All sessions logged out successfully");
    }
}

