package fu.coreservice.dto.admin;
import fu.coreservice.entity.UserRole;
import jakarta.validation.constraints.*;
public record CreateAdminUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull UserRole role) {}
