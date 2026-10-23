package fu.coreservice.dto.auth;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record UpdateProfileRequest(@NotBlank @Size(min = 3, max = 100) String username) {}
