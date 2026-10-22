package fu.coreservice.dto.learningpath;

import jakarta.validation.constraints.*;
import java.util.List;

public record CreateLearningPathRequest(
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String planName,
        @Positive Long documentId,
        @Size(max = 1000) List<@NotBlank @Size(max = 100) String> goalConceptIds,
        @Size(max = 1000) List<@NotBlank @Size(max = 100) String> knownConceptIds,
        Boolean includeExternalPrereqs,
        Boolean assumeKnownPrerequisites,
        @PositiveOrZero Long expectedGraphVersion
) {}
