package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.auth.ChangePasswordRequest;
import fu.coreservice.dto.auth.ForgotPasswordRequest;
import fu.coreservice.dto.auth.ResetPasswordRequest;
import fu.coreservice.dto.auth.UpdateProfileRequest;
import fu.coreservice.dto.auth.UserInfoResponse;
import fu.coreservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequiredArgsConstructor
public class AccountController {
    private final AccountService service;

    @Operation(summary = "Update my username")
    @PatchMapping("/api/users/me")
    public ApiResponse<UserInfoResponse> update(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.<UserInfoResponse>builder().success(true).message("Profile updated")
                .data(service.update(request)).build();
    }

    @Operation(summary = "Change my password and revoke existing tokens")
    @PutMapping("/api/users/me/password")
    public ApiResponse<Void> change(@Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(request);
        return success("Password changed. Please sign in again.");
    }

    @Operation(summary = "Request a 6-digit password reset OTP by email")
    @PostMapping("/api/auth/forgot-password")
    public ApiResponse<Void> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        service.forgotPassword(request);
        return success("If the email is eligible, a password reset OTP will be sent.");
    }

    @Operation(summary = "Reset password using email and a single-use 6-digit OTP")
    @PostMapping("/api/auth/reset-password")
    public ApiResponse<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(request);
        return success("Password reset. Please sign in again.");
    }

    private ApiResponse<Void> success(String message) {
        return ApiResponse.<Void>builder().success(true).message(message).build();
    }
}
