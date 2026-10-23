package fu.coreservice.service;

import fu.coreservice.dto.auth.ChangePasswordRequest;
import fu.coreservice.dto.auth.ForgotPasswordRequest;
import fu.coreservice.dto.auth.ResetPasswordRequest;
import fu.coreservice.dto.auth.UpdateProfileRequest;
import fu.coreservice.dto.auth.UserInfoResponse;

public interface AccountService {
    UserInfoResponse update(UpdateProfileRequest request);
    void changePassword(ChangePasswordRequest request);
    void forgotPassword(ForgotPasswordRequest request);
    void resetPassword(ResetPasswordRequest request);
}
