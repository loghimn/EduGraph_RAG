package fu.coreservice.controller;
import fu.coreservice.service.AccountService;
import fu.coreservice.exception.*;
import fu.coreservice.dto.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountControllerTest {
    AccountService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(AccountService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void forgotReturnsGenericSuccessWithoutOtp() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.otp").doesNotExist());
        verify(service).forgotPassword(new ForgotPasswordRequest("student@example.com"));
    }
    @Test void invalidEmailRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void resetRejectsWeakPassword() throws Exception {
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@example.com\",\"otp\":\"012345\",\"newPassword\":\"123\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void resetReturnsDomainErrorForInvalidOtp() throws Exception {
        doThrow(new AppException(ErrorCode.INVALID_RESET_OTP)).when(service).resetPassword(any());
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@example.com\",\"otp\":\"012345\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("AUTH_007"));
    }
    @Test void resetRejectsNonSixDigitOtpBeforeService() throws Exception {
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@example.com\",\"otp\":\"12345\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void resetAcceptsLeadingZeroOtp() throws Exception {
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@example.com\",\"otp\":\"012345\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isOk());
        verify(service).resetPassword(new ResetPasswordRequest("student@example.com", "012345", "new-password"));
    }
    @Test void profileEndpointReturnsUpdatedUser() throws Exception {
        when(service.update(any())).thenReturn(UserInfoResponse.builder().username("newname").build());
        mvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newname\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("newname"));
    }
    @Test void changePasswordEndpointInvokesService() throws Exception {
        mvc.perform(put("/api/users/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isOk());
        verify(service).changePassword(new ChangePasswordRequest("old-password", "new-password"));
    }
}
