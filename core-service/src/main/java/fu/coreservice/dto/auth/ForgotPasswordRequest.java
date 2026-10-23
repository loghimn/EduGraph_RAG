package fu.coreservice.dto.auth;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {}
