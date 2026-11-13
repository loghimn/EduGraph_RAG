package fu.coreservice.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatSessionRenameRequest(@NotBlank @Size(max = 255) String title) {}
