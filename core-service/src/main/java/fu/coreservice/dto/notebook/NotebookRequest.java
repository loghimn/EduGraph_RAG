package fu.coreservice.dto.notebook;
import jakarta.validation.constraints.*;
public record NotebookRequest(@NotBlank @Size(max=255) String title,
 @Size(max=10000) String description, @NotBlank @Size(max=255) String domain,
 @NotBlank @Pattern(regexp="vi|en") String language) {}
