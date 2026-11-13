package fu.coreservice.dto.admin;
import fu.coreservice.entity.UserRole;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
public record UpdateAdminUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotNull UserRole role, @NotNull Boolean isActive,
        @NotNull LocalDateTime expectedUpdatedAt,
        @NotBlank @Size(min = 3, max = 500) String reason) {}
