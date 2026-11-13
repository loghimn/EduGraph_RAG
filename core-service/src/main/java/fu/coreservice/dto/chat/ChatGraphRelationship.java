package fu.coreservice.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatGraphRelationship(@NotBlank @Size(max = 200) String sourceId,
                                    @NotBlank @Size(max = 200) String targetId) {}
